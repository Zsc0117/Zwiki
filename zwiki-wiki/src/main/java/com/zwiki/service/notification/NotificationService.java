package com.zwiki.service.notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.domain.dto.NotificationMessage;
import com.zwiki.domain.enums.NotificationType;
import com.zwiki.repository.dao.NotificationRepository;
import com.zwiki.repository.dao.ZwikiUserRepository;
import com.zwiki.repository.entity.NotificationEntity;
import com.zwiki.repository.entity.ZwikiUser;
import com.zwiki.websocket.NotificationWebSocketHandler;
import com.zwiki.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 通知服务
 * 负责发送和管理系统通知
 *
 * @author zwiki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;
    private final NotificationRepository notificationRepository;
    private final ZwikiUserRepository zwikiUserRepository;
    private final ApplicationContext applicationContext;

    /**
     * 发送任务入队通知（带排队快照信息）
     */
    public void notifyTaskQueued(String userId, String taskId, String projectName,
                                  Integer queuePosition, Integer aheadCount, Integer estimatedWaitMinutes) {
        String msg;
        if (estimatedWaitMinutes != null && estimatedWaitMinutes > 0) {
            msg = String.format("您的项目「%s」已加入处理队列，当前排第%d位，前方%d个任务，预计等待%d分钟",
                    projectName, queuePosition, aheadCount, estimatedWaitMinutes);
        } else {
            msg = String.format("您的项目「%s」已加入处理队列，当前排第%d位", projectName, queuePosition);
        }

        NotificationMessage notification = NotificationMessage.builder()
                .id(UUID.randomUUID().toString())
                .notificationType(NotificationType.TASK_QUEUED.getCode())
                .taskId(taskId)
                .userId(userId)
                .projectName(projectName)
                .status("pending")
                .title(NotificationType.TASK_QUEUED.getTitle())
                .message(msg)
                .resourceUrl("/repo/" + taskId)
                .timestamp(System.currentTimeMillis())
                .read(false)
                .queuePosition(queuePosition)
                .queueAheadCount(aheadCount)
                .estimatedWaitMinutes(estimatedWaitMinutes)
                .build();

        sendNotification(notification);
    }

    /**
     * 发送任务开始处理通知
     */
    public void notifyTaskStarted(String userId, String taskId, String projectName) {
        NotificationMessage notification = NotificationMessage.builder()
                .id(UUID.randomUUID().toString())
                .notificationType(NotificationType.TASK_STARTED.getCode())
                .taskId(taskId)
                .userId(userId)
                .projectName(projectName)
                .status("processing")
                .progress(0)
                .title(NotificationType.TASK_STARTED.getTitle())
                .message(String.format("您的项目「%s」已开始处理", projectName))
                .resourceUrl("/repo/" + taskId)
                .timestamp(System.currentTimeMillis())
                .read(false)
                .build();

        sendNotification(notification);
    }

    /**
     * 发送任务进度更新（仅WebSocket推送，不持久化）
     */
    public void notifyTaskProgress(String userId, String taskId, Integer progress, String currentStep) {
        try {
            NotificationMessage notification = NotificationMessage.builder()
                    .notificationType(NotificationType.TASK_PROGRESS.getCode())
                    .taskId(taskId)
                    .userId(userId)
                    .progress(progress)
                    .currentStep(currentStep)
                    .timestamp(System.currentTimeMillis())
                    .build();

            String message = objectMapper.writeValueAsString(notification);
            NotificationWebSocketHandler.sendToUser(userId, message);
            log.debug("推送进度更新: userId={}, taskId={}, progress={}%", userId, taskId, progress);
        } catch (Exception e) {
            log.error("推送进度更新失败: userId={}, taskId={}", userId, taskId, e);
        }
    }

    /**
     * 发送任务完成通知（站内 + 邮件）
     */
    public void notifyTaskCompleted(String userId, String taskId, String projectName) {
        NotificationMessage notification = NotificationMessage.builder()
                .id(UUID.randomUUID().toString())
                .notificationType(NotificationType.TASK_COMPLETED.getCode())
                .taskId(taskId)
                .userId(userId)
                .projectName(projectName)
                .status("completed")
                .progress(100)
                .title(NotificationType.TASK_COMPLETED.getTitle())
                .message(String.format("🎉 项目「%s」分析完成！", projectName))
                .resourceUrl("/repo/" + taskId)
                .timestamp(System.currentTimeMillis())
                .read(false)
                .build();

        sendNotification(notification);

        // 异步发送邮件通知
        trySendEmail(userId, (emailService, user) ->
                emailService.sendTaskCompletedEmail(user.getEmail(), userId, projectName, taskId));
    }

    /**
     * 发送任务失败通知（站内 + 邮件）
     */
    public void notifyTaskFailed(String userId, String taskId, String projectName, String errorMessage) {
        NotificationMessage notification = NotificationMessage.builder()
                .id(UUID.randomUUID().toString())
                .notificationType(NotificationType.TASK_FAILED.getCode())
                .taskId(taskId)
                .userId(userId)
                .projectName(projectName)
                .status("failed")
                .errorMessage(errorMessage)
                .title(NotificationType.TASK_FAILED.getTitle())
                .message(String.format("❌ 项目「%s」处理失败: %s", projectName, errorMessage))
                .resourceUrl("/repo/" + taskId)
                .timestamp(System.currentTimeMillis())
                .read(false)
                .build();

        sendNotification(notification);

        // 异步发送邮件通知
        trySendEmail(userId, (emailService, user) ->
                emailService.sendTaskFailedEmail(user.getEmail(), userId, projectName, taskId, errorMessage));
    }

    /**
     * 发送系统通知
     */
    public void sendSystemNotification(String userId, String title, String message) {
        NotificationMessage notification = NotificationMessage.builder()
                .id(UUID.randomUUID().toString())
                .notificationType(NotificationType.SYSTEM.getCode())
                .userId(userId)
                .title(title)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .read(false)
                .build();

        sendNotification(notification);
    }

    public void sendSystemNotification(String userId, String title, String message, String resourceUrl, String taskId) {
        NotificationMessage notification = NotificationMessage.builder()
                .id(UUID.randomUUID().toString())
                .notificationType(NotificationType.SYSTEM.getCode())
                .userId(userId)
                .title(title)
                .message(message)
                .resourceUrl(resourceUrl)
                .taskId(taskId)
                .timestamp(System.currentTimeMillis())
                .read(false)
                .build();

        sendNotification(notification);
    }

    /**
     * 广播系统通知给所有在线用户
     */
    public int broadcastSystemNotification(String title, String message) {
        try {
            NotificationMessage notification = NotificationMessage.builder()
                    .id(UUID.randomUUID().toString())
                    .notificationType(NotificationType.SYSTEM.getCode())
                    .title(title)
                    .message(message)
                    .timestamp(System.currentTimeMillis())
                    .read(false)
                    .build();

            String jsonMessage = objectMapper.writeValueAsString(notification);
            return NotificationWebSocketHandler.broadcastToAll(jsonMessage);
        } catch (Exception e) {
            log.error("广播系统通知失败", e);
            return 0;
        }
    }

    /**
     * 尝试给用户发送邮件通知（检查用户邮箱和邮件通知开关）
     * 使用 ApplicationContext 懒加载 EmailService 以避免循环依赖
     */
    private void trySendEmail(String userId, EmailAction action) {
        try {
            if (!StringUtils.hasText(userId)) {
                return;
            }
            ZwikiUser user = zwikiUserRepository.findFirstByUserId(userId).orElse(null);
            if (user == null || !StringUtils.hasText(user.getEmail())
                    || !Boolean.TRUE.equals(user.getEmailNotificationEnabled())) {
                return;
            }
            EmailService emailService = applicationContext.getBean(EmailService.class);
            if (!emailService.isEnabled()) {
                return;
            }
            action.execute(emailService, user);
        } catch (Exception e) {
            log.warn("尝试发送邮件通知失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface EmailAction {
        void execute(EmailService emailService, ZwikiUser user);
    }

    /**
     * 发送通知（持久化到数据库 + WebSocket推送）
     */
    private void sendNotification(NotificationMessage notification) {
        try {
            // 1. 持久化到数据库
            NotificationEntity entity = toEntity(notification);
            notificationRepository.save(entity);

            // 2. 使Redis未读计数失效（下次查询时从数据库重新加载）
            invalidateUnreadCountCache(notification.getUserId());

            // 3. WebSocket实时推送
            String jsonMessage = objectMapper.writeValueAsString(notification);
            NotificationWebSocketHandler.sendToUser(notification.getUserId(), jsonMessage);

            log.info("通知发送成功: userId={}, type={}, title={}", 
                    notification.getUserId(), notification.getNotificationType(), notification.getTitle());

        } catch (Exception e) {
            log.error("发送通知失败: userId={}", notification.getUserId(), e);
        }
    }

    /**
     * 将DTO转换为Entity
     */
    private NotificationEntity toEntity(NotificationMessage msg) {
        return NotificationEntity.builder()
                .notificationId(msg.getId())
                .userId(msg.getUserId())
                .notificationType(msg.getNotificationType())
                .title(msg.getTitle())
                .message(msg.getMessage())
                .taskId(msg.getTaskId())
                .projectName(msg.getProjectName())
                .resourceUrl(msg.getResourceUrl())
                .status(msg.getStatus())
                .progress(msg.getProgress())
                .errorMessage(msg.getErrorMessage())
                .isRead(Boolean.TRUE.equals(msg.getRead()))
                .createTime(LocalDateTime.now())
                .build();
    }

    /**
     * 将Entity转换为DTO
     */
    private NotificationMessage toDto(NotificationEntity entity) {
        return NotificationMessage.builder()
                .id(entity.getNotificationId())
                .userId(entity.getUserId())
                .notificationType(entity.getNotificationType())
                .title(entity.getTitle())
                .message(entity.getMessage())
                .taskId(entity.getTaskId())
                .projectName(entity.getProjectName())
                .resourceUrl(entity.getResourceUrl())
                .status(entity.getStatus())
                .progress(entity.getProgress())
                .errorMessage(entity.getErrorMessage())
                .read(entity.getIsRead())
                .timestamp(entity.getCreateTime() != null ? 
                        entity.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 
                        System.currentTimeMillis())
                .build();
    }

    /**
     * 使未读计数缓存失效
     */
    private void invalidateUnreadCountCache(String userId) {
        try {
            String countKey = RedisKeyEnum.NOTIFICATION_UNREAD_COUNT_CACHE.getKey(userId);
            redisUtil.delete(countKey);
        } catch (Exception e) {
            log.warn("删除未读计数缓存失败: userId={}", userId, e);
        }
    }

    /**
     * 获取用户的通知列表（从数据库查询）
     */
    public List<NotificationMessage> getNotifications(String userId, int page, int size) {
        try {
            Page<NotificationEntity> entityPage = notificationRepository.findByUserIdOrderByCreateTimeDesc(
                    userId, PageRequest.of(page, size));
            
            return entityPage.getContent().stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("获取通知列表失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取通知总数
     */
    public long getTotalCount(String userId) {
        try {
            return notificationRepository.findByUserIdOrderByCreateTimeDesc(userId).size();
        } catch (Exception e) {
            log.error("获取通知总数失败: userId={}", userId, e);
            return 0;
        }
    }

    /**
     * 获取未读通知数量（先查缓存，缓存未命中则查数据库）
     */
    public int getUnreadCount(String userId) {
        try {
            // 先查Redis缓存
            String countKey = RedisKeyEnum.NOTIFICATION_UNREAD_COUNT_CACHE.getKey(userId);
            String cachedCount = redisUtil.get(countKey);
            if (cachedCount != null) {
                return Integer.parseInt(cachedCount);
            }

            // 缓存未命中，从数据库查询
            long count = notificationRepository.countByUserIdAndIsReadFalse(userId);
            
            // 写入缓存 (24小时)
            redisUtil.set(countKey, String.valueOf(count), 24 * 60 * 60L);
            
            return (int) count;
        } catch (Exception e) {
            log.error("获取未读数量失败: userId={}", userId, e);
            return 0;
        }
    }

    /**
     * 标记所有通知为已读
     */
    @Transactional
    public void markAllAsRead(String userId) {
        try {
            int updated = notificationRepository.markAllAsReadByUserId(userId, LocalDateTime.now());
            invalidateUnreadCountCache(userId);
            log.info("标记所有通知为已读: userId={}, count={}", userId, updated);
        } catch (Exception e) {
            log.error("标记已读失败: userId={}", userId, e);
        }
    }

    /**
     * 标记单个通知为已读
     */
    @Transactional
    public void markAsRead(String userId, String notificationId) {
        try {
            int updated = notificationRepository.markAsReadByNotificationId(notificationId, LocalDateTime.now());
            if (updated > 0) {
                invalidateUnreadCountCache(userId);
            }
            log.debug("标记通知已读: userId={}, notificationId={}, updated={}", userId, notificationId, updated);
        } catch (Exception e) {
            log.error("标记已读失败: userId={}, notificationId={}", userId, notificationId, e);
        }
    }

    /**
     * 清空用户所有通知
     */
    @Transactional
    public void clearAllNotifications(String userId) {
        try {
            notificationRepository.deleteByUserId(userId);
            invalidateUnreadCountCache(userId);
            log.info("清空所有通知: userId={}", userId);
        } catch (Exception e) {
            log.error("清空通知失败: userId={}", userId, e);
        }
    }

    /**
     * 清理过期通知（可定时调用）
     */
    @Transactional
    public int cleanupExpiredNotifications(int retentionDays) {
        try {
            LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);
            int deleted = notificationRepository.deleteByCreateTimeBefore(beforeTime);
            log.info("清理过期通知: beforeTime={}, deleted={}", beforeTime, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("清理过期通知失败", e);
            return 0;
        }
    }

    /**
     * 检查用户是否在线
     */
    public boolean isUserOnline(String userId) {
        return NotificationWebSocketHandler.isUserOnline(userId);
    }

    /**
     * 获取在线用户数
     */
    public int getOnlineUserCount() {
        return NotificationWebSocketHandler.getOnlineUserCount();
    }
}
