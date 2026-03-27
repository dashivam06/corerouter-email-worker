package com.fleebug.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fleebug.constants.PathConfig;
import com.fleebug.utility.ApiClient;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

/**
 * Sends worker heartbeat updates to the API every 30 seconds.
 */
public class HeartbeatService {

    private final TelemetryClient telemetryClient = new TelemetryClient();
    private static final String ENV_HEARTBEAT_INSTANCE_ID = "HEARTBEAT_INSTANCE_ID";
    private static final String STATUS_UP = "UP";

    private final String heartbeatUrl;
    private final String serviceName;
    private final String instanceId;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong sequence = new AtomicLong(0);

    public HeartbeatService(String baseUrl, String serviceName) {
        this.heartbeatUrl = normalizeBaseUrl(baseUrl) + PathConfig.API_HEARTBEAT_ENDPOINT;
        this.serviceName = serviceName;
        this.instanceId = resolveInstanceId();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "heartbeat-" + serviceName);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void start() {
        Map<String, String> properties = new HashMap<>();
        properties.put("serviceName", serviceName);
        properties.put("instanceId", instanceId);
        properties.put("heartbeatUrl", heartbeatUrl);
        properties.put("interval", "30s");
        telemetryClient.trackTrace("Heartbeat start", SeverityLevel.Information, properties);
        
        scheduler.scheduleAtFixedRate(this::sendHeartbeatSafely, 0, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        Map<String, String> properties = new HashMap<>();
        properties.put("serviceName", serviceName);
        telemetryClient.trackTrace("Heartbeat stopping", SeverityLevel.Information, properties);
        scheduler.shutdownNow();
    }

    private void sendHeartbeatSafely() {
        long seq = sequence.incrementAndGet();
        long started = System.currentTimeMillis();
        try {
            sendHeartbeat();
            long took = System.currentTimeMillis() - started;
            
            Map<String, String> properties = new HashMap<>();
            properties.put("serviceName", serviceName);
            properties.put("instanceId", instanceId);
            properties.put("seq", String.valueOf(seq));
            properties.put("ms", String.valueOf(took));
            telemetryClient.trackTrace("heartbeat success", SeverityLevel.Information, properties);
            
        } catch (Throwable e) {
            long took = System.currentTimeMillis() - started;
            
            Map<String, String> properties = new HashMap<>();
            properties.put("serviceName", serviceName);
            properties.put("instanceId", instanceId);
            properties.put("seq", String.valueOf(seq));
            properties.put("ms", String.valueOf(took));
            properties.put("error", e.getMessage());
            
            telemetryClient.trackException(new Exception("Heartbeat failure: " + e.getMessage(), e), properties, null);
            System.err.println("Heartbeat failure for " + serviceName + " seq=" + seq + " after " + took + "ms: " + e.getMessage());
        }
    }

    private void sendHeartbeat() throws IOException {
        String jsonBody = "{"
                + "\"instanceId\":\"" + escapeJson(instanceId) + "\","
                + "\"serviceName\":\"" + escapeJson(serviceName) + "\","
                + "\"status\":\"" + STATUS_UP + "\""
                + "}";

        ApiClient.postInternalHeartbeat(heartbeatUrl, jsonBody);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String resolveInstanceId() {
        String explicit = System.getenv(ENV_HEARTBEAT_INSTANCE_ID);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        return UUID.randomUUID().toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}