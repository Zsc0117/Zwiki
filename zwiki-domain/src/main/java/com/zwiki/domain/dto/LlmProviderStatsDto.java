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
public class LlmProviderStatsDto {

    private Long id;

    private String userId;

    private String provider;

    private Long callCount;

    private Long errorCount;

    private Long inputTokens;

    private Long outputTokens;

    private Long totalTokens;

    private LocalDateTime lastUsedTime;
}
