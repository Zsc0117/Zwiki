package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModelDto {

    private Long id;

    private String userId;

    private Long llmKeyId;

    private String keyName;

    private String name;

    /**
     * 唯一标识符，格式: keyId:name
     * 用于场景模型选择时精确匹配到具体 Key 下的模型
     */
    private String uniqueId;

    private String displayName;

    private String provider;

    private String modelType;

    private Boolean enabled;

    private Integer weight;

    private Integer priority;

    private Long quotaLimit;

    private Long inputTokens;

    private Long outputTokens;

    private Long totalTokens;

    private Long callCount;

    private Long errorCount;

    private LocalDateTime lastUsedTime;

    private LocalDate quotaResetDate;

    private String description;

    private String capabilities;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private boolean healthy;

    private Long unhealthyUntilEpochMillis;

    private long remainingCooldownMillis;
}
