package com.fleebug.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleebug.config.ChatTaskConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final MediaType JSON = MediaType.get(ChatTaskConfig.CONTENT_TYPE_JSON);

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

    private ApiClient() {}

    public static OkHttpClient getVllmClient() {
        return vllmClient;
    }

    public static String get(String url) throws IOException {
        Request req = new Request.Builder().url(url).get().headers(authHeaders()).build();
        try (Response res = apiClient.newCall(req).execute()) {
            return unwrapApiResponse(res);
        }
    }

    public static void patch(String url, String jsonBody) throws IOException {
        Request req = new Request.Builder().url(url)
                .patch(RequestBody.create(jsonBody, JSON)).headers(authHeaders()).build();
        try (Response res = apiClient.newCall(req).execute()) {
            if (!res.isSuccessful() && res.code() != 204) {
                String body = res.body() != null ? res.body().string() : "";
                throw new IOException("PATCH failed (HTTP " + res.code() + "): " + body);
            }
        }
    }

    public static void post(String url, String jsonBody) throws IOException {
        Request req = new Request.Builder().url(url)
                .post(RequestBody.create(jsonBody, JSON)).headers(authHeaders()).build();
        try (Response res = apiClient.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String body = res.body() != null ? res.body().string() : "";
                throw new IOException("POST failed (HTTP " + res.code() + "): " + body);
            }
        }
    }

    private static okhttp3.Headers authHeaders() {
        okhttp3.Headers.Builder builder = new okhttp3.Headers.Builder();
        String workerSecret = Env.get(ChatTaskConfig.ENV_WORKER_SECRET);
        if (workerSecret != null && !workerSecret.isBlank()) {
            builder.add(ChatTaskConfig.HEADER_X_SERVICE_TOKEN, workerSecret);
        }
        return builder.build();
    }

    private static String unwrapApiResponse(Response res) throws IOException {
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
}
