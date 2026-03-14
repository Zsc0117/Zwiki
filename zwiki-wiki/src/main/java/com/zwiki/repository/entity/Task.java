package com.zwiki.repository.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zwiki.domain.enums.TaskStatusEnum;
import com.zwiki.repository.jpa.TaskStatusEnumConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @author pai
 * @description: 任务表
 * @date 2026/1/20 23:22
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "zwiki_task")
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "project_url")
    private String projectUrl;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "project_path")
    private String projectPath;

    @Column(name = "status")
    @Convert(converter = TaskStatusEnumConverter.class)
    private TaskStatusEnum status;

    @Column(name = "fail_reason")
    private String failReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
