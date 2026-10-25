package com.notifyengine.notification;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = true)
    private NotificationRequest request;

    @Column(nullable = false)
    private String channel; // EMAIL, SMS, PUSH

    @Column(nullable = false)
    private String status; // PENDING, SENT, FAILED, RETRYING, DEAD

    @Column(columnDefinition = "TEXT")
    private String providerResponse;

    @Column(nullable = false)
    private int attemptCount = 0;

    private LocalDateTime lastAttemptedAt;
    private LocalDateTime deliveredAt;

    @PrePersist
    protected void onCreate() {
        if (lastAttemptedAt == null) {
            lastAttemptedAt = LocalDateTime.now();
        }
    }
}
