package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmBalancerConfigDto {

    private boolean enabled;

    private String strategy;

    private int maxAttemptsPerRequest;

    private int unhealthyCooldownSeconds;

    private boolean allowFallbackOnExplicitModel;
}
