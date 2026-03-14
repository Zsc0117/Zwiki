package com.zwiki.repository.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统通知实体
 *
 * @author zwiki
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "zwiki_notification")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false, unique = true, length = 64)
    private String notificationId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "notification_type", nullable = false, length = 32)
    private String notificationType;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "project_name", length = 255)
    private String projectName;

    @Column(name = "resource_url", length = 512)
    private String resourceUrl;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "progress")
    private Integer progress;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "extra_data", columnDefinition = "JSON")
    private String extraData;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "read_time")
    private LocalDateTime readTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (isRead == null) {
            isRead = false;
        }
    }
}
