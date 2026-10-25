package com.notifyengine.channel;

import com.notifyengine.queue.NotificationJob;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailSender {

    private final JavaMailSender mailSender;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}")
    private String mailUsername;

    @org.springframework.beans.factory.annotation.Value("${resend.api-key:}")
    private String resendApiKey;

    @org.springframework.beans.factory.annotation.Value("${resend.from-email:onboarding@resend.dev}")
    private String resendFromEmail;

    private final org.springframework.web.client.RestClient restClient = org.springframework.web.client.RestClient.create();

    public String send(NotificationJob job) {
        // MOCK MODE for testing
        if ("test".equals(mailUsername)) {
            log.info("--- MOCK EMAIL START ---");
            log.info("To: {}, Subject: {}", job.getTo(), job.getSubject());
            log.info("Body: {}", job.getBody());
            if (job.getAttachmentPath() != null) log.info("Attachment: {}", job.getAttachmentName());
            log.info("--- MOCK EMAIL END ---");
            return "Success";
        }

        // RESEND HTTP MODE
        if (resendApiKey != null && !resendApiKey.isEmpty()) {
            try {
                java.util.Map<String, Object> body = java.util.Map.of(
                        "from", resendFromEmail,
                        "to", java.util.List.of(job.getTo()),
                        "subject", job.getSubject(),
                        "html", job.getBody()
                );

                org.springframework.http.ResponseEntity<String> response = restClient.post()
                        .uri("https://api.resend.com/emails")
                        .header("Authorization", "Bearer " + resendApiKey)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Email sent successfully via Resend to {}", job.getTo());
                    return "Success";
                } else {
                    log.warn("Resend rejected email ({}): {}. Falling back to standard SMTP...", response.getStatusCode(), response.getBody());
                }
            } catch (Exception e) {
                log.warn("Resend request failed: {}. Falling back to standard SMTP...", e.getMessage());
            }
        }

        // FALLBACK TO SMTP MODE
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setTo(job.getTo());
            helper.setSubject(job.getSubject());
            helper.setText(job.getBody(), true); 

            // Handle Attachment
            if (job.getAttachmentPath() != null) {
                java.io.File file = new java.io.File(job.getAttachmentPath());
                if (file.exists()) {
                    helper.addAttachment(job.getAttachmentName() != null ? job.getAttachmentName() : file.getName(), file);
                }
            }
            
            mailSender.send(message);
            log.info("Email sent successfully to {}", job.getTo());
            return "Success";
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", job.getTo(), e.getMessage());
            return e.getMessage() != null ? e.getMessage() : "Unknown SMTP Error";
        }
    }
}
