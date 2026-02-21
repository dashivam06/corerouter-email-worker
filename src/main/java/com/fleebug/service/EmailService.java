package com.fleebug.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.fleebug.config.MailConfig;

public class EmailService {

    public static final String EMAIL_TEMPLATE_FOLDER = "src/main/resources/templates/";
    private final static String VERIFICATION_EMAIL_TEMPLETE_FILENAME = "verify-email.html";

    private final static EmailClient emailClient = MailConfig.getEmailClient();

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
            System.out.println(
                    "Email successfully queued for delivery. Recipients: " + to + ", Message ID: " + result.getId());
        } catch (Exception ex) {
            System.err.println("Failed to queue email: " + ex.getMessage());
        }
    }




    private String renderHtmlTemplateForUserVariables(String templeteFileName, Map<String, String> keyValuePair)
            throws IOException {
        Path path = Paths.get(EMAIL_TEMPLATE_FOLDER + templeteFileName);

        String fileContent = Files.readString(path);

        for (Map.Entry<String, String> entry : keyValuePair.entrySet()) {
            fileContent = fileContent.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        return fileContent;
    }




    public void sendOtpEmail(String toEmail, String username, String otp, String expiresIn) throws IOException {

        String subject = "CoreRouter — Verification Code";

        Map<String, String> values = Map.of(
                "USERNAME", username,
                "OTP_CODE", otp,
                "EXPIRY_TIME", expiresIn);

        String emailBody = renderHtmlTemplateForUserVariables(VERIFICATION_EMAIL_TEMPLETE_FILENAME, values);
        
        String textBody = "Hi " + username + ",\n\n"
                + "Your OTP for email verification is: " + otp + "\n\n"
                + "This code was generated on "+ expiresIn+" and is valid for the next 5 minutes. Please do not share this code with anyone.\n\n"
                + "Best regards,\n"
                + "CoreRouter Team";

        sendEmail("noreply@corerouter.me",List.of(toEmail), subject, emailBody, textBody);
    }

}
