package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageTrendDto {

    private String dimensionType;

    private String dimensionId;

    private List<DailyData> data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyData {
        private LocalDate date;
        private Long callCount;
        private Long errorCount;
        private Long inputTokens;
        private Long outputTokens;
        private Long totalTokens;
    }
}
