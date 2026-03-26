package com.fleebug.config;

import com.fleebug.utility.Env;

/**
 * Centralized configuration for the vLLM Chat Completion Worker.
 * All values are read from environment variables with sensible defaults.
 */
public class ChatTaskConfig {


    // ── Headers & Auth ─────────────────────────────────────────────
    public static final String ENV_WORKER_SECRET = "WORKER_SECRET";
    public static final String HEADER_X_SERVICE_TOKEN = "X-Service-Token";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_TYPE_JSON = "application/json";

    // ── Stream ─────────────────────────────────────────────────────
    public static final String STREAM_KEY = env("STREAM_KEY", "stream:tasks");
    public static final String CONSUMER_GROUP = env("CONSUMER_GROUP", "worker-group");
    public static final String CONSUMER_NAME = env("CONSUMER_NAME", "worker-1");

    // ── Spring API ─────────────────────────────────────────────────
    public static final String API_BASE_URL = env("API_BASE_URL", "https://corerouter.me");
    public static final String LOCALHOST_BASE_URL = env("LOCALHOST_BASE_URL", "http://localhost:7777");

    
    // API Endpoints
    public static final String API_MODELS_ENDPOINT = API_BASE_URL + "/api/v1/admin/models/";
    public static final String API_BILLING_CONFIG_ENDPOINT = API_BASE_URL + "/api/v1/admin/billing/configs/model/";
    public static final String API_BILLING_USAGE_ENDPOINT = API_BASE_URL + "/api/v1/admin/billing/usage";
    public static final String BILLING_TYPE_INPUT = "INPUT_TOKENS";
    public static final String BILLING_TYPE_OUTPUT = "OUTPUT_TOKENS";
    public static final String API_TASK_STATUS_ENDPOINT = API_BASE_URL + "/api/v1/tasks/status";

    // ── vLLM ───────────────────────────────────────────────────────
    public static final int VLLM_TIMEOUT_SECONDS = Integer.parseInt(env("VLLM_TIMEOUT", "120"));
    
    // vLLM Paths
    public static final String VLLM_COMPLETIONS_PATH = "/v1/completions";
    public static final String VLLM_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    // ── Cache ──────────────────────────────────────────────────────
    public static final int CACHE_TTL_SECONDS = Integer.parseInt(env("CACHE_TTL", "3600"));

    // ── Retry ──────────────────────────────────────────────────────
    public static final int API_MAX_RETRIES = 3;
    public static final long API_RETRY_BASE_DELAY_MS = 1000;

    // ── Stream read ────────────────────────────────────────────────
    public static final int STREAM_READ_COUNT = 10;
    public static final int STREAM_BLOCK_MS = 5000;
    public static final long STREAM_MAX_LEN = Long.parseLong(env("STREAM_MAX_LEN", "500"));

    // ── Concurrency ────────────────────────────────────────────────
    public static final int WORKER_THREADS = Integer.parseInt(env("WORKER_THREADS", "3"));

    private ChatTaskConfig() { }

    private static String env(String key, String fallback) {
        String value = Env.get(key);
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
