package com.fleebug.workers;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

import com.fleebug.config.RedisConfig;
import com.fleebug.constants.ChatTaskConfig;
import com.fleebug.constants.PathConfig;
import com.fleebug.dto.email.EmailJobDto;
import com.fleebug.service.EmailService;
import com.fleebug.service.HeartbeatService;
import com.fleebug.utility.Env;
import com.fleebug.utility.MessageEncryption;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class OtpEmailWorker {

    private static final TelemetryClient telemetryClient = new TelemetryClient();

    private static final JedisPool jedisPool = RedisConfig.getJedisPool();
    private static final EmailService emailService = new EmailService();
    private static final HeartbeatService heartbeatService =
        new HeartbeatService(PathConfig.API_HEARTBEAT_BASE_URL, "otp-email-worker");

    public static void main(String[] args) throws IOException {

        String cs = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");

		if (cs == null || cs.isBlank()) {
			System.err.println("Application Insights not configured: missing env var APPLICATIONINSIGHTS_CONNECTION_STRING. Continuing with local logging only.");
		} 

        String workerSecret = Env.get(ChatTaskConfig.ENV_WORKER_SECRET);
        System.out.println("OTP worker heartbeat base URL: " + PathConfig.API_HEARTBEAT_BASE_URL);
        System.out.println("OTP worker heartbeat endpoint: " + PathConfig.API_HEARTBEAT_ENDPOINT);
        System.out.println("OTP worker WORKER_SECRET present: " + (workerSecret != null && !workerSecret.isBlank()));

        heartbeatService.start();

        Runtime.getRuntime().addShutdownHook(new Thread(heartbeatService::stop));

        try (Jedis jedis = jedisPool.getResource()) {

            while (true) {

                List<String> job = jedis.brpop(3000, "queue:email");

                if (job == null) {
                    continue;
                }

                String value = job.get(1);

                try {

                    String decryptedValue = MessageEncryption.decrypt(value);
                    EmailJobDto emailJob = EmailJobDto.fromJson(decryptedValue);

                    try {
                        String username = emailJob.getEmail().split("@")[0];
                        emailService.sendOtpEmail(emailJob.getEmail(), username, emailJob.getOtp(), emailJob.getOtpTtlMinutes());
                        
                        Map<String, String> properties = new HashMap<>();
                        properties.put("email", emailJob.getEmail());
                        properties.put("ttlMin", String.valueOf(emailJob.getOtpTtlMinutes()));
                        telemetryClient.trackTrace("otp sent", SeverityLevel.Information, properties);
                        
                    } catch (Exception e) {
                        Map<String, String> properties = new HashMap<>();
                        properties.put("email", emailJob.getEmail());
                        properties.put("error", e.getMessage());
                        telemetryClient.trackException(e, properties, null);
                    }

                } catch (IOException e) {
                     Map<String, String> properties = new HashMap<>();
                     properties.put("valueLength", String.valueOf(value != null ? value.length() : 0));
                     properties.put("error", e.getMessage());
                     telemetryClient.trackException(e, properties, null);
                }

            }

        } finally {
            heartbeatService.stop();
        }
    }

}