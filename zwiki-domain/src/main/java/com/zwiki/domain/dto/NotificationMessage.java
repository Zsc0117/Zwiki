package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 系统通知消息DTO
 * 用于向用户发送系统通知
 *
 * @author zwiki
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 通知ID
     */
    private String id;

    /**
     * 通知类型：TASK_QUEUED, TASK_STARTED, TASK_PROGRESS, TASK_COMPLETED, TASK_FAILED, SYSTEM
     */
    private String notificationType;

    /**
     * 任务ID（如果关联任务）
     */
    private String taskId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 任务类型
     */
    private String taskType;

    /**
     * 当前状态
     */
    private String status;

    /**
     * 进度（0-100）
     */
    private Integer progress;

    /**
     * 当前步骤描述
     */
    private String currentStep;

    /**
     * 消息标题
     */
    private String title;

    /**
     * 消息内容
     */
    private String message;

    /**
     * 错误信息（如果有）
     */
    private String errorMessage;

    /**
     * 跳转链接
     */
    private String resourceUrl;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 是否已读
     */
    private Boolean read;

    /**
     * 优先级
     */
    private String priority;

    /**
     * 排队位置（第几位）
     */
    private Integer queuePosition;

    /**
     * 前方等待任务数
     */
    private Integer queueAheadCount;

    /**
     * 预计等待时间（分钟）
     */
    private Integer estimatedWaitMinutes;
}
