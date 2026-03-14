package com.zwiki.mcpserver.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
    private String status;

    @Column(name = "fail_reason")
    private String failReason;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
