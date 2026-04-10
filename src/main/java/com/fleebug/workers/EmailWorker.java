package com.fleebug.workers;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

import com.fleebug.config.RedisConfig;
import com.fleebug.constants.PathConfig;
import com.fleebug.dto.email.EmailJobDto;
import com.fleebug.service.EmailService;
import com.fleebug.service.HeartbeatService;
import com.fleebug.utility.MessageEncryption;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class EmailWorker {

    private static final String QUEUE_NAME = "queue:email";
    private static final String DLQ_NAME = "queue:email:dlq";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long BASE_BACKOFF_MILLIS = 500L;

    private static final TelemetryClient telemetryClient = new TelemetryClient();

    private static final JedisPool jedisPool = RedisConfig.getJedisPool();
    private static final EmailService emailService = new EmailService();
    private static final HeartbeatService heartbeatService =
        new HeartbeatService(PathConfig.API_HEARTBEAT_BASE_URL, "email-worker");

    public static void main(String[] args) throws IOException {

        String cs = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");

		if (cs == null || cs.isBlank()) {
			System.err.println("Application Insights not configured: missing env var APPLICATIONINSIGHTS_CONNECTION_STRING. Continuing with local logging only.");
		} 

        heartbeatService.start();

        Runtime.getRuntime().addShutdownHook(new Thread(heartbeatService::stop));

        try (Jedis jedis = jedisPool.getResource()) {

            while (true) {

                List<String> job = jedis.brpop(3000, QUEUE_NAME);

                if (job == null) {
                    continue;
                }

                String encryptedValue = job.get(1);
                String decryptedValue = null;
                EmailJobDto emailJob = null;

                try {
                    decryptedValue = MessageEncryption.decrypt(encryptedValue);
                    emailJob = EmailJobDto.fromJson(decryptedValue);

                    validateEnvelope(emailJob);
                    emailService.sendByTemplate(emailJob);

                    Map<String, String> properties = new HashMap<>();
                    properties.put("templateKey", safe(emailJob.getTemplateKey()));
                    properties.put("category", safe(emailJob.getCategory()));
                    properties.put("email", safe(emailJob.getEmail()));
                    properties.put("timestamp", String.valueOf(emailJob.getTimestamp()));
                    telemetryClient.trackTrace("Email sent", SeverityLevel.Information, properties);
                } catch (Exception e) {
                    handleFailure(jedis, emailJob, decryptedValue, e);
                }

            }

        } finally {
            heartbeatService.stop();
        }
    }

    private static void validateEnvelope(EmailJobDto emailJob) {
        require(emailJob.getSchemaVersion(), "schemaVersion");
        require(emailJob.getChannel(), "channel");
        require(emailJob.getCategory(), "category");
        require(emailJob.getTemplateKey(), "templateKey");
        require(emailJob.getEmail(), "email");

        if (!"EMAIL".equals(safeUpper(emailJob.getChannel()))) {
            throw new IllegalArgumentException("Unsupported channel: " + emailJob.getChannel());
        }
    }

    private static void handleFailure(Jedis jedis, EmailJobDto emailJob, String decryptedPayload, Exception e)
            throws IOException {
        int currentRetries = emailJob != null && emailJob.getRetryCount() != null ? emailJob.getRetryCount() : 0;
        int nextRetries = currentRetries + 1;

        Map<String, String> properties = new HashMap<>();
        properties.put("templateKey", emailJob == null ? "" : safe(emailJob.getTemplateKey()));
        properties.put("category", emailJob == null ? "" : safe(emailJob.getCategory()));
        properties.put("email", emailJob == null ? "" : safe(emailJob.getEmail()));
        properties.put("retryCount", String.valueOf(nextRetries));

        if (emailJob != null && nextRetries <= MAX_RETRY_COUNT) {
            emailJob.setRetryCount(nextRetries);
            long backoffMillis = Math.min(BASE_BACKOFF_MILLIS * (1L << (nextRetries - 1)), 5000L);
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }

            jedis.lpush(QUEUE_NAME, MessageEncryption.encrypt(emailJob.toJson()));
            properties.put("backoffMs", String.valueOf(backoffMillis));
            telemetryClient.trackTrace("Email send failed; message requeued", SeverityLevel.Warning, properties);
            return;
        }

        String payloadForDlq;
        if (emailJob != null) {
            emailJob.setRetryCount(nextRetries);
            payloadForDlq = emailJob.toJson();
        } else if (decryptedPayload != null && !decryptedPayload.isBlank()) {
            payloadForDlq = decryptedPayload;
        } else {
            payloadForDlq = "{\"error\":\"invalid payload\",\"timestamp\":" + System.currentTimeMillis() + "}";
        }

        jedis.lpush(DLQ_NAME, MessageEncryption.encrypt(payloadForDlq));
        properties.put("dlq", DLQ_NAME);
        telemetryClient.trackException(e, properties, null);
    }

    private static void require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeUpper(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

}