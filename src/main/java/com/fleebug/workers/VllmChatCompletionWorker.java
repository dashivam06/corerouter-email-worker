package com.fleebug.workers;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fleebug.config.RedisConfig;
import com.fleebug.config.ChatTaskConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fleebug.dto.task.model.BillingConfigDto;
import com.fleebug.dto.task.model.ModelDto;
import com.fleebug.dto.task.vllm.VllmChatCompletionResponse;
import com.fleebug.dto.task.vllm.VllmTaskDto;
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

    private static final Logger log = LoggerFactory.getLogger(VllmChatCompletionWorker.class);
    private static final JedisPool jedisPool = RedisConfig.getJedisPool();
    private static final VllmChatCompletionService service = new VllmChatCompletionService();
    private static final ExecutorService executor =
            Executors.newFixedThreadPool(ChatTaskConfig.WORKER_THREADS);

    private static final AtomicInteger tasksProcessed = new AtomicInteger(0);


    public static void main(String[] args) {
        printBanner();
        ensureConsumerGroup();
        log.info("Listening for tasks on {} (threads={}) ...",
                ChatTaskConfig.STREAM_KEY, ChatTaskConfig.WORKER_THREADS);
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — draining executor ...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Executor stopped.");
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
                log.error("Stream read failed — {}", e.getMessage());
                sleep(2000);
                continue;
            }

            if (batch == null || batch.isEmpty()) continue;

            // Phase 2: submit each entry to the thread pool
            for (StreamEntry entry : batch) {
                final String messageId = entry.getID().toString();
                final Map<String, String> fields = entry.getFields();

                executor.submit(() -> {
                    // Process
                    boolean shouldAck = processTask(messageId, fields);

                    // Phase 3: XACK
                    if (shouldAck) {
                        try (Jedis jedis = jedisPool.getResource()) {
                            jedis.xack(ChatTaskConfig.STREAM_KEY,
                                    ChatTaskConfig.CONSUMER_GROUP,
                                    new StreamEntryID(messageId));
                            log.info("acked  {}", messageId);
                        } catch (Exception e) {
                            log.error("ack failed  {} — {}", messageId, e.getMessage());
                        }
                    }
                });
            }
        }
    }

    /** @return true if should ACK (handled or unrecoverable), false to let PEL redeliver */
    private static boolean processTask(String messageId, Map<String, String> fields) {
        String taskId = null;
        long startTime = System.currentTimeMillis();

        try {
            // 1. Parse
            VllmTaskDto task = VllmTaskDto.fromStreamFields(fields);
            taskId = task.getTaskId();
            System.out.println();
            log.info("┌── task  {}  ({})", taskId, messageId);

            // 2. Resolve model
            ModelDto model = service.getModel(task.getModelId());
            log.info("│   model      {} @ {}", model.getFullname(), model.getEndpointUrl());

            // 3. Check model status
            if (!model.isActive()) {
                log.error("│   model      not active (status: {})", model.getStatus());
                service.updateTaskStatus(taskId, "FAILED",
                        errorResult("Model is not active (status: " + model.getStatus() + ")"));
                return true; // ACK — inactive model, no retry
            }

            // 4. Resolve billing config
            BillingConfigDto billingConfig = service.getBillingConfig(task.getModelId());
            if (!billingConfig.isPresent()) {
                log.warn("│   billing    no config for model {} — skipping", task.getModelId());
            }

            // 5. Mark PROCESSING
            service.updateTaskStatus(taskId, "PROCESSING", null);

            // 6. Call inference
            log.info("│   inference  POST {}/v1/completions", model.getEndpointUrl());
            VllmChatCompletionResponse vllmResponse = service.callChatCompletion(
                    model.getEndpointUrl(),(model.getProvider()+"/"+ model.getFullname()), task.getPayload()); // vllm expects model name in format provider/model

            long processingTimeMs = System.currentTimeMillis() - startTime;

            // 7. Mark COMPLETED
            service.updateTaskStatus(taskId, "COMPLETED", vllmResponse);

            // 8. Record billing
            if (billingConfig.isPresent()) {
                service.recordBillingUsage(taskId,
                        vllmResponse.getPromptTokens(),
                        vllmResponse.getCompletionTokens());
            }

            // 9. Stats
            log.info("└── done   {}ms  tokens={}  [#{}]", processingTimeMs, vllmResponse.getTotalTokens(), tasksProcessed.incrementAndGet());

            return true; // ACK

        } catch (IOException e) {
            log.error("└── FAILED  {} — {}", (taskId != null ? taskId : "unknown"), e.getMessage());

            if (taskId != null) {
                try {
                    service.updateTaskStatus(taskId, "FAILED", errorResult(e.getMessage()));
                    return true; // ACK — task marked FAILED
                } catch (IOException statusUpdateFailed) {
                    // API unreachable → do NOT ack → PEL redelivery
                    log.error("API unreachable — message NOT ack'd, will be redelivered");
                    return false;
                }
            }
            // No taskId means corrupt message → ACK to prevent infinite loop
            return true;

        } catch (Exception e) {
            log.error("└── UNEXPECTED  {}", e.getMessage());
            e.printStackTrace();

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

    private static void ensureConsumerGroup() {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.xgroupCreate(ChatTaskConfig.STREAM_KEY,
                        ChatTaskConfig.CONSUMER_GROUP,
                        new StreamEntryID("0-0"), true);
                log.info("Consumer group '{}' created", ChatTaskConfig.CONSUMER_GROUP);
                return;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.info("Consumer group '{}' ready", ChatTaskConfig.CONSUMER_GROUP);
                    return;
                }
                log.warn("Consumer group create failed (attempt {}/{}) — {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    sleep(2000L * attempt);
                }
            }
        }
        throw new RuntimeException("Failed to create consumer group '" + ChatTaskConfig.CONSUMER_GROUP
                + "' on stream '" + ChatTaskConfig.STREAM_KEY + "' after " + maxRetries + " attempts. Aborting.");
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         vLLM Chat Completion Worker          ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("  stream    " + ChatTaskConfig.STREAM_KEY);
        System.out.println("  group     " + ChatTaskConfig.CONSUMER_GROUP);
        System.out.println("  consumer  " + ChatTaskConfig.CONSUMER_NAME);
        System.out.println("  api       " + ChatTaskConfig.API_BASE_URL);
        System.out.println();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
