package com.zwiki.domain.enums;

import lombok.Getter;

/**
 * 通知类型枚举
 *
 * @author zwiki
 */
@Getter
public enum NotificationType {
    
    TASK_QUEUED("TASK_QUEUED", "任务已入队"),
    TASK_STARTED("TASK_STARTED", "任务开始处理"),
    TASK_PROGRESS("TASK_PROGRESS", "任务进度更新"),
    TASK_COMPLETED("TASK_COMPLETED", "任务处理完成"),
    TASK_FAILED("TASK_FAILED", "任务处理失败"),
    QUEUE_TIMEOUT("QUEUE_TIMEOUT", "排队超时提醒"),
    SYSTEM("SYSTEM", "系统通知");

    private final String code;
    private final String title;

    NotificationType(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public static NotificationType fromCode(String code) {
        for (NotificationType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return SYSTEM;
    }
}
