package com.fleebug.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.fleebug.config.MailConfig;
import com.fleebug.dto.email.EmailJobDto;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;

public class EmailService {

    private final static String OTP_EMAIL_TEMPLATE_FILENAME = "verify-email.html";
    private final static String PASSWORD_RESET_OTP_EMAIL_TEMPLATE_FILENAME = "forgot-password.html";
    private final static String PASSWORD_CHANGED_TEMPLATE_FILENAME = "password-changed-notification.html";
    private final static String USER_DELETED_TEMPLATE_FILENAME = "user-deleted-notification.html";
    private final static String API_KEY_USAGE_TEMPLATE_FILENAME = "api-key-monthly-usage-alert.html";

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

    public void sendByTemplate(EmailJobDto emailJob) throws IOException {
        String templateKey = safeUpper(emailJob.getTemplateKey());
        String recipient = emailJob.getEmail();

        switch (templateKey) {
            case "REGISTRATION_OTP" -> sendRegistrationOtpEmail(emailJob, recipient);
            case "PASSWORD_RESET_OTP" -> sendPasswordResetOtpEmail(emailJob, recipient);
            case "PASSWORD_CHANGED_NOTIFICATION" -> sendPasswordChangedNotification(emailJob, recipient);
            case "USER_DELETED_NOTIFICATION" -> sendUserDeletedNotification(emailJob, recipient);
            case "API_KEY_MONTHLY_USAGE_ALERT" -> sendApiKeyMonthlyUsageAlert(emailJob, recipient);
            default -> throw new IllegalArgumentException("Unsupported templateKey: " + emailJob.getTemplateKey());
        }
    }

    private void sendRegistrationOtpEmail(EmailJobDto emailJob, String toEmail) throws IOException {
        String username = resolveDisplayName(emailJob);
        long ttlMinutes = emailJob.getOtpTtlMinutes() == null ? 5L : emailJob.getOtpTtlMinutes();
        String subject = isBlank(emailJob.getSubject()) ? "CoreRouter — One-Time Passcode" : emailJob.getSubject();

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "OTP_CODE", safe(emailJob.getOtp()),
                "EXPIRY_TIME", String.valueOf(ttlMinutes),
                "ACTION_TEXT", "complete your account action",
                "PURPOSE_LABEL", "account verification");

