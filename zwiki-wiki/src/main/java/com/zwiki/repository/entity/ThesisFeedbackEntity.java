package com.zwiki.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 论文反馈实体
 * 存储用户对生成论文的反馈意见
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "zwiki_thesis_feedback", indexes = {
        @Index(name = "idx_task_id", columnList = "taskId"),
        @Index(name = "idx_version", columnList = "version"),
        @Index(name = "idx_task_id_doc_type_version", columnList = "taskId,docType,version")
})
public class ThesisFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的分析任务ID
     */
    @Column(nullable = false, length = 64)
    private String taskId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    private String docType;

    /**
     * 论文版本号
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * 反馈的章节或段落
     */
    @Column(length = 255)
    private String section;

    /**
     * 用户反馈类型：not_detailed, unclear, incorrect, other
     */
    @Column(nullable = false, length = 20)
    private String feedbackType;

    /**
     * 用户反馈详细内容
     */
    @Column(columnDefinition = "TEXT")
    private String feedbackContent;

    /**
     * 原始内容
     */
    @Column(columnDefinition = "LONGTEXT")
    private String originalContent;

    /**
     * 优化后的内容
     */
    @Column(columnDefinition = "LONGTEXT")
    private String optimizedContent;

    /**
     * 是否已处理
     */
    @Column(nullable = false)
    private Boolean processed = false;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
