package com.fleebug.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleebug.config.ChatTaskConfig;
import com.fleebug.dto.task.model.BillingConfigDto;
import com.fleebug.dto.task.model.BillingUsageDto;
import com.fleebug.dto.task.model.ModelDto;
import com.fleebug.dto.task.vllm.TaskStatusUpdateDto;
import com.fleebug.dto.task.vllm.VllmChatCompletionRequest;
import com.fleebug.dto.task.vllm.VllmChatCompletionResponse;
import com.fleebug.utility.Env;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
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
    private static final MediaType JSON = MediaType.get("application/json");
    private final RedisService redis = new RedisService();

    private static final OkHttpClient apiClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(chain -> {
                IOException lastError = null;
                for (int attempt = 0; attempt < ChatTaskConfig.API_MAX_RETRIES; attempt++) {
                    try {
                        okhttp3.Response res = chain.proceed(chain.request());
                        if (res.isSuccessful() || res.code() == 204) return res;
                        res.close();
                        lastError = new IOException("HTTP " + res.code());
                    } catch (IOException e) {
                        lastError = e;
                    }
                    long delay = ChatTaskConfig.API_RETRY_BASE_DELAY_MS * (long) Math.pow(2, attempt);
                    log.warn("   retry {}/{}  {}  backoff {}ms",
                            attempt + 1, ChatTaskConfig.API_MAX_RETRIES, lastError.getMessage(), delay);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw lastError;
                    }
                }
                throw lastError;
            })
            .build();

    private static final OkHttpClient vllmClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(ChatTaskConfig.VLLM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public ModelDto getModel(String modelId) throws IOException {
        String cacheKey = RedisService.modelKey(modelId);
        String cached = redis.getFromCache(cacheKey);

        if (cached != null) {
            log.info("│   cache      model:{} (hit)", modelId);
            return ModelDto.fromJson(cached);
        }

        log.info("│   cache      model:{} (miss) — fetching", modelId);
        String url = ChatTaskConfig.API_BASE_URL + "/api/v1/admin/models/" + modelId;
        String json = apiGet(url);
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
            String url = ChatTaskConfig.API_BASE_URL + "/api/v1/admin/billing/configs/model/" + modelId;
            String json = apiGet(url);
            redis.saveToCache(cacheKey, json, ChatTaskConfig.CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return BillingConfigDto.fromJson(json);
        } catch (Exception e) {
            log.warn("│   billing    config unavailable — {}", e.getMessage());
            return new BillingConfigDto(null);
        }
    }

    public void updateTaskStatus(String taskId, String status, Object result) throws IOException {
        TaskStatusUpdateDto body = new TaskStatusUpdateDto(taskId, status, result);
        String url = ChatTaskConfig.API_BASE_URL + "/v1/tasks/status";
        apiPatch(url, body.toJson());
        log.info("│   status     → {}", status);
    }

    public void recordBillingUsage(String taskId, int promptTokens, int completionTokens) {
        String url = ChatTaskConfig.API_BASE_URL + "/api/v1/admin/billing/usage";
        try {
            BillingUsageDto input = new BillingUsageDto(taskId, "INPUT_TOKENS", promptTokens);
            apiPost(url, input.toJson());
            log.info("│   billing    prompt={}", promptTokens);

            BillingUsageDto output = new BillingUsageDto(taskId, "OUTPUT_TOKENS", completionTokens);
            apiPost(url, output.toJson());
            log.info("│   billing    completion={}", completionTokens);
        } catch (IOException e) {
            log.error("│   billing    recording failed — {}", e.getMessage());
        }
    }

    public VllmChatCompletionResponse callChatCompletion(String endpointUrl, String modelFullname,
                                                         Map<String, Object> payload) throws IOException {
        String url = endpointUrl + "/v1/completions";

        String prompt = buildPromptString(payload);
        Double temperature = asDouble(payload.get("temperature"));
        Integer maxTokens = asInteger(payload.get("max_tokens"));

        VllmChatCompletionRequest vllmReq = new VllmChatCompletionRequest(modelFullname, prompt, temperature, maxTokens);
        RequestBody reqBody = RequestBody.create(vllmReq.toJson(), JSON);

        Request request = new Request.Builder()
                .url(url)
                .post(reqBody)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = vllmClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                return VllmChatCompletionResponse.fromJson(body);
            }
            throw new IOException("vLLM error (HTTP " + response.code() + "): " + body);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildPromptString(Map<String, Object> payload) {
        // If payload already has a plain prompt string, use it directly
        Object promptObj = payload.get("prompt");
        if (promptObj instanceof String && !((String) promptObj).isBlank()) {
            return (String) promptObj;
        }

        // Convert messages array into a single prompt string
        Object msgs = payload.get("messages");
        if (msgs instanceof List) {
            List<Map<String, Object>> messages = (List<Map<String, Object>>) msgs;
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> msg : messages) {
                Object content = msg.get("content");
                if (content != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(content);
                }
            }
            return sb.toString();
        }

        throw new IllegalArgumentException("Payload must contain either 'messages' array or 'prompt' string");
    }

    private String apiGet(String url) throws IOException {
        Request req = new Request.Builder().url(url).get().headers(authHeaders()).build();
        try (Response res = apiClient.newCall(req).execute()) {
            return unwrapApiResponse(res);
        }
    }

    private void apiPatch(String url, String jsonBody) throws IOException {
        Request req = new Request.Builder().url(url)
                .patch(RequestBody.create(jsonBody, JSON)).headers(authHeaders()).build();
        try (Response res = apiClient.newCall(req).execute()) {
            if (!res.isSuccessful() && res.code() != 204) {
                String body = res.body() != null ? res.body().string() : "";
                throw new IOException("PATCH failed (HTTP " + res.code() + "): " + body);
            }
        }
    }

    private void apiPost(String url, String jsonBody) throws IOException {
        Request req = new Request.Builder().url(url)
                .post(RequestBody.create(jsonBody, JSON)).headers(authHeaders()).build();
        try (Response res = apiClient.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String body = res.body() != null ? res.body().string() : "";
                throw new IOException("POST failed (HTTP " + res.code() + "): " + body);
            }
        }
    }

    private okhttp3.Headers authHeaders() {
        okhttp3.Headers.Builder builder = new okhttp3.Headers.Builder()
                .add("Content-Type", "application/json");
        if (!Env.get("WORKER_SECRET").isBlank()) {
            builder.add("X-Service-Token", Env.get("WORKER_SECRET"));
        }
        return builder.build();
    }

    private String unwrapApiResponse(Response res) throws IOException {
        String body = res.body() != null ? res.body().string() : "";

        if (!res.isSuccessful()) {
            throw new IOException("API error (HTTP " + res.code() + "): " + body);
        }

        JsonNode root = MAPPER.readTree(body);
        JsonNode dataNode = root.path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) {
            throw new IOException("API response missing 'data' field: " + body);
        }
        return MAPPER.writeValueAsString(dataNode);
    }

    private static Double asDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    private static Integer asInteger(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }
}
