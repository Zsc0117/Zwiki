package com.zwiki.repository.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 文档变更记录实体
 * 记录 Wiki 文档每次更新的变更详情
 */
@Entity
@Table(name = "zwiki_doc_change", indexes = {
    @Index(name = "idx_task_id", columnList = "taskId"),
    @Index(name = "idx_catalogue_id", columnList = "catalogueId"),
    @Index(name = "idx_created_at", columnList = "createdAt"),
    @Index(name = "idx_change_type", columnList = "changeType")
})
@Data
public class DocChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "catalogue_id", length = 64)
    private String catalogueId;

    @Column(name = "change_type", nullable = false, length = 32)
    private String changeType;

    @Column(name = "trigger_source", length = 64)
    private String triggerSource;

    @Column(name = "before_content_hash", length = 64)
    private String beforeContentHash;

    @Column(name = "after_content_hash", length = 64)
    private String afterContentHash;

    @Column(name = "before_content_summary", columnDefinition = "TEXT")
    private String beforeContentSummary;

    @Column(name = "after_content_summary", columnDefinition = "TEXT")
    private String afterContentSummary;

    @Column(name = "diff_content", columnDefinition = "TEXT")
    private String diffContent;

    @Column(name = "triggered_by", length = 64)
    private String triggeredBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // 变更类型枚举
    public enum ChangeType {
        ADD("新增"),
        MODIFY("修改"),
        DELETE("删除");

        private final String displayName;

        ChangeType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // 触发来源枚举
    public enum TriggerSource {
        WEBHOOK("Webhook推送"),
        MANUAL("手动触发"),
        SYSTEM("系统自动");

        private final String displayName;

        TriggerSource(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
