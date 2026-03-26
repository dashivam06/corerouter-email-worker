package com.fleebug.service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleebug.config.ChatTaskConfig;
import com.fleebug.dto.task.model.BillingConfigDto;
import com.fleebug.dto.task.model.BillingUsageDto;
import com.fleebug.dto.task.model.ModelDto;
import com.fleebug.dto.task.vllm.*;
import com.fleebug.utility.ApiClient;
import com.fleebug.utility.Env;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VllmChatCompletionService {

    private final TelemetryClient telemetryClient = new TelemetryClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RedisService redis = new RedisService();

    public ModelDto getModel(String modelId) throws IOException {
        String cacheKey = RedisService.modelKey(modelId);
        String cached = redis.getFromCache(cacheKey);

        if (cached != null) {
            Map<String, String> properties = new HashMap<>();
            properties.put("modelId", modelId);
            telemetryClient.trackTrace("Cache Hit: Model", SeverityLevel.Verbose, properties);
            return ModelDto.fromJson(cached);
        }

        Map<String, String> properties = new HashMap<>();
        properties.put("modelId", modelId);
        telemetryClient.trackTrace("Cache Miss: Model", SeverityLevel.Verbose, properties);
        
        String url = ChatTaskConfig.API_MODELS_ENDPOINT + modelId;
        String json = ApiClient.get(url);
        redis.saveToCache(cacheKey, json, ChatTaskConfig.CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return ModelDto.fromJson(json);
    }

    public BillingConfigDto getBillingConfig(String modelId) {
        String cacheKey = RedisService.billingConfigKey(modelId);
        String cached = redis.getFromCache(cacheKey);

        try {
            if (cached != null) {
                Map<String, String> properties = new HashMap<>();
                properties.put("modelId", modelId);
                telemetryClient.trackTrace("Cache Hit: Billing", SeverityLevel.Verbose, properties);
                return BillingConfigDto.fromJson(cached);
            }

            Map<String, String> properties = new HashMap<>();
            properties.put("modelId", modelId);
            telemetryClient.trackTrace("Cache Miss: Billing", SeverityLevel.Verbose, properties);
            
            String url = ChatTaskConfig.API_BILLING_CONFIG_ENDPOINT + modelId;
            String json = ApiClient.get(url);
            redis.saveToCache(cacheKey, json, ChatTaskConfig.CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return BillingConfigDto.fromJson(json);
        } catch (Exception e) {
            Map<String, String> properties = new HashMap<>();
            properties.put("modelId", modelId);
            properties.put("error", e.getMessage());
            telemetryClient.trackException(e, properties, null);
            return new BillingConfigDto(null);
        }
    }

    public void updateTaskStatus(String taskId, String status, Object result) throws IOException {
        updateTaskStatus(taskId, status, result, null);
    }

    public void updateTaskStatus(String taskId, String status, Object result, String usageMetadata) throws IOException {
        TaskStatusUpdateDto body = new TaskStatusUpdateDto(taskId, status, result, usageMetadata);
        String url = ChatTaskConfig.API_TASK_STATUS_ENDPOINT;
        ApiClient.patch(url, body.toJson());
        
        Map<String, String> properties = new HashMap<>();
        properties.put("taskId", taskId);
        properties.put("status", status);
        telemetryClient.trackTrace("Status Updated", SeverityLevel.Information, properties);
    }

    public void recordBillingUsage(String taskId, int promptTokens, int completionTokens) {
        String url = ChatTaskConfig.API_BILLING_USAGE_ENDPOINT;
        try {
            BillingUsageDto input = new BillingUsageDto(taskId, ChatTaskConfig.BILLING_TYPE_INPUT, promptTokens);
            ApiClient.post(url, input.toJson());
            
            Map<String, String> inProps = new HashMap<>();
            inProps.put("taskId", taskId);
            inProps.put("tokens", String.valueOf(promptTokens));
            inProps.put("type", "input");
            telemetryClient.trackTrace("Billing Recorded", SeverityLevel.Information, inProps);

            BillingUsageDto output = new BillingUsageDto(taskId, ChatTaskConfig.BILLING_TYPE_OUTPUT, completionTokens);
            ApiClient.post(url, output.toJson());
            
            Map<String, String> outProps = new HashMap<>();
            outProps.put("taskId", taskId);
            outProps.put("tokens", String.valueOf(completionTokens));
            outProps.put("type", "output");
            telemetryClient.trackTrace("Billing Recorded", SeverityLevel.Information, outProps);
            
        } catch (IOException e) {
            Map<String, String> properties = new HashMap<>();
            properties.put("taskId", taskId);
            properties.put("error", e.getMessage());
            telemetryClient.trackException(e, properties, null);
        }
    }

    public VllmChatCompletionResponse callChatCompletion(String endpointUrl, String modelFullname,
                                                         ChatCompletionRequest unifiedRequest) throws IOException {
        
        // Ensure model name is injected if missing from payload
        if (unifiedRequest.getModel() == null) {
            unifiedRequest.setModel(modelFullname);
        }

        // Strategy: Standard Chat API (/v1/chat/completions) for ALL models (Qwen, Llama, etc.)
        // We use unifiedRequest directly as the payload, but process system prompt if present.
        
        if (unifiedRequest.getSystemPrompt() != null && !unifiedRequest.getSystemPrompt().isBlank()) {
            // Prepend system prompt as a system message
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", unifiedRequest.getSystemPrompt());
            if (unifiedRequest.getMessages() == null) {
                unifiedRequest.setMessages(new ArrayList<>());
            }
            unifiedRequest.getMessages().add(0, systemMsg);
            // Clear the field so it's not serialized as "system_prompt" in JSON
            unifiedRequest.setSystemPrompt(null);
        }
        
        String requestJson = MAPPER.writeValueAsString(unifiedRequest);
        String finalUrl = endpointUrl + ChatTaskConfig.VLLM_CHAT_COMPLETIONS_PATH; // Standard chat endpoint

        Map<String, String> payloadProps = new HashMap<>();
        payloadProps.put("type", "Standard/Chat");
        payloadProps.put("len", String.valueOf(requestJson.length()));
        payloadProps.put("model", modelFullname);
        telemetryClient.trackTrace("Payload Prepared", SeverityLevel.Verbose, payloadProps);

        RequestBody reqBody = RequestBody.create(requestJson, ApiClient.JSON);

        Request.Builder rb = new Request.Builder()
                .url(finalUrl)
                .post(reqBody)
                .header(ChatTaskConfig.HEADER_CONTENT_TYPE, ChatTaskConfig.CONTENT_TYPE_JSON);
                
        String workerSecret = Env.get(ChatTaskConfig.ENV_WORKER_SECRET);
        if (workerSecret != null && !workerSecret.isBlank()) {
            rb.addHeader(ChatTaskConfig.HEADER_X_SERVICE_TOKEN, workerSecret);
        }
        
        Request request = rb.build();

        try (Response response = ApiClient.getVllmClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                return VllmChatCompletionResponse.fromJson(body);
            }
            throw new IOException("vLLM error (HTTP " + response.code() + "): " + body);
        }
    }

}
