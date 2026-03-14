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
 * 论文版本实体
 * 存储论文的多个版本，支持预览和迭代优化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "zwiki_thesis_version", indexes = {
        @Index(name = "idx_task_id_version", columnList = "taskId,version"),
        @Index(name = "idx_task_id_doc_type_version", columnList = "taskId,docType,version")
})
public class ThesisVersionEntity {

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

    /**
     * 版本号（从1开始递增）
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * 论文标题
     */
    @Column(length = 255)
    private String thesisTitle;

    /**
     * 文档类型：thesis, task_book, opening_report
     */
    @Column(nullable = false, length = 32)
    private String docType;

    /**
     * 论文封面信息（JSON）
     */
    @Column(columnDefinition = "TEXT")
    private String thesisInfo;

    /**
     * 摘要
     */
    @Column(columnDefinition = "TEXT")
    private String abstractContent;

    /**
     * 关键词（JSON数组）
     */
    @Column(length = 512)
    private String keywords;

    /**
     * 章节内容（JSON格式，包含背景、需求、设计、实现、总结等）
     */
    @Column(columnDefinition = "JSON")
    private String chapterContents;

    /**
     * 参考文献（JSON数组）
     */
    @Column(columnDefinition = "TEXT")
    private String referencesList;

    /**
     * 论文完整内容（Markdown或JSON）
     */
    @Column(columnDefinition = "LONGTEXT")
    private String fullContent;

    /**
     * 论文HTML预览内容
     */
    @Column(columnDefinition = "LONGTEXT")
    private String htmlPreview;

    /**
     * DOCX文件路径
     */
    @Column(length = 512)
    private String docxFilePath;

    /**
     * Markdown文件路径
     */
    @Column(length = 512)
    private String markdownFilePath;

    /**
     * PDF文件路径
     */
    @Column(length = 512)
    private String pdfFilePath;

    /**
     * 版本状态：draft, preview, final
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 版本说明
     */
    @Column(columnDefinition = "TEXT")
    private String versionNotes;

    /**
     * 使用的模板路径
     */
    @Column(length = 512)
    private String templatePath;

    /**
     * 插入的图表信息（JSON格式）
     */
    @Column(columnDefinition = "TEXT")
    private String insertedDiagrams;

    /**
     * 是否为当前版本
     */
    @Column(nullable = false)
    private Boolean isCurrent = false;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
