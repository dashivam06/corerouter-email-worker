package com.fleebug.config;

import com.fleebug.utility.Env;

/**
 * Centralized configuration for the vLLM Chat Completion Worker.
 * All values are read from environment variables with sensible defaults.
 */
public class ChatTaskConfig {


    // ── Stream ─────────────────────────────────────────────────────
    public static final String STREAM_KEY = env("STREAM_KEY", "stream:tasks");
    public static final String CONSUMER_GROUP = env("CONSUMER_GROUP", "worker-group");
    public static final String CONSUMER_NAME = env("CONSUMER_NAME", "worker-1");

    // ── Spring API ─────────────────────────────────────────────────
    public static final String API_BASE_URL = env("API_BASE_URL", "https://corerouter.me");

    // ── vLLM ───────────────────────────────────────────────────────
    public static final int VLLM_TIMEOUT_SECONDS = Integer.parseInt(env("VLLM_TIMEOUT", "120"));

    // ── Cache ──────────────────────────────────────────────────────
    public static final int CACHE_TTL_SECONDS = Integer.parseInt(env("CACHE_TTL", "3600"));

    // ── Retry ──────────────────────────────────────────────────────
    public static final int API_MAX_RETRIES = 3;
    public static final long API_RETRY_BASE_DELAY_MS = 1000;

    // ── Stream read ────────────────────────────────────────────────
    public static final int STREAM_READ_COUNT = 10;
    public static final int STREAM_BLOCK_MS = 5000;

    // ── Concurrency ────────────────────────────────────────────────
    public static final int WORKER_THREADS = Integer.parseInt(env("WORKER_THREADS", "3"));

    private ChatTaskConfig() { }

    private static String env(String key, String fallback) {
        String value = Env.get(key);
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
