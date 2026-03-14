package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.common.result.ResultVo;
import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.llm.service.ModelHealthRepository;
import com.zwiki.repository.entity.LlmKey;
import com.zwiki.repository.entity.LlmModel;
import com.zwiki.service.LlmConfigService;
import com.zwiki.service.LlmKeyService;
import com.zwiki.service.LlmModelService;
import com.zwiki.service.LlmUsageStatsService;
import com.zwiki.domain.dto.LlmBalancerConfigDto;
import com.zwiki.domain.dto.LlmKeyDto;
import com.zwiki.domain.dto.LlmModelDto;
import com.zwiki.domain.dto.LlmProviderStatsDto;
import com.zwiki.domain.dto.LlmUsageTrendDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final LlmBalancerProperties properties;
    private final ModelHealthRepository healthRepository;
    private final LlmConfigService llmConfigService;
    private final LlmKeyService llmKeyService;
    private final LlmModelService llmModelService;
    private final LlmUsageStatsService usageStatsService;

    @GetMapping("/keys")
    public ResultVo<List<LlmKeyDto>> listKeys() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(llmKeyService.listByUserId(userId));
    }

    @PostMapping("/keys")
    public ResultVo<LlmKey> createKey(@RequestBody Map<String, String> body) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }

            LlmKey key = LlmKey.builder()
                    .name(body.get("name"))
                    .provider(body.get("provider"))
                    .description(body.get("description"))
                    .baseUrl(body.get("baseUrl"))
                    .apiVersion(body.get("apiVersion"))
                    .extraHeaders(body.get("extraHeaders"))
                    .enabled(body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled")))
                    .build();
            String apiKey = body.get("apiKey");

            LlmKey saved = llmKeyService.createKey(userId, key, apiKey);
            log.info("Created llm key: id={}, provider={}, user={}", saved.getId(), saved.getProvider(), userId);
            return ResultVo.success(saved);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @PutMapping("/keys/{keyId}")
    public ResultVo<LlmKey> updateKey(@PathVariable("keyId") Long keyId, @RequestBody Map<String, String> body) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }

            LlmKey patch = LlmKey.builder()
                    .name(body.get("name"))
                    .provider(body.get("provider"))
                    .description(body.get("description"))
                    .baseUrl(body.get("baseUrl"))
                    .apiVersion(body.get("apiVersion"))
                    .extraHeaders(body.get("extraHeaders"))
                    .enabled(body.containsKey("enabled") ? Boolean.parseBoolean(body.get("enabled")) : null)
                    .build();
            String apiKey = body.get("apiKey");

            LlmKey updated = llmKeyService.updateKey(keyId, userId, patch, apiKey);
            log.info("Updated llm key: id={}, user={}", keyId, userId);
            return ResultVo.success(updated);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @DeleteMapping("/keys/{keyId}")
    public ResultVo<Void> deleteKey(@PathVariable("keyId") Long keyId) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }
            llmKeyService.deleteKey(keyId, userId);
            log.info("Deleted llm key: id={}, user={}", keyId, userId);
            return ResultVo.success(null);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/keys/{keyId}/models")
    public ResultVo<List<LlmModelDto>> listModelsByKey(@PathVariable("keyId") Long keyId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(llmModelService.listModelsByKeyId(userId, keyId));
    }

    @PostMapping("/keys/{keyId}/models")
    public ResultVo<LlmModel> createModel(@PathVariable("keyId") Long keyId, @RequestBody LlmModel model) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }
            LlmModel saved = llmModelService.createModel(userId, keyId, model);
            log.info("Created model under key: keyId={}, model={}, user={}", keyId, saved.getName(), userId);
            return ResultVo.success(saved);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @PutMapping("/keys/{keyId}/models/{id}")
    public ResultVo<LlmModel> updateModel(@PathVariable("keyId") Long keyId,
                                          @PathVariable("id") Long id,
                                          @RequestBody LlmModel model) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }
            LlmModel updated = llmModelService.updateModel(userId, keyId, id, model);
            log.info("Updated model under key: keyId={}, modelId={}, user={}", keyId, id, userId);
            return ResultVo.success(updated);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @DeleteMapping("/keys/{keyId}/models/{id}")
    public ResultVo<Void> deleteModel(@PathVariable("keyId") Long keyId,
                                      @PathVariable("id") Long id) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }
            llmModelService.deleteModel(userId, keyId, id);
            log.info("Deleted model under key: keyId={}, modelId={}, user={}", keyId, id, userId);
            return ResultVo.success(null);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/keys/{keyId}/models/{id}/toggle")
    public ResultVo<Void> toggleModelEnabled(@PathVariable("keyId") Long keyId,
                                             @PathVariable("id") Long id,
                                             @RequestParam("enabled") boolean enabled) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null || userId.isBlank()) {
                return ResultVo.error(401, "未登录");
            }
            llmModelService.toggleModelEnabled(userId, keyId, id, enabled);
            log.info("Toggled model enabled: keyId={}, modelId={}, enabled={}, user={}", keyId, id, enabled, userId);
            return ResultVo.success(null);
        } catch (IllegalArgumentException e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/models")
    public ResultVo<List<LlmModelDto>> listAllModels() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(llmModelService.listAllEnabledModels(userId));
    }

    @GetMapping("/stats/all")
    public ResultVo<Map<String, Object>> getAggregatedModelStats() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(llmModelService.getAggregatedStats(userId));
    }

    @PostMapping("/models/{modelName}/mark-healthy")
    public ResultVo<Void> markModelHealthy(@PathVariable("modelName") String modelName) {
        healthRepository.markHealthy(modelName);
        log.info("Manually marked model {} as healthy", modelName);
        return ResultVo.success(null);
    }

    @PostMapping("/models/refresh")
    public ResultVo<Void> refreshModels() {
        llmModelService.refreshModelsFromDatabase();
        log.info("Refreshed models from database");
        return ResultVo.success(null);
    }

    @GetMapping("/balancer/config")
    public ResultVo<LlmBalancerConfigDto> getBalancerConfig() {
        LlmBalancerConfigDto config = LlmBalancerConfigDto.builder()
                .enabled(properties.isEnabled())
                .strategy(properties.getStrategy())
                .maxAttemptsPerRequest(properties.getMaxAttemptsPerRequest())
                .unhealthyCooldownSeconds(properties.getUnhealthyCooldownSeconds())
                .allowFallbackOnExplicitModel(properties.isAllowFallbackOnExplicitModel())
                .build();
        return ResultVo.success(config);
    }

    @PutMapping("/balancer/config")
    public ResultVo<Void> updateBalancerConfig(@RequestBody LlmBalancerConfigDto configDto) {
        llmConfigService.updateBalancerConfig(configDto);
        log.info("Updated balancer config: {}", configDto);
        return ResultVo.success(null);
    }

    @GetMapping("/stats/keys")
    public ResultVo<List<LlmKeyDto>> getKeyStats() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(usageStatsService.getKeyStatsList(userId));
    }

    @GetMapping("/stats/providers")
    public ResultVo<List<LlmProviderStatsDto>> getProviderStats() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(usageStatsService.getProviderStats(userId));
    }

    @GetMapping("/stats/trend")
    public ResultVo<LlmUsageTrendDto> getUsageTrend(
            @RequestParam("dimensionType") String dimensionType,
            @RequestParam(value = "dimensionId", required = false) String dimensionId,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            return ResultVo.error(401, "未登录");
        }

        // Default to last 30 days
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(30);
        }

        LlmUsageTrendDto trend;
        if (dimensionId == null || dimensionId.isBlank() || "ALL".equalsIgnoreCase(dimensionId)) {
            trend = usageStatsService.getAggregatedTrend(userId, dimensionType, startDate, endDate);
        } else {
            trend = usageStatsService.getTrend(userId, dimensionType, dimensionId, startDate, endDate);
        }
        return ResultVo.success(trend);
    }
}
