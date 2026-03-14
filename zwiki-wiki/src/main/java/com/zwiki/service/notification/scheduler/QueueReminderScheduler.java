package com.zwiki.service.notification.scheduler;
import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.config.MailProperties;
import com.zwiki.domain.enums.NotificationType;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.dao.ZwikiUserRepository;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.entity.ZwikiUser;
import com.zwiki.service.TaskQueueService;
import com.zwiki.service.notification.EmailService;
import com.zwiki.service.notification.NotificationService;
import com.zwiki.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 排队超时提醒定时任务
 * 每分钟扫描等待队列，对超时任务发送站内+邮件提醒
 *
 * @author zwiki
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueReminderScheduler {

    private final TaskQueueService taskQueueService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final MailProperties mailProperties;
    private final RedisUtil redisUtil;
    private final TaskRepository taskRepository;
    private final ZwikiUserRepository zwikiUserRepository;

    @Scheduled(fixedDelay = 60_000)
    public void checkQueueTimeout() {
        if (!mailProperties.isEnabled()) {
            return;
        }

        int timeoutMinutes = mailProperties.getQueueTimeoutMinutes();
        long timeoutMillis = (long) timeoutMinutes * 60 * 1000;

        Set<String> waitingTaskIds = taskQueueService.getWaitingTaskIds();
        if (waitingTaskIds.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        for (String taskId : waitingTaskIds) {
            try {
                Long enqueueTime = taskQueueService.getEnqueueTime(taskId);
                if (enqueueTime == null) {
                    continue;
                }

                long waitMillis = now - enqueueTime;
                if (waitMillis < timeoutMillis) {
                    continue;
                }

                // 检查冷却
                String cooldownKey = RedisKeyEnum.QUEUE_TIMEOUT_COOLDOWN.getKey(taskId);
                if (Boolean.TRUE.equals(redisUtil.hasKey(cooldownKey))) {
                    continue;
                }

                // 查询任务获取userId和projectName
                Task task = taskRepository.findFirstByTaskId(taskId).orElse(null);
                if (task == null || !StringUtils.hasText(task.getUserId())) {
                    continue;
                }

                int waitMinutes = (int) (waitMillis / 60_000);
                String projectName = StringUtils.hasText(task.getProjectName()) ? task.getProjectName() : taskId;

                // 发送站内通知
                notificationService.sendSystemNotification(
                        task.getUserId(),
                        NotificationType.QUEUE_TIMEOUT.getTitle(),
                        String.format("您的项目「%s」已在队列中等待超过 %d 分钟，当前系统处理繁忙，请耐心等待。", projectName, waitMinutes),
                        "/repo/" + taskId,
                        taskId
                );

                // 发送邮件（如果用户启用了邮件通知）
                ZwikiUser user = zwikiUserRepository.findFirstByUserId(task.getUserId()).orElse(null);
                if (user != null
                        && StringUtils.hasText(user.getEmail())
                        && Boolean.TRUE.equals(user.getEmailNotificationEnabled())) {
                    emailService.sendQueueTimeoutEmail(
                            user.getEmail(), user.getUserId(), projectName, taskId, waitMinutes);
                }

                // 设置冷却
                long cooldownSeconds = (long) mailProperties.getQueueReminderCooldownMinutes() * 60;
                redisUtil.set(cooldownKey, "1", cooldownSeconds);

                log.info("发送排队超时提醒: taskId={}, waitMinutes={}", taskId, waitMinutes);
            } catch (Exception e) {
                log.warn("处理排队超时提醒失败: taskId={}, error={}", taskId, e.getMessage());
            }
        }
    }
}
