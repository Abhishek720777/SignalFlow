package com.notifyengine.channel;

import com.notifyengine.queue.NotificationJob;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${brevo.api-key:}")
    private String brevoApiKey;

    @Value("${brevo.from-email:abhishekpoojar69@gmail.com}")
    private String brevoFromEmail;

    @Value("${brevo.from-name:SignalFlow}")
    private String brevoFromName;

    @Value("${resend.api-key:}")
    private String resendApiKey;

    @Value("${resend.from-email:onboarding@resend.dev}")
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

        // 1. Try Brevo HTTP REST API (Port 443 - HTTPS, Never blocked, works for ANY recipient)
        if (brevoApiKey != null && !brevoApiKey.trim().isEmpty()) {
            try {
                Map<String, Object> body = Map.of(
                        "sender", Map.of("name", brevoFromName, "email", brevoFromEmail),
                        "to", List.of(Map.of("email", job.getTo())),
                        "subject", job.getSubject() != null ? job.getSubject() : "Notification",
                        "htmlContent", job.getBody() != null ? job.getBody() : ""
                );

                org.springframework.http.ResponseEntity<String> response = restClient.post()
                        .uri("https://api.brevo.com/v3/smtp/email")
                        .header("api-key", brevoApiKey.trim())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Email sent successfully via Brevo to {}", job.getTo());
                    return "Success";
                } else {
                    log.warn("Brevo returned status {}: {}", response.getStatusCode(), response.getBody());
                }
            } catch (Exception e) {
                log.warn("Brevo delivery failed ({}), trying alternatives...", e.getMessage());
            }
        }

        // 2. Try Resend HTTP API if configured
        if (resendApiKey != null && !resendApiKey.trim().isEmpty()) {
            try {
                Map<String, Object> body = Map.of(
                        "from", resendFromEmail,
                        "to", List.of(job.getTo()),
                        "subject", job.getSubject() != null ? job.getSubject() : "Notification",
                        "html", job.getBody() != null ? job.getBody() : ""
                );

                org.springframework.http.ResponseEntity<String> response = restClient.post()
                        .uri("https://api.resend.com/emails")
                        .header("Authorization", "Bearer " + resendApiKey.trim())
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

        // 3. Fallback to standard SMTP (Gmail, etc.)
        return sendViaSmtp(job);
    }

    private String sendViaSmtp(NotificationJob job) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            if (mailUsername != null && !mailUsername.isBlank()) {
                helper.setFrom(mailUsername);
            }
            helper.setTo(job.getTo());
            helper.setSubject(job.getSubject() != null ? job.getSubject() : "Notification");
            helper.setText(job.getBody() != null ? job.getBody() : "", true);

            // Handle Attachment
            if (job.getAttachmentPath() != null) {
                java.io.File file = new java.io.File(job.getAttachmentPath());
                if (file.exists()) {
                    helper.addAttachment(job.getAttachmentName() != null ? job.getAttachmentName() : file.getName(), file);
                }
            }

            mailSender.send(message);
            log.info("Email sent successfully via SMTP to {}", job.getTo());
            return "Success";
        } catch (Exception e) {
            log.error("Failed to send email via SMTP to {}: {}", job.getTo(), e.getMessage());
            return e.getMessage() != null ? e.getMessage() : "Unknown SMTP Error";
        }
    }
}
