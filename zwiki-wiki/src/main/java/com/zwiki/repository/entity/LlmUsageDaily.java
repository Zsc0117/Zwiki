package com.zwiki.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "zwiki_llm_usage_daily")
public class LlmUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "dimension_type", nullable = false, length = 16)
    private String dimensionType;

    @Column(name = "dimension_id", nullable = false, length = 128)
    private String dimensionId;

    @Column(name = "call_count")
    @Builder.Default
    private Long callCount = 0L;

    @Column(name = "error_count")
    @Builder.Default
    private Long errorCount = 0L;

    @Column(name = "input_tokens")
    @Builder.Default
    private Long inputTokens = 0L;

    @Column(name = "output_tokens")
    @Builder.Default
    private Long outputTokens = 0L;

    @Column(name = "total_tokens")
    @Builder.Default
    private Long totalTokens = 0L;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
