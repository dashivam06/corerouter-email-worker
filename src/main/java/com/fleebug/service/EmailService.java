package com.fleebug.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.fleebug.config.MailConfig;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

public class EmailService {

    private final static String VERIFICATION_EMAIL_TEMPLETE_FILENAME = "verify-email.html";

    private final static EmailClient emailClient = MailConfig.getEmailClient();
    private final TelemetryClient telemetryClient = new TelemetryClient();

    public void sendEmail(String from, List<String> to, String subject, String htmlBody, String textBody) {
        EmailMessage message = new EmailMessage()
                .setSenderAddress(from)
                .setToRecipients(to.stream().map(EmailAddress::new).toList())
                .setSubject(subject)
                .setBodyPlainText(textBody)
                .setBodyHtml(htmlBody);

        try {
            SyncPoller<EmailSendResult, EmailSendResult> poller = emailClient.beginSend(message);
            PollResponse<EmailSendResult> response = poller.waitForCompletion();
            EmailSendResult result = response.getValue();
            
            Map<String, String> properties = new HashMap<>();
            properties.put("recipients", to.toString());
            properties.put("messageId", result.getId());
            telemetryClient.trackTrace("Email successfully queued for delivery", SeverityLevel.Information, properties);
            
        } catch (Exception ex) {
            Map<String, String> properties = new HashMap<>();
            properties.put("recipients", to.toString());
            properties.put("error", ex.getMessage());
            telemetryClient.trackException(ex, properties, null);
        }
    }


    public String loadTemplate(String templateName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("templates/" + templateName)) {
            if (is == null) {
                throw new IOException("Template not found: " + templateName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    private String renderHtmlTemplateForUserVariables(String templeteFileName, Map<String, String> keyValuePair)
            throws IOException {

        String fileContent = loadTemplate(templeteFileName);

        for (Map.Entry<String, String> entry : keyValuePair.entrySet()) {
            fileContent = fileContent.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        return fileContent;
    }




    public void sendOtpEmail(String toEmail, String username, String otp, long otpTtlMinutes) throws IOException {

        String subject = "CoreRouter — Verification Code";

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "OTP_CODE", otp,
                "EXPIRY_TIME", String.valueOf(otpTtlMinutes));

        String emailBody = renderHtmlTemplateForUserVariables(VERIFICATION_EMAIL_TEMPLETE_FILENAME, values);
        
        String textBody = "Hi " + username + ",\n\n"
                + "Your OTP for email verification is: " + otp + "\n\n"
                + "This code was generated on "+ otpTtlMinutes+" and is valid for the next 5 minutes. Please do not share this code with anyone.\n\n"
                + "Best regards,\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me",List.of(toEmail), subject, emailBody, textBody);
    }

}
