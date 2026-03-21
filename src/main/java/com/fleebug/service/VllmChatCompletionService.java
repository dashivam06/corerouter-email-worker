package com.fleebug.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleebug.config.ChatTaskConfig;
import com.fleebug.dto.task.model.BillingConfigDto;
import com.fleebug.dto.task.model.BillingUsageDto;
import com.fleebug.dto.task.model.ModelDto;
import com.fleebug.dto.task.vllm.*;
import com.fleebug.utility.ApiClient;
import com.fleebug.utility.Env;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VllmChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(VllmChatCompletionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RedisService redis = new RedisService();

    public ModelDto getModel(String modelId) throws IOException {
        String cacheKey = RedisService.modelKey(modelId);
        String cached = redis.getFromCache(cacheKey);

        if (cached != null) {
            log.info("│   cache      model:{} (hit)", modelId);
            return ModelDto.fromJson(cached);
        }

        log.info("│   cache      model:{} (miss) — fetching", modelId);
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
                log.info("│   cache      billing:{} (hit)", modelId);
                return BillingConfigDto.fromJson(cached);
            }

            log.info("│   cache      billing:{} (miss) — fetching", modelId);
            String url = ChatTaskConfig.API_BILLING_CONFIG_ENDPOINT + modelId;
            String json = ApiClient.get(url);
            redis.saveToCache(cacheKey, json, ChatTaskConfig.CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return BillingConfigDto.fromJson(json);
        } catch (Exception e) {
            log.warn("│   billing    config unavailable — {}", e.getMessage());
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
        log.info("│   status     → {}", status);
    }

    public void recordBillingUsage(String taskId, int promptTokens, int completionTokens) {
        String url = ChatTaskConfig.API_BILLING_USAGE_ENDPOINT;
        try {
            BillingUsageDto input = new BillingUsageDto(taskId, ChatTaskConfig.BILLING_TYPE_INPUT, promptTokens);
            ApiClient.post(url, input.toJson());
            log.info("│   billing    prompt={}", promptTokens);

            BillingUsageDto output = new BillingUsageDto(taskId, ChatTaskConfig.BILLING_TYPE_OUTPUT, completionTokens);
            ApiClient.post(url, output.toJson());
            log.info("│   billing    completion={}", completionTokens);
        } catch (IOException e) {
            log.error("│   billing    recording failed — {}", e.getMessage());
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

        log.info("│   payload    type={} len={} model={}", 
                "Standard/Chat", 
                requestJson.length(), 
                modelFullname);

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