        String emailBody = renderHtmlTemplateForUserVariables(OTP_EMAIL_TEMPLATE_FILENAME, values);
        String textBody = "Hi " + username + ",\n\n"
                + "Use this one-time passcode to complete your account action: " + safe(emailJob.getOtp()) + "\n\n"
                + "This code is valid for the next " + ttlMinutes + " minutes. Please do not share it with anyone.\n\n"
                + "Best regards,\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me", List.of(toEmail), subject, emailBody, textBody);
    }

    private void sendPasswordResetOtpEmail(EmailJobDto emailJob, String toEmail) throws IOException {
        String username = resolveDisplayName(emailJob);
        long ttlMinutes = emailJob.getOtpTtlMinutes() == null ? 5L : emailJob.getOtpTtlMinutes();
        String subject = isBlank(emailJob.getSubject()) ? "CoreRouter — One-Time Passcode" : emailJob.getSubject();

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "OTP_CODE", safe(emailJob.getOtp()),
                "EXPIRY_TIME", String.valueOf(ttlMinutes),
                "ACTION_TEXT", "reset your password",
                "PURPOSE_LABEL", "password reset");

        String emailBody = renderHtmlTemplateForUserVariables(PASSWORD_RESET_OTP_EMAIL_TEMPLATE_FILENAME, values);
        String textBody = "Hi " + username + ",\n\n"
                + "Use this one-time passcode to reset your password: " + safe(emailJob.getOtp()) + "\n\n"
                + "This code is valid for the next " + ttlMinutes + " minutes. Please do not share it with anyone.\n\n"
                + "Best regards,\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me", List.of(toEmail), subject, emailBody, textBody);
    }

    private void sendPasswordChangedNotification(EmailJobDto emailJob, String toEmail) throws IOException {
        String username = resolveDisplayName(emailJob);
        String subject = isBlank(emailJob.getSubject())
                ? "CoreRouter — Password Change Notice"
                : emailJob.getSubject();

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "EVENT_TIME", safe(emailJob.getEventTime()),
                "MESSAGE", defaultMessage(emailJob.getMessage(), "Your account password was changed successfully."));

        String emailBody = renderHtmlTemplateForUserVariables(PASSWORD_CHANGED_TEMPLATE_FILENAME, values);
        String textBody = "Hi " + username + ",\n\n"
                + defaultMessage(emailJob.getMessage(), "Your account password was changed successfully.") + "\n"
                + "Time: " + safe(emailJob.getEventTime()) + "\n\n"
                + "If this wasn't you, contact support immediately.\n\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me", List.of(toEmail), subject, emailBody, textBody);
    }

    private void sendUserDeletedNotification(EmailJobDto emailJob, String toEmail) throws IOException {
        String username = resolveDisplayName(emailJob);
        String subject = isBlank(emailJob.getSubject())
                ? "CoreRouter — Account Deletion Notice"
                : emailJob.getSubject();

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "EVENT_TIME", safe(emailJob.getEventTime()),
                "MESSAGE", defaultMessage(emailJob.getMessage(), "Your account has been deleted from CoreRouter."));

        String emailBody = renderHtmlTemplateForUserVariables(USER_DELETED_TEMPLATE_FILENAME, values);
        String textBody = "Hi " + username + ",\n\n"
                + defaultMessage(emailJob.getMessage(), "Your account has been deleted from CoreRouter.") + "\n"
                + "Time: " + safe(emailJob.getEventTime()) + "\n\n"
                + "If this was not requested by you, contact support immediately.\n\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me", List.of(toEmail), subject, emailBody, textBody);
    }

    private void sendApiKeyMonthlyUsageAlert(EmailJobDto emailJob, String toEmail) throws IOException {
        String username = resolveDisplayName(emailJob);
        String subject = isBlank(emailJob.getSubject())
                ? "CoreRouter — API Key Usage Alert"
                : emailJob.getSubject();

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "API_KEY_NAME", safe(emailJob.getApiKeyName()),
                "THRESHOLD_PERCENT", valueOrDefault(emailJob.getThresholdPercent(), 0),
                "CONSUMED", valueOrDefault(emailJob.getConsumed(), 0L),
                "MONTHLY_LIMIT", valueOrDefault(emailJob.getMonthlyLimit(), 0L),
                "EVENT_TIME", safe(emailJob.getEventTime()),
                "MESSAGE", defaultMessage(emailJob.getMessage(), "Your API key usage has reached the configured monthly threshold."));

        String emailBody = renderHtmlTemplateForUserVariables(API_KEY_USAGE_TEMPLATE_FILENAME, values);
        String textBody = "Hi " + username + ",\n\n"
                + "API Key: " + safe(emailJob.getApiKeyName()) + "\n"
                + "Usage: " + valueOrDefault(emailJob.getConsumed(), 0L) + " / " + valueOrDefault(emailJob.getMonthlyLimit(), 0L) + "\n"
                + "Threshold: " + valueOrDefault(emailJob.getThresholdPercent(), 0) + "%\n"
                + "Time: " + safe(emailJob.getEventTime()) + "\n\n"
                + defaultMessage(emailJob.getMessage(), "Your API key usage has reached the configured monthly threshold.") + "\n\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me", List.of(toEmail), subject, emailBody, textBody);
    }

    private String resolveDisplayName(EmailJobDto emailJob) {
        if (!isBlank(emailJob.getFullName())) {
            return emailJob.getFullName();
        }
        String email = safe(emailJob.getEmail());
        if (email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return email;
    }

    private String safeUpper(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String defaultMessage(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String valueOrDefault(Number value, Number fallback) {
        return String.valueOf(value == null ? fallback : value);
    }




    public void sendOtpEmail(String toEmail, String username, String otp, long otpTtlMinutes) throws IOException {
        sendOtpEmail(toEmail, username, otp, otpTtlMinutes, null);
    }


    public void sendOtpEmail(String toEmail, String username, String otp, long otpTtlMinutes, String purpose)
            throws IOException {

        String resolvedPurpose = purpose == null ? "REGISTRATION" : purpose.trim().toUpperCase();
        String templateName = switch (resolvedPurpose) {
            case "PASSWORD_RESET" -> PASSWORD_RESET_OTP_EMAIL_TEMPLATE_FILENAME;
            default -> OTP_EMAIL_TEMPLATE_FILENAME;
        };

        String subject = "CoreRouter — One-Time Passcode";

        String actionText = switch (resolvedPurpose) {
            case "PASSWORD_RESET" -> "reset your password";
            default -> "complete your account action";
        };

        String purposeLabel = switch (resolvedPurpose) {
            case "PASSWORD_RESET" -> "password reset";
            default -> "account verification";
        };

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "OTP_CODE", otp,
                "EXPIRY_TIME", String.valueOf(otpTtlMinutes),
                "ACTION_TEXT", actionText,
                "PURPOSE_LABEL", purposeLabel);

        String emailBody = renderHtmlTemplateForUserVariables(templateName, values);

        String textBody = "Hi " + username + ",\n\n"
                + "Use this one-time passcode to " + actionText + ": " + otp + "\n\n"
                + "This code is valid for the next " + otpTtlMinutes + " minutes. Please do not share it with anyone.\n\n"
                + "Best regards,\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me", List.of(toEmail), subject, emailBody, textBody);
    }

}
