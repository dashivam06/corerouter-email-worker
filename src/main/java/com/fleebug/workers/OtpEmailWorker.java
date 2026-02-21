package com.fleebug.workers;

import java.io.IOException;
import java.util.List;
import com.fleebug.config.RedisConfig;
import com.fleebug.dto.EmailJobDto;
import com.fleebug.service.EmailService;
import com.fleebug.utility.MessageEncryption;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class OtpEmailWorker {

    private static final JedisPool jedisPool = RedisConfig.getJedisPool();
    private static final EmailService emailService = new EmailService();

    public static void main(String[] args) throws IOException {

        try (Jedis jedis = jedisPool.getResource()) {

            while (true) {

                List<String> job = jedis.brpop(3000, "queue:email");

                if (job == null) {
                    // No job available, loop again
                    continue;
                }

                String value = job.get(1);

                try {

                    String decryptedValue = MessageEncryption.decrypt(value);
                    EmailJobDto emailJob = EmailJobDto.fromJson(decryptedValue);

                    try {

                    String username = emailJob.getEmail().split("@")[0]; // Extract username from email

                    emailService.sendOtpEmail(emailJob.getEmail(), username , emailJob.getOtp(),emailJob.getOtpTtlMinutes());
              
                    System.out.println("Sent OTP to " + emailJob.getEmail());
                } catch (Exception e) {
                    System.err.println("Failed to send OTP to " + emailJob.getEmail());
                    e.printStackTrace();
                }


                } catch (IOException e) {
                    System.err.println("Invalid email job JSON: " + value);
                    e.printStackTrace();
                    continue; 
                }

            }

        }
    }

}