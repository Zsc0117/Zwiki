package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubReviewConfigDto {

    private Long id;

    private String repoFullName;

    private String webhookSecret;

    private String customPat;

    private Boolean enabled;

    private LocalDateTime lastReviewAt;

    private Integer reviewCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Webhook回调URL（前端展示用，不存DB）
     */
    private String webhookUrl;

    /**
     * 是否使用自定义PAT（前端展示用）
     */
    private Boolean usingCustomPat;
}
