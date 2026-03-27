package com.fleebug.constants;

import com.fleebug.utility.Env;

public class PathConfig {

    public static final String API_BASE_URL = env("API_BASE_URL", "https://api.corerouter.me");
    public static final String API_HEARTBEAT_BASE_URL = env("API_HEARTBEAT_BASE_URL", API_BASE_URL);
    public static final String LOCALHOST_BASE_URL = env("LOCALHOST_BASE_URL", "http://localhost:7777");

    // API Endpoints
    public static final String API_MODELS_ENDPOINT = API_BASE_URL + "/api/v1/admin/models/";
    public static final String API_BILLING_CONFIG_ENDPOINT = API_BASE_URL + "/api/v1/admin/billing/configs/model/";
    public static final String API_BILLING_USAGE_ENDPOINT = API_BASE_URL + "/api/v1/admin/billing/usage";
    public static final String API_TASK_STATUS_ENDPOINT = API_BASE_URL + "/api/v1/tasks/status";
    public static final String API_HEARTBEAT_ENDPOINT = "/api/v1/internal/worker/heartbeat";

    // vLLM Paths
    public static final String VLLM_COMPLETIONS_PATH = "/v1/completions";
    public static final String VLLM_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private PathConfig() { }

    private static String env(String key, String fallback) {
        String value = Env.get(key);
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
