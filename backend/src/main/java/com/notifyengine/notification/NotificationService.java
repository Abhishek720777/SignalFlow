package com.notifyengine.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifyengine.client.Client;
import com.notifyengine.queue.NotificationJob;
import com.notifyengine.queue.RedisQueueService;
import com.notifyengine.ratelimit.RateLimiter;
import com.notifyengine.template.Template;
import com.notifyengine.template.TemplateRepository;
import com.notifyengine.user.NotificationUser;
import com.notifyengine.user.UserPreference;
import com.notifyengine.user.UserRepository;
import com.notifyengine.template.TemplateCacheService;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRequestRepository requestRepository;
    private final NotificationLogRepository logRepository;
    private final TemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final RedisQueueService queueService;
    private final RateLimiter rateLimiter;
    private final TemplateCacheService templateCacheService;
    private final ObjectMapper objectMapper;

    public void sendTestNotification(String channel, String to, String subject, String body, Map<String, Object> mockData) {
        String resolvedBody = resolveTemplate(body, mockData != null ? mockData : Map.of());
        String resolvedSubject = subject != null ? resolveTemplate(subject, mockData != null ? mockData : Map.of()) : "[TEST] Notification";

        // Create a lightweight test log entry so workers can track the delivery
        NotificationLog testLog = new NotificationLog();
        testLog.setChannel(channel);
        testLog.setStatus("TEST");
        testLog = logRepository.save(testLog);

        NotificationJob job = NotificationJob.builder()
                .logId(testLog.getId())
                .channel(channel.toUpperCase())
                .to(to)
                .subject(resolvedSubject)
                .body(resolvedBody)
                .attemptCount(0)
                .priority("HIGH")
                .build();

        queueService.pushJob(job);
        log.info("Test notification queued for channel={} to={}", channel, to);
    }

    @Transactional
    public void processNotificationRequest(Client client, Map<String, Object> payload) {
        if (!rateLimiter.isAllowed(client.getApiKey())) {
            throw new RuntimeException("Rate limit exceeded");
        }

        String externalUserId = (String) payload.get("userId");
        String eventName = (String) payload.get("event");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        String priority = payload.containsKey("priority") ? (String) payload.get("priority") : "LOW";

        NotificationRequest request = new NotificationRequest();
        request.setClient(client);
        request.setEventName(eventName);
        request.setStatus("PROCESSING");
        try {
            request.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            request.setPayload("{}");
        }
        
        // Find user - if not found, auto-provision user from payload data or externalUserId
        NotificationUser user = userRepository.findByClientIdAndExternalUserId(client.getId(), externalUserId).orElse(null);
        if (user == null) {
            String recipientEmail = (data != null && data.containsKey("email")) ? (String) data.get("email") : null;
            if (recipientEmail == null && externalUserId != null && externalUserId.contains("@")) {
                recipientEmail = externalUserId;
            }
            log.info("Auto-provisioning user for externalUserId: {} with email: {}", externalUserId, recipientEmail);
            user = NotificationUser.builder()
                    .client(client)
                    .externalUserId(externalUserId != null ? externalUserId : "anonymous")
                    .email(recipientEmail)
                    .build();
            user = userRepository.save(user);
        }
        
        request.setUser(user);
        request = requestRepository.save(request);

        try {
            List<Template> templates = templateCacheService.getActiveTemplates(client.getId(), eventName);

            if (templates.isEmpty()) {
                request.setStatus("FAILED");
                requestRepository.save(request);
                log.warn("No active templates found for event {}", eventName);
                return;
            }

            int queuedCount = 0;
            // Inject Brand Assets
            data.put("brandLogo", client.getLogoUrl() != null ? client.getLogoUrl() : "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png");
            data.put("brandColor", client.getPrimaryColor() != null ? client.getPrimaryColor() : "#6366f1");
            data.put("clientName", client.getName());

            for (Template template : templates) {
                if (shouldSend(user, template.getChannel(), priority)) {
                    // Refresh data map with Template-specific branding if available
                    data.put("brandLogo", template.getLogoUrl() != null ? template.getLogoUrl() : data.get("brandLogo"));
                    data.put("brandColor", template.getPrimaryColor() != null ? template.getPrimaryColor() : data.get("brandColor"));

                    String resolvedBody = resolveTemplate(template.getBody(), data);
                    
                    NotificationLog notificationLog = new NotificationLog();
                    notificationLog.setRequest(request);
                    notificationLog.setChannel(template.getChannel());
                    notificationLog.setStatus("PENDING");
                    notificationLog = logRepository.save(notificationLog);

                    NotificationJob job = NotificationJob.builder()
                            .logId(notificationLog.getId())
                            .channel(template.getChannel())
                            .to(getDestination(user, template.getChannel()))
                            .subject(template.getSubject() != null ? resolveTemplate(template.getSubject(), data) : null)
                            .body(resolvedBody)
                            .attemptCount(0)
                            .priority(priority)
                            .build();

                    queueService.pushJob(job);
                    queuedCount++;
                }
            }

            if (queuedCount > 0) {
                request.setStatus("QUEUED");
            } else if (templates.isEmpty()) {
                request.setStatus("FAILED");
                log.warn("No active templates found for event {}", eventName);
            } else {
                // If we found templates but queued 0, it means user suppressed via preferences
                request.setStatus("SUPPRESSED");
            }
            requestRepository.save(request);
        } catch (Exception e) {
            log.error("Error processing notification request", e);
            request.setStatus("ERROR");
            requestRepository.save(request);
        }
    }

    private boolean shouldSend(NotificationUser user, String channel, String priority) {
        UserPreference pref = user.getPreferences().stream()
                .filter(p -> p.getChannel().equalsIgnoreCase(channel))
                .findFirst()
                .orElse(null);

        if (pref != null && !pref.isEnabled()) {
            return false;
        }

        // Check quiet hours if priority is LOW
        if ("LOW".equalsIgnoreCase(priority) && pref != null && pref.getQuietHoursStart() != null && pref.getQuietHoursEnd() != null) {
            LocalTime now = LocalTime.now();
            if (isWithinQuietHours(now, pref.getQuietHoursStart(), pref.getQuietHoursEnd())) {
                return false;
            }
        }
        return true;
    }

    private boolean isWithinQuietHours(LocalTime time, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return time.isAfter(start) && time.isBefore(end);
        } else {
            return time.isAfter(start) || time.isBefore(end);
        }
    }

    private String getDestination(NotificationUser user, String channel) {
        return switch (channel.toUpperCase()) {
            case "EMAIL" -> user.getEmail();
            case "SMS" -> user.getPhone();
            case "PUSH" -> user.getFcmToken();
            default -> null;
        };
    }

    private String resolveTemplate(String templateContent, Map<String, Object> data) {
        try {
            Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
            freemarker.template.Template t = new freemarker.template.Template("templateName", new StringReader(templateContent), cfg);
            StringWriter out = new StringWriter();
            t.process(data, out);
            return out.toString();
        } catch (IOException | TemplateException e) {
            log.error("Failed to resolve freemarker template", e);
            return templateContent; // fallback to unparsed
        }
    }
}
