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
public class LlmKeyDto {

    private Long id;

    private String userId;

    private String name;

    private String provider;

    private Boolean enabled;

    private String description;

    private String apiKeyMasked;

    private String baseUrl;

    private String apiVersion;

    private String extraHeaders;

    private Long callCount;

    private Long errorCount;

    private Long inputTokens;

    private Long outputTokens;

    private Long totalTokens;

    private LocalDateTime lastUsedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
