package com.zwiki.service;
import com.zwiki.domain.dto.LlmKeyDto;
import com.zwiki.domain.dto.LlmProviderStatsDto;
import com.zwiki.domain.dto.LlmUsageTrendDto;
import com.zwiki.repository.dao.LlmKeyRepository;
import com.zwiki.repository.dao.LlmProviderStatsRepository;
import com.zwiki.repository.dao.LlmUsageDailyRepository;
import com.zwiki.repository.entity.LlmProviderStats;
import com.zwiki.repository.entity.LlmUsageDaily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmUsageStatsService {

    private final LlmKeyRepository llmKeyRepository;
    private final LlmProviderStatsRepository providerStatsRepository;
    private final LlmUsageDailyRepository usageDailyRepository;
    private final LlmKeyService llmKeyService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(String modelName, Long keyId, String provider, String userId,
                            long inputTokens, long outputTokens, long totalTokens) {
        if (!StringUtils.hasText(userId)) {
            log.warn("recordUsage skipped: userId is empty");
            return;
        }

        LocalDate today = LocalDate.now();

        try {
            // 1. Update Key stats
            if (keyId != null) {
                llmKeyRepository.recordTokenUsageForKey(keyId, inputTokens, outputTokens, totalTokens);
                // Daily for KEY dimension
                usageDailyRepository.recordTokenUsage(userId, today, "KEY", String.valueOf(keyId),
                        inputTokens, outputTokens, totalTokens);
            }

            // 2. Update Provider stats
            if (StringUtils.hasText(provider)) {
                providerStatsRepository.recordTokenUsage(userId, provider, inputTokens, outputTokens, totalTokens);
                // Daily for PROVIDER dimension
                usageDailyRepository.recordTokenUsage(userId, today, "PROVIDER", provider,
                        inputTokens, outputTokens, totalTokens);
            }

            // 3. Daily for MODEL dimension
            if (StringUtils.hasText(modelName)) {
                usageDailyRepository.recordTokenUsage(userId, today, "MODEL", modelName,
                        inputTokens, outputTokens, totalTokens);
            }

            log.debug("Recorded usage stats: model={}, keyId={}, provider={}, userId={}, tokens={}",
                    modelName, keyId, provider, userId, totalTokens);
        } catch (Exception e) {
            log.error("Failed to record usage stats: model={}, keyId={}, provider={}, userId={}",
                    modelName, keyId, provider, userId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(String modelName, Long keyId, String provider, String userId) {
        if (!StringUtils.hasText(userId)) {
            log.warn("recordError skipped: userId is empty");
            return;
        }

        LocalDate today = LocalDate.now();

        try {
            // 1. Update Key stats
            if (keyId != null) {
                llmKeyRepository.recordErrorForKey(keyId);
                usageDailyRepository.recordError(userId, today, "KEY", String.valueOf(keyId));
            }

            // 2. Update Provider stats
            if (StringUtils.hasText(provider)) {
                providerStatsRepository.recordError(userId, provider);
                usageDailyRepository.recordError(userId, today, "PROVIDER", provider);
            }

            // 3. Daily for MODEL dimension
            if (StringUtils.hasText(modelName)) {
                usageDailyRepository.recordError(userId, today, "MODEL", modelName);
            }

            log.debug("Recorded error stats: model={}, keyId={}, provider={}, userId={}",
                    modelName, keyId, provider, userId);
        } catch (Exception e) {
            log.error("Failed to record error stats: model={}, keyId={}, provider={}, userId={}",
                    modelName, keyId, provider, userId, e);
        }
    }

    public List<LlmKeyDto> getKeyStatsList(String userId) {
        return llmKeyService.listByUserId(userId);
    }

    public List<LlmProviderStatsDto> getProviderStats(String userId) {
        List<LlmProviderStats> stats = providerStatsRepository.findByUserIdOrderByTotalTokensDesc(userId);
        return stats.stream()
                .map(this::toProviderStatsDto)
                .collect(Collectors.toList());
    }

    public LlmUsageTrendDto getTrend(String userId, String dimensionType, String dimensionId,
                                     LocalDate startDate, LocalDate endDate) {
        List<LlmUsageDaily> dailyData;
        if (StringUtils.hasText(dimensionId)) {
            dailyData = usageDailyRepository
                    .findByUserIdAndDimensionTypeAndDimensionIdAndStatDateBetweenOrderByStatDateAsc(
                            userId, dimensionType, dimensionId, startDate, endDate);
        } else {
            dailyData = usageDailyRepository
                    .findByUserIdAndDimensionTypeAndStatDateBetweenOrderByStatDateAsc(
                            userId, dimensionType, startDate, endDate);
        }

        List<LlmUsageTrendDto.DailyData> data = dailyData.stream()
                .map(d -> LlmUsageTrendDto.DailyData.builder()
                        .date(d.getStatDate())
                        .callCount(d.getCallCount())
                        .errorCount(d.getErrorCount())
                        .inputTokens(d.getInputTokens())
                        .outputTokens(d.getOutputTokens())
                        .totalTokens(d.getTotalTokens())
                        .build())
                .collect(Collectors.toList());

        return LlmUsageTrendDto.builder()
                .dimensionType(dimensionType)
                .dimensionId(dimensionId)
                .data(data)
                .build();
    }

    public LlmUsageTrendDto getAggregatedTrend(String userId, String dimensionType,
                                                LocalDate startDate, LocalDate endDate) {
        List<LlmUsageDaily> dailyData = usageDailyRepository
                .findByUserIdAndDimensionTypeAndStatDateBetweenOrderByStatDateAsc(
                        userId, dimensionType, startDate, endDate);

        // Aggregate by date
        var aggregated = dailyData.stream()
                .collect(Collectors.groupingBy(LlmUsageDaily::getStatDate))
                .entrySet().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .map(entry -> {
                    long calls = entry.getValue().stream().mapToLong(d -> d.getCallCount() != null ? d.getCallCount() : 0).sum();
                    long errors = entry.getValue().stream().mapToLong(d -> d.getErrorCount() != null ? d.getErrorCount() : 0).sum();
                    long input = entry.getValue().stream().mapToLong(d -> d.getInputTokens() != null ? d.getInputTokens() : 0).sum();
                    long output = entry.getValue().stream().mapToLong(d -> d.getOutputTokens() != null ? d.getOutputTokens() : 0).sum();
                    long total = entry.getValue().stream().mapToLong(d -> d.getTotalTokens() != null ? d.getTotalTokens() : 0).sum();
                    return LlmUsageTrendDto.DailyData.builder()
                            .date(entry.getKey())
                            .callCount(calls)
                            .errorCount(errors)
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(total)
                            .build();
                })
                .collect(Collectors.toList());

        return LlmUsageTrendDto.builder()
                .dimensionType(dimensionType)
                .dimensionId("ALL")
                .data(aggregated)
                .build();
    }

    private LlmProviderStatsDto toProviderStatsDto(LlmProviderStats stats) {
        return LlmProviderStatsDto.builder()
                .id(stats.getId())
                .userId(stats.getUserId())
                .provider(stats.getProvider())
                .callCount(stats.getCallCount())
                .errorCount(stats.getErrorCount())
                .inputTokens(stats.getInputTokens())
                .outputTokens(stats.getOutputTokens())
                .totalTokens(stats.getTotalTokens())
                .lastUsedTime(stats.getLastUsedTime())
                .build();
    }
}
