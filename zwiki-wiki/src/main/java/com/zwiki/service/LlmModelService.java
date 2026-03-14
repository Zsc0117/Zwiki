package com.zwiki.service;

import com.zwiki.domain.dto.LlmModelDto;
import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.llm.service.ModelHealthRepository;
import com.zwiki.repository.dao.LlmKeyRepository;
import com.zwiki.repository.dao.LlmModelRepository;
import com.zwiki.repository.dao.LlmProviderStatsRepository;
import com.zwiki.repository.dao.LlmUsageDailyRepository;
import com.zwiki.repository.entity.LlmKey;
import com.zwiki.repository.entity.LlmModel;
import com.zwiki.service.notification.NotificationService;
import com.zwiki.util.RedisUtil;
import com.zwiki.common.enums.RedisKeyEnum;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModelService {

    private final LlmModelRepository llmModelRepository;
    private final LlmKeyRepository llmKeyRepository;
    private final LlmBalancerProperties balancerProperties;
    private final ModelHealthRepository healthRepository;
    private final NotificationService notificationService;
    private final RedisUtil redisUtil;
    private final LlmProviderStatsRepository providerStatsRepository;
    private final LlmUsageDailyRepository usageDailyRepository;

    @PostConstruct
    public void init() {
        if (!"db".equalsIgnoreCase(balancerProperties.getModelsSource())) {
            refreshModelsFromDatabase();
        }
    }

    @Scheduled(fixedDelayString = "${zwiki.llm.balancer.refresh-interval-ms:30000}")
    public void scheduledRefreshModelsFromDatabase() {
        try {
            if ("db".equalsIgnoreCase(balancerProperties.getModelsSource())) {
                return;
            }
            refreshModelsFromDatabase();
        } catch (Exception e) {
            log.warn("Failed to refresh models from database: {}", e.getMessage());
        }
    }

    public void refreshModelsFromDatabase() {
        List<LlmModel> dbModels = llmModelRepository.findByEnabledTrueOrderByPriorityDescWeightDesc();
        
        List<LlmBalancerProperties.ModelConfig> modelConfigs = dbModels.stream()
                .map(this::toModelConfig)
                .collect(Collectors.toList());
        
        balancerProperties.setModels(modelConfigs);
        log.info("Refreshed {} models from database into balancer", modelConfigs.size());
    }

    private LlmBalancerProperties.ModelConfig toModelConfig(LlmModel model) {
        LlmBalancerProperties.ModelConfig config = new LlmBalancerProperties.ModelConfig();
        config.setLlmKeyId(model.getLlmKeyId());
        config.setName(model.getName());
        config.setDisplayName(model.getDisplayName());
        config.setProvider(model.getProvider());
        config.setModelType(model.getModelType());
        config.setCapabilities(model.getCapabilities());
        config.setEnabled(model.getEnabled());
        config.setWeight(model.getWeight());
        config.setPriority(model.getPriority());
        return config;
    }

    /**
     * 列出用户所有 enabled 的模型（跨所有 Key），供场景模型选择使用。
     * 包含 keyName 和 provider 信息以便前端显示。
     */
    public List<LlmModelDto> listAllEnabledModels(String userId) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        List<LlmKey> keys = llmKeyRepository.findByUserIdAndEnabledTrueOrderByCreateTimeDesc(userId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        
        Map<Long, LlmKey> keyMap = keys.stream().collect(Collectors.toMap(LlmKey::getId, k -> k));
        List<Long> keyIds = keys.stream().map(LlmKey::getId).collect(Collectors.toList());
        List<LlmModel> models = llmModelRepository.findByLlmKeyIdInAndEnabledTrueOrderByPriorityDescWeightDesc(keyIds);
        
        return models.stream().map(m -> {
            LlmKey key = keyMap.get(m.getLlmKeyId());
            return toDtoWithKeyInfo(m, key);
        }).collect(Collectors.toList());
    }

    /**
     * 获取用户所有模型的聚合统计信息（跨所有 Key）。
     */
    public Map<String, Object> getAggregatedStats(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Map.of();
        }
        List<LlmKey> keys = llmKeyRepository.findByUserIdAndEnabledTrueOrderByCreateTimeDesc(userId);
        if (keys == null || keys.isEmpty()) {
            return Map.of("totalModels", 0, "healthyModels", 0, "totalCalls", 0L,
                    "totalErrors", 0L, "totalInputTokens", 0L, "totalOutputTokens", 0L, "totalTokens", 0L);
        }
        
        List<Long> keyIds = keys.stream().map(LlmKey::getId).collect(Collectors.toList());
        List<LlmModel> allModels = llmModelRepository.findByLlmKeyIdInOrderByPriorityDescWeightDesc(keyIds);
        
        Set<String> modelNames = allModels.stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .map(LlmModel::getName)
                .collect(Collectors.toSet());
        Map<String, com.zwiki.llm.model.ModelHealthState> healthStates = healthRepository.getAllModelStates(modelNames);
        
        int totalModels = allModels.size();
        int enabledModels = (int) allModels.stream().filter(m -> Boolean.TRUE.equals(m.getEnabled())).count();
        int healthyModels = (int) allModels.stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .filter(m -> {
                    var state = healthStates.get(m.getName());
                    return state == null || state.isCurrentlyHealthy();
                })
                .count();
        
        long totalCalls = allModels.stream().mapToLong(m -> m.getCallCount() != null ? m.getCallCount() : 0).sum();
        long totalErrors = allModels.stream().mapToLong(m -> m.getErrorCount() != null ? m.getErrorCount() : 0).sum();
        long totalInputTokens = allModels.stream().mapToLong(m -> m.getInputTokens() != null ? m.getInputTokens() : 0).sum();
        long totalOutputTokens = allModels.stream().mapToLong(m -> m.getOutputTokens() != null ? m.getOutputTokens() : 0).sum();
        long totalTokens = allModels.stream().mapToLong(m -> m.getTotalTokens() != null ? m.getTotalTokens() : 0).sum();
        
        // Group by model type
        Map<String, long[]> typeStats = allModels.stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .collect(Collectors.groupingBy(
                        m -> m.getModelType() != null ? m.getModelType() : "TEXT",
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> {
                                    int total = list.size();
                                    int healthy = (int) list.stream().filter(m -> {
                                        var state = healthStates.get(m.getName());
                                        return state == null || state.isCurrentlyHealthy();
                                    }).count();
                                    return new long[]{total, healthy};
                                }
                        )
                ));
        
        return Map.of(
                "totalModels", totalModels,
                "enabledModels", enabledModels,
                "healthyModels", healthyModels,
                "totalCalls", totalCalls,
                "totalErrors", totalErrors,
                "totalInputTokens", totalInputTokens,
                "totalOutputTokens", totalOutputTokens,
                "totalTokens", totalTokens,
                "typeStats", typeStats
        );
    }

    public List<LlmModelDto> listModelsByKeyId(String userId, Long keyId) {
        LlmKey key = loadUserKey(userId, keyId);
        List<LlmModel> models = llmModelRepository.findByLlmKeyIdOrderByPriorityDescWeightDesc(key.getId());
        Set<String> modelNames = models.stream().map(LlmModel::getName).collect(Collectors.toSet());
        Map<String, com.zwiki.llm.model.ModelHealthState> states = healthRepository.getAllModelStates(modelNames);
        return models.stream()
                .map(model -> toDtoWithHealth(model, states.get(model.getName())))
                .collect(Collectors.toList());
    }

    private LlmModelDto toDto(LlmModel model) {
        return LlmModelDto.builder()
                .id(model.getId())
                .userId(model.getUserId())
                .llmKeyId(model.getLlmKeyId())
                .name(model.getName())
                .displayName(model.getDisplayName())
                .provider(model.getProvider())
                .modelType(model.getModelType())
                .enabled(model.getEnabled())
                .weight(model.getWeight())
                .priority(model.getPriority())
                .quotaLimit(model.getQuotaLimit())
                .inputTokens(model.getInputTokens())
                .outputTokens(model.getOutputTokens())
                .totalTokens(model.getTotalTokens())
                .callCount(model.getCallCount())
                .errorCount(model.getErrorCount())
                .lastUsedTime(model.getLastUsedTime())
                .quotaResetDate(model.getQuotaResetDate())
                .description(model.getDescription())
                .capabilities(model.getCapabilities())
                .createTime(model.getCreateTime())
                .updateTime(model.getUpdateTime())
                .healthy(true)
                .build();
    }

    private LlmModelDto toDtoWithKeyInfo(LlmModel model, LlmKey key) {
        // Build uniqueId: keyId:name for precise scene model matching
        String uniqueId = model.getLlmKeyId() != null 
                ? model.getLlmKeyId() + ":" + model.getName()
                : model.getName();
        return LlmModelDto.builder()
                .id(model.getId())
                .userId(model.getUserId())
                .llmKeyId(model.getLlmKeyId())
                .keyName(key != null ? key.getName() : null)
                .name(model.getName())
                .uniqueId(uniqueId)
                .displayName(model.getDisplayName())
                .provider(key != null ? key.getProvider() : model.getProvider())
                .modelType(model.getModelType())
                .enabled(model.getEnabled())
                .weight(model.getWeight())
                .priority(model.getPriority())
                .quotaLimit(model.getQuotaLimit())
                .inputTokens(model.getInputTokens())
                .outputTokens(model.getOutputTokens())
                .totalTokens(model.getTotalTokens())
                .callCount(model.getCallCount())
                .errorCount(model.getErrorCount())
                .lastUsedTime(model.getLastUsedTime())
                .quotaResetDate(model.getQuotaResetDate())
                .description(model.getDescription())
                .capabilities(model.getCapabilities())
                .createTime(model.getCreateTime())
                .updateTime(model.getUpdateTime())
                .healthy(true)
                .build();
    }

    private LlmModelDto toDtoWithHealth(LlmModel model, com.zwiki.llm.model.ModelHealthState state) {
        LlmModelDto.LlmModelDtoBuilder builder = LlmModelDto.builder()
                .id(model.getId())
                .userId(model.getUserId())
                .llmKeyId(model.getLlmKeyId())
                .name(model.getName())
                .displayName(model.getDisplayName())
                .provider(model.getProvider())
                .modelType(model.getModelType())
                .enabled(model.getEnabled())
                .weight(model.getWeight())
                .priority(model.getPriority())
                .quotaLimit(model.getQuotaLimit())
                .inputTokens(model.getInputTokens())
                .outputTokens(model.getOutputTokens())
                .totalTokens(model.getTotalTokens())
                .callCount(model.getCallCount())
                .errorCount(model.getErrorCount())
                .lastUsedTime(model.getLastUsedTime())
                .quotaResetDate(model.getQuotaResetDate())
                .description(model.getDescription())
                .capabilities(model.getCapabilities())
                .createTime(model.getCreateTime())
                .updateTime(model.getUpdateTime());

        if (state != null) {
            builder.healthy(state.isCurrentlyHealthy())
                    .unhealthyUntilEpochMillis(state.getUnhealthyUntilEpochMillis())
                    .remainingCooldownMillis(state.getRemainingCooldownMillis());
        } else {
            builder.healthy(true);
        }

        return builder.build();
    }

    @Transactional
    public LlmModel createModel(String userId, Long keyId, LlmModel model) {
        LlmKey key = loadUserKey(userId, keyId);
        validateModelInput(model);
        String normalizedName = model.getName().trim();
        model.setName(normalizedName);
        if (llmModelRepository.existsByLlmKeyIdAndName(key.getId(), normalizedName)) {
            throw new IllegalArgumentException("该 Key 下模型已存在: " + normalizedName);
        }
        model.setLlmKeyId(key.getId());
        model.setUserId(userId);
        model.setProvider(key.getProvider());
        LlmModel saved;
        try {
            saved = llmModelRepository.save(model);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("该 Key 下模型已存在: " + normalizedName);
        }
        refreshModelsFromDatabase();
        return saved;
    }

    @Transactional
    public LlmModel updateModel(String userId, Long keyId, Long id, LlmModel model) {
        loadUserKey(userId, keyId);
        LlmModel existing = llmModelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + id));

        if (!existing.getLlmKeyId().equals(keyId)) {
            throw new IllegalArgumentException("模型不属于当前 Key");
        }

        validateModelInput(model);
        String normalizedName = model.getName().trim();
        model.setName(normalizedName);
        if (!existing.getName().equals(normalizedName)) {
            if (llmModelRepository.existsByLlmKeyIdAndNameAndIdNot(keyId, normalizedName, id)) {
                throw new IllegalArgumentException("该 Key 下模型已存在: " + normalizedName);
            }
        }

        existing.setName(normalizedName);
        existing.setDisplayName(model.getDisplayName());
        existing.setProvider(existing.getProvider());
        existing.setModelType(model.getModelType());
        existing.setEnabled(model.getEnabled());
        existing.setWeight(model.getWeight());
        existing.setPriority(model.getPriority());
        existing.setQuotaLimit(model.getQuotaLimit());
        existing.setQuotaResetDate(model.getQuotaResetDate());
        existing.setDescription(model.getDescription());
        existing.setCapabilities(model.getCapabilities());

        LlmModel saved = llmModelRepository.save(existing);
        refreshModelsFromDatabase();
        return saved;
    }

    @Transactional
    public void deleteModel(String userId, Long keyId, Long id) {
        loadUserKey(userId, keyId);
        LlmModel model = llmModelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + id));

        if (!keyId.equals(model.getLlmKeyId())) {
            throw new IllegalArgumentException("模型不属于当前 Key");
        }

        llmModelRepository.deleteById(id);
        refreshModelsFromDatabase();
    }

    @Transactional
    public void toggleModelEnabled(String userId, Long keyId, Long id, boolean enabled) {
        loadUserKey(userId, keyId);
        LlmModel model = llmModelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + id));
        if (!keyId.equals(model.getLlmKeyId())) {
            throw new IllegalArgumentException("模型不属于当前 Key");
        }
        model.setEnabled(enabled);
        llmModelRepository.save(model);
        refreshModelsFromDatabase();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTokenUsage(Long keyId, String modelName, String userId, long inputTokens, long outputTokens, long totalTokens) {
        int updated = 0;
        boolean hasUserId = userId != null && !userId.isBlank();

        // Priority 1: Precise match by keyId + modelName
        if (keyId != null) {
            updated = llmModelRepository.recordTokenUsageByKeyId(keyId, modelName, inputTokens, outputTokens, totalTokens);
            if (updated > 0) {
                log.info("Recorded token usage: model={}, keyId={}, input={}, output={}, total={}",
                        modelName, keyId, inputTokens, outputTokens, totalTokens);
                recordKeyProviderDailyUsage(keyId, modelName, userId, inputTokens, outputTokens, totalTokens);
                checkAndSendQuotaWarning(keyId, modelName, userId);
                return;
            }
        }

        // Priority 2: Match by userId
        if (hasUserId) {
            updated = llmModelRepository.recordTokenUsageForUser(modelName, userId, inputTokens, outputTokens, totalTokens);
            if (updated == 0) {
                updated = llmModelRepository.recordTokenUsageForUserByKey(modelName, userId, inputTokens, outputTokens, totalTokens);
            }
        }

        // Fallback: name-only to ensure usage is recorded regardless of public/user_id matching
        if (updated == 0) {
            updated = llmModelRepository.recordTokenUsageByNameOnly(modelName, inputTokens, outputTokens, totalTokens);
        }

        if (updated == 0) {
            log.warn("No model found to record token usage: model={}, keyId={}, userId={}", modelName, keyId, userId);
            return;
        }

        // Record Key/Provider/Daily stats
        recordKeyProviderDailyUsage(keyId, modelName, userId, inputTokens, outputTokens, totalTokens);

        log.info("Recorded token usage: model={}, keyId={}, userId={}, input={}, output={}, total={}",
                modelName, keyId, userId, inputTokens, outputTokens, totalTokens);
        checkAndSendQuotaWarning(keyId, modelName, userId);
    }

    private void recordKeyProviderDailyUsage(Long keyId, String modelName, String userId, long inputTokens, long outputTokens, long totalTokens) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            LlmModel model = findModelForStats(keyId, modelName, userId);
            Long resolvedKeyId = keyId != null ? keyId : (model != null ? model.getLlmKeyId() : null);
            String provider = model != null ? model.getProvider() : null;

            // 1. Update Key stats
            if (resolvedKeyId != null) {
                llmKeyRepository.recordTokenUsageForKey(resolvedKeyId, inputTokens, outputTokens, totalTokens);
                usageDailyRepository.recordTokenUsage(userId, today, "KEY", String.valueOf(resolvedKeyId),
                        inputTokens, outputTokens, totalTokens);
            }

            // 2. Update Provider stats
            if (StringUtils.hasText(provider)) {
                providerStatsRepository.recordTokenUsage(userId, provider, inputTokens, outputTokens, totalTokens);
                usageDailyRepository.recordTokenUsage(userId, today, "PROVIDER", provider,
                        inputTokens, outputTokens, totalTokens);
            }

            // 3. Daily for MODEL dimension
            if (StringUtils.hasText(modelName)) {
                usageDailyRepository.recordTokenUsage(userId, today, "MODEL", modelName,
                        inputTokens, outputTokens, totalTokens);
            }
        } catch (Exception e) {
            log.warn("Failed to record Key/Provider/Daily stats: model={}, userId={}, error={}",
                    modelName, userId, e.getMessage());
        }
    }

    private LlmModel findModelForStats(Long keyId, String modelName, String userId) {
        if (!StringUtils.hasText(modelName)) {
            return null;
        }
        // Priority 1: Precise match by keyId
        if (keyId != null) {
            LlmModel model = llmModelRepository.findByLlmKeyIdAndName(keyId, modelName).orElse(null);
            if (model != null) {
                return model;
            }
        }
        // Priority 2: Try user's keys
        if (StringUtils.hasText(userId)) {
            List<LlmKey> userKeys = llmKeyRepository.findByUserIdOrderByCreateTimeDesc(userId);
            for (LlmKey key : userKeys) {
                LlmModel model = llmModelRepository.findByLlmKeyIdAndName(key.getId(), modelName).orElse(null);
                if (model != null) {
                    return model;
                }
            }
        }
        // Fallback: any model with this name (use findFirst to avoid non-unique result exception
        // when multiple keys have models with the same name)
        return llmModelRepository.findFirstByNameOrderByIdAsc(modelName).orElse(null);
    }

    private void checkAndSendQuotaWarning(Long keyId, String modelName, String userId) {
        try {
            LlmModel model = findModelForStats(keyId, modelName, userId);
            if (model == null || model.getQuotaLimit() == null || model.getQuotaLimit() <= 0) {
                return;
            }

            String targetUserId = model.getUserId() != null ? model.getUserId() : userId;
            if (targetUserId == null) {
                return;
            }

            long usedTokens = model.getTotalTokens() != null ? model.getTotalTokens() : 0;
            long quotaLimit = model.getQuotaLimit();
            double usagePercent = (double) usedTokens / quotaLimit * 100;

            if (usagePercent >= 100) {
                sendQuotaWarningIfNotSent(targetUserId, modelName, model.getDisplayName(), 0, usagePercent);
            } else if (usagePercent >= 90) {
                sendQuotaWarningIfNotSent(targetUserId, modelName, model.getDisplayName(), 10, usagePercent);
            } else if (usagePercent >= 50) {
                sendQuotaWarningIfNotSent(targetUserId, modelName, model.getDisplayName(), 50, usagePercent);
            }
        } catch (Exception e) {
            log.warn("Failed to check quota warning for model {}: {}", modelName, e.getMessage());
        }
    }

    private void sendQuotaWarningIfNotSent(String userId, String modelName, String displayName, int threshold, double currentUsage) {
        String warningKey = RedisKeyEnum.LLM_QUOTA_WARNING_CACHE.getKey(userId, modelName, String.valueOf(threshold));
        try {
            Boolean alreadySent = redisUtil.hasKey(warningKey);
            if (Boolean.TRUE.equals(alreadySent)) {
                return;
            }

            String modelDisplayName = displayName != null ? displayName : modelName;
            String title;
            String message;
            if (threshold == 0) {
                title = "🚨 模型额度已用尽";
                message = String.format(
                        "模型「%s」额度已用尽（已使用 %.1f%%），请及时充值或更换模型。",
                        modelDisplayName, currentUsage);
            } else {
                title = threshold == 10 ? "⚠️ 模型额度严重不足" : "⚠️ 模型额度预警";
                message = String.format(
                        "模型「%s」额度已使用 %.1f%%，剩余额度不足 %d%%，请及时关注。",
                        modelDisplayName, currentUsage, threshold);
            }

            notificationService.sendSystemNotification(userId, title, message);
            
            // 24小时内不重复发送警告
            redisUtil.set(warningKey, "1", 24 * 60 * 60L);
            
            log.info("Sent quota warning: userId={}, model={}, threshold={}%, usage={:.1f}%", 
                    userId, modelName, threshold, currentUsage);
        } catch (Exception e) {
            log.warn("Failed to send quota warning: userId={}, model={}, error={}", userId, modelName, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(Long keyId, String modelName, String userId) {
        int updated = 0;
        boolean hasUserId = userId != null && !userId.isBlank();

        // Priority 1: Precise match by keyId + modelName
        if (keyId != null) {
            updated = llmModelRepository.recordErrorByKeyId(keyId, modelName);
            if (updated > 0) {
                log.info("Recorded error: model={}, keyId={}", modelName, keyId);
                recordKeyProviderDailyError(keyId, modelName, userId);
                return;
            }
        }

        // Priority 2: Match by userId
        if (hasUserId) {
            updated = llmModelRepository.recordErrorForUser(modelName, userId);
            if (updated == 0) {
                updated = llmModelRepository.recordErrorForUserByKey(modelName, userId);
            }
        }

        // Fallback: name-only to ensure errors are recorded
        if (updated == 0) {
            updated = llmModelRepository.recordErrorByNameOnly(modelName);
        }

        if (updated == 0) {
            log.warn("No model found to record error: model={}, keyId={}, userId={}", modelName, keyId, userId);
            return;
        }

        // Record Key/Provider/Daily error stats
        recordKeyProviderDailyError(keyId, modelName, userId);
    }

    private void recordKeyProviderDailyError(Long keyId, String modelName, String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            LlmModel model = findModelForStats(keyId, modelName, userId);
            Long resolvedKeyId = keyId != null ? keyId : (model != null ? model.getLlmKeyId() : null);
            String provider = model != null ? model.getProvider() : null;

            // 1. Update Key stats
            if (resolvedKeyId != null) {
                llmKeyRepository.recordErrorForKey(resolvedKeyId);
                usageDailyRepository.recordError(userId, today, "KEY", String.valueOf(resolvedKeyId));
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
        } catch (Exception e) {
            log.warn("Failed to record Key/Provider/Daily error stats: model={}, keyId={}, userId={}, error={}",
                    modelName, keyId, userId, e.getMessage());
        }
    }

    /**
     * 致命错误时自动持久下线模型（DB enabled=false）。
     * 需管理员手动重新启用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoDisableModel(Long keyId, String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return;
        }
        int updated = llmModelRepository.disableByKeyIdAndName(keyId, modelName);
        if (updated > 0) {
            log.warn("Auto-disabled model '{}' with keyId={} in DB (enabled=false) due to fatal error, {} row(s) affected",
                    modelName, keyId, updated);
            refreshModelsFromDatabase();
        } else {
            log.info("autoDisableModel called for keyId={}, model='{}' but no matching rows found", keyId, modelName);
        }
    }

    private LlmKey loadUserKey(String userId, Long keyId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("未登录");
        }
        if (keyId == null) {
            throw new IllegalArgumentException("keyId 不能为空");
        }
        return llmKeyRepository.findByIdAndUserId(keyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("LLM Key 不存在: " + keyId));
    }

    private void validateModelInput(LlmModel model) {
        if (model == null) {
            throw new IllegalArgumentException("模型参数不能为空");
        }
        if (!StringUtils.hasText(model.getName())) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (!StringUtils.hasText(model.getModelType())) {
            model.setModelType("TEXT");
        }
        if (model.getEnabled() == null) {
            model.setEnabled(true);
        }
        if (model.getWeight() == null || model.getWeight() <= 0) {
            model.setWeight(1);
        }
        if (model.getPriority() == null) {
            model.setPriority(0);
        }
    }
}
