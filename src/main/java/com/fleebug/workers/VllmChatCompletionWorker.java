package com.fleebug.workers;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fleebug.config.RedisConfig;
import com.fleebug.config.ChatTaskConfig;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

import com.fleebug.dto.task.model.BillingConfigDto;
import com.fleebug.dto.task.model.ModelDto;
import com.fleebug.dto.task.vllm.VllmChatCompletionResponse;
import com.fleebug.dto.task.vllm.VllmTaskDto;
import com.fleebug.service.HeartbeatService;
import com.fleebug.service.VllmChatCompletionService;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Redis stream consumer for vLLM chat completion tasks.
 */
public class VllmChatCompletionWorker {

    private static final TelemetryClient telemetryClient = new TelemetryClient();
    private static final JedisPool jedisPool = RedisConfig.getJedisPool();
    private static final VllmChatCompletionService service = new VllmChatCompletionService();
        private static final HeartbeatService heartbeatService =
            new HeartbeatService(ChatTaskConfig.API_BASE_URL, "vllm-chat-completion-worker");
    private static final ExecutorService executor =
            Executors.newFixedThreadPool(ChatTaskConfig.WORKER_THREADS);

    private static final AtomicInteger tasksProcessed = new AtomicInteger(0);


    public static void main(String[] args) {

        String cs = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");

		if (cs == null || cs.isBlank()) {
			System.err.println("Application Insights not configured: missing env var APPLICATIONINSIGHTS_CONNECTION_STRING. Continuing with local logging only.");
		} 
        
        ensureConsumerGroup();
        heartbeatService.start();

        Map<String, String> startupProps = new HashMap<>();
        startupProps.put("streamKey", ChatTaskConfig.STREAM_KEY);
        startupProps.put("consumerGroup", ChatTaskConfig.CONSUMER_GROUP);
        startupProps.put("threads", String.valueOf(ChatTaskConfig.WORKER_THREADS));
        telemetryClient.trackTrace("Listening for tasks", SeverityLevel.Information, startupProps);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            telemetryClient.trackTrace("Shutdown signal received — draining executor", SeverityLevel.Information, null);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            telemetryClient.trackTrace("Executor stopped", SeverityLevel.Information, null);
            heartbeatService.stop();
        }));

        while (!executor.isShutdown()) {
            List<StreamEntry> batch = null;

            // Phase 1: XREADGROUP — fetch up to STREAM_READ_COUNT entries
            try (Jedis jedis = jedisPool.getResource()) {
                Map<String, StreamEntryID> streams = Collections.singletonMap(
                        ChatTaskConfig.STREAM_KEY, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);

                List<Map.Entry<String, List<StreamEntry>>> result = jedis.xreadGroup(
                        ChatTaskConfig.CONSUMER_GROUP,
                        ChatTaskConfig.CONSUMER_NAME,
                        new XReadGroupParams()
                                .count(ChatTaskConfig.STREAM_READ_COUNT)
                                .block(ChatTaskConfig.STREAM_BLOCK_MS),
                        streams);

                if (result == null || result.isEmpty()) continue;

                for (Map.Entry<String, List<StreamEntry>> stream : result) {
                    List<StreamEntry> entries = stream.getValue();
                    if (entries != null && !entries.isEmpty()) {
                        batch = entries;
                    }
                }
            } catch (Exception e) {
                Map<String, String> errorProps = new HashMap<>();
                errorProps.put("error", e.getMessage());
                telemetryClient.trackException(e, errorProps, null);
                sleep(2000);
                continue;
            }

            if (batch == null || batch.isEmpty()) continue;

            // Phase 2: submit each entry to the thread pool
            for (StreamEntry entry : batch) {
                final String messageId = entry.getID().toString();
                final Map<String, String> fields = entry.getFields();

                executor.submit(() -> {
                    try {
                        // Process
                        boolean shouldAck = processTask(messageId, fields);

                        // Phase 3: XACK + XTRIM
                        if (shouldAck) {
                            try (Jedis jedis = jedisPool.getResource()) {
                                jedis.xack(ChatTaskConfig.STREAM_KEY,
                                        ChatTaskConfig.CONSUMER_GROUP,
                                        new StreamEntryID(messageId));
                                // Trim to latest 500 entries (~approximate, efficient)
                                jedis.xtrim(ChatTaskConfig.STREAM_KEY,
                                        ChatTaskConfig.STREAM_MAX_LEN, true);
                                
                                Map<String, String> ackProps = new HashMap<>();
                                ackProps.put("messageId", messageId);
                                telemetryClient.trackTrace("acked", SeverityLevel.Information, ackProps);

                            } catch (Exception e) {
                                Map<String, String> ackErrorProps = new HashMap<>();
                                ackErrorProps.put("messageId", messageId);
                                ackErrorProps.put("error", e.getMessage());
                                telemetryClient.trackException(e, ackErrorProps, null);
                            }
                        }
                    } catch (Throwable t) {
                        Map<String, String> fatalProps = new HashMap<>();
                        fatalProps.put("messageId", messageId);
                        fatalProps.put("error", t.getMessage());
                        telemetryClient.trackException(new Exception("FATAL error in worker loop", t), fatalProps, null);
                    }
                });
            }
        }
    }

    /** @return true if should ACK (handled or unrecoverable), false to let PEL redeliver */
    private static boolean processTask(String messageId, Map<String, String> fields) {
        String taskId = null;
        long vllmStartedAt = System.currentTimeMillis();

        try {
            // 1. Parse
            VllmTaskDto task = VllmTaskDto.fromStreamFields(fields);
            taskId = task.getTaskId();
            
            Map<String, String> startProps = new HashMap<>();
            startProps.put("taskId", taskId);
            startProps.put("messageId", messageId);
            startProps.put("stream", ChatTaskConfig.STREAM_KEY);
            telemetryClient.trackTrace("task start", SeverityLevel.Information, startProps);

            // 2. Resolve model
            ModelDto model = service.getModel(task.getModelId());
            
            Map<String, String> modelProps = new HashMap<>();
            modelProps.put("model", model.getFullname());
            modelProps.put("endpoint", model.getEndpointUrl());
            modelProps.put("provider", model.getProvider());
            telemetryClient.trackTrace("model resolved", SeverityLevel.Information, modelProps);

            // 3. Check model status
            if (!model.isActive()) {
                Map<String, String> inactiveProps = new HashMap<>();
                inactiveProps.put("taskId", taskId);
                inactiveProps.put("status", model.getStatus());
                telemetryClient.trackTrace("model inactive", SeverityLevel.Error, inactiveProps);
                
                service.updateTaskStatus(taskId, "FAILED",
                        errorResult("Model is not active (status: " + model.getStatus() + ")"));
                return true; // ACK — inactive model, no retry
            }

            // 4. Resolve billing config
            BillingConfigDto billingConfig = service.getBillingConfig(task.getModelId());
            if (!billingConfig.isPresent()) {
                Map<String, String> billingProps = new HashMap<>();
                billingProps.put("modelId", String.valueOf(task.getModelId()));
                billingProps.put("taskId", taskId);
                telemetryClient.trackTrace("billing missing", SeverityLevel.Warning, billingProps);
            }

            // 5. Mark PROCESSING
            service.updateTaskStatus(taskId, "PROCESSING", null);

            // 6. Call inference
            Map<String, String> inferenceProps = new HashMap<>();
            inferenceProps.put("taskId", taskId);
            inferenceProps.put("endpoint", model.getEndpointUrl());
            inferenceProps.put("model", model.getFullname());
            telemetryClient.trackTrace("inference calling", SeverityLevel.Information, inferenceProps);
            
            VllmChatCompletionResponse vllmResponse = service.callChatCompletion(
                    model.getEndpointUrl(),(model.getProvider()+"/"+ model.getFullname()), task.getPayload()); // vllm expects model name in format provider/model

            long vllmProcessingTimeMs = System.currentTimeMillis() - vllmStartedAt;

            // 7. Mark COMPLETED with usage metadata
            String usageMetadata = buildUsageMetadata(
                    vllmProcessingTimeMs,
                    vllmResponse.getCompletionTokens(),
                    vllmResponse.getModel());
            service.updateTaskStatus(taskId, "COMPLETED", vllmResponse, usageMetadata);

            // 8. Record billing
            if (billingConfig.isPresent()) {
                service.recordBillingUsage(taskId,
                        vllmResponse.getPromptTokens(),
                        vllmResponse.getCompletionTokens());
            }

            // 9. Stats
                int count = tasksProcessed.incrementAndGet();
                
                Map<String, String> doneProps = new HashMap<>();
                doneProps.put("taskId", taskId);
                doneProps.put("latencyMs", String.valueOf(vllmProcessingTimeMs));
                doneProps.put("tokens", String.valueOf(vllmResponse.getTotalTokens()));
                doneProps.put("count", String.valueOf(count));
                telemetryClient.trackTrace("task done", SeverityLevel.Information, doneProps);

            return true; // ACK

        } catch (IOException e) {
            Map<String, String> ioProps = new HashMap<>();
            ioProps.put("taskId", taskId != null ? taskId : "unknown");
            ioProps.put("messageId", messageId);
            ioProps.put("error", e.getMessage());
            telemetryClient.trackException(e, ioProps, null);

            if (taskId != null) {
                try {
                    service.updateTaskStatus(taskId, "FAILED", errorResult(e.getMessage()));
                    return true; // ACK — task marked FAILED
                } catch (IOException statusUpdateFailed) {
                    // API unreachable → do NOT ack → PEL redelivery
                    Map<String, String> retryProps = new HashMap<>();
                    retryProps.put("taskId", taskId);
                    retryProps.put("messageId", messageId);
                    telemetryClient.trackTrace("api unreachable; will redeliver", SeverityLevel.Error, retryProps);
                    return false;
                }
            }
            // No taskId means corrupt message → ACK to prevent infinite loop
            return true;

        } catch (Exception e) {
            Map<String, String> exProps = new HashMap<>();
            exProps.put("taskId", taskId != null ? taskId : "unknown");
            exProps.put("messageId", messageId);
            exProps.put("error", e.getMessage());
            telemetryClient.trackException(e, exProps, null);

            if (taskId != null) {
                try {
                    service.updateTaskStatus(taskId, "FAILED", errorResult(e.getMessage()));
                } catch (IOException ignored) {
                    return false; // API unreachable → no ACK
                }
            }
            return true;
        }
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message);
        err.put("timestamp", System.currentTimeMillis());
        return err;
    }

    private static String buildUsageMetadata(long latencyMs,int completionTokens, String modelName) {
        double tokensPerSecond = latencyMs > 0 ? (completionTokens * 1000.0) / latencyMs : 0;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("latencyMs", latencyMs);
        meta.put("tokensPerSecond", Math.round(tokensPerSecond * 10.0) / 10.0);
        meta.put("model", modelName);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            return null;
        }
    }

    private static void ensureConsumerGroup() {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.xgroupCreate(ChatTaskConfig.STREAM_KEY,
                        ChatTaskConfig.CONSUMER_GROUP,
                        new StreamEntryID("0-0"), true);
                
                Map<String, String> props = new HashMap<>();
                props.put("consumerGroup", ChatTaskConfig.CONSUMER_GROUP);
                telemetryClient.trackTrace("Consumer group created", SeverityLevel.Information, props);
                return;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    Map<String, String> props = new HashMap<>();
                    props.put("consumerGroup", ChatTaskConfig.CONSUMER_GROUP);
                    telemetryClient.trackTrace("Consumer group ready", SeverityLevel.Information, props);
                    return;
                }
                
                Map<String, String> props = new HashMap<>();
                props.put("attempt", String.valueOf(attempt));
                props.put("maxRetries", String.valueOf(maxRetries));
                props.put("error", e.getMessage());
                telemetryClient.trackTrace("Consumer group create failed", SeverityLevel.Warning, props);
                
                if (attempt < maxRetries) {
                    sleep(2000L * attempt);
                }
            }
        }
        throw new RuntimeException("Failed to create consumer group '" + ChatTaskConfig.CONSUMER_GROUP
                + "' on stream '" + ChatTaskConfig.STREAM_KEY + "' after " + maxRetries + " attempts. Aborting.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
