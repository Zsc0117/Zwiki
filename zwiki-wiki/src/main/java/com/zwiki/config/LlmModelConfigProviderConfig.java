package com.zwiki.config;

import com.zwiki.util.AuthUtil;
import com.zwiki.service.auth.TokenCryptoService;
import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.llm.provider.ModelConfigProvider;
import com.zwiki.repository.entity.LlmKey;
import com.zwiki.repository.entity.LlmModel;
import com.zwiki.repository.dao.LlmKeyRepository;
import com.zwiki.repository.dao.LlmModelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author pai
 * @description: LLM模型配置提供者配置
 * @date 2026/1/22
 */
@Configuration
public class LlmModelConfigProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmModelConfigProviderConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    public ModelConfigProvider dbModelConfigProvider(
            LlmModelRepository llmModelRepository,
            LlmKeyRepository llmKeyRepository,
            TokenCryptoService tokenCryptoService) {
        return new ModelConfigProvider() {

            private String resolveUserId() {
                String resolved = AuthUtil.getCurrentUserId();
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
                return null;
            }

            @Override
            public List<LlmBalancerProperties.ModelConfig> getModels() {
                String userId = resolveUserId();
                if (userId == null || userId.isBlank()) {
                    return List.of();
                }

                List<LlmKey> enabledKeys = llmKeyRepository.findByUserIdAndEnabledTrueOrderByCreateTimeDesc(userId);
                if (enabledKeys == null || enabledKeys.isEmpty()) {
                    return List.of();
                }

                Set<Long> keyIds = enabledKeys.stream().map(LlmKey::getId).collect(Collectors.toSet());
                List<LlmModel> models = llmModelRepository.findByLlmKeyIdInAndEnabledTrueOrderByPriorityDescWeightDesc(new ArrayList<>(keyIds));

                Map<Long, LlmKey> keyMap = new LinkedHashMap<>();
                for (LlmKey key : enabledKeys) {
                    keyMap.put(key.getId(), key);
                }

                List<LlmBalancerProperties.ModelConfig> configs = new ArrayList<>();
                for (LlmModel m : models) {
                    if (m == null || m.getLlmKeyId() == null) {
                        continue;
                    }
                    LlmKey key = keyMap.get(m.getLlmKeyId());
                    if (key == null) {
                        continue;
                    }
                    LlmBalancerProperties.ModelConfig c = new LlmBalancerProperties.ModelConfig();
                    c.setLlmKeyId(m.getLlmKeyId());
                    c.setName(m.getName());
                    c.setDisplayName(m.getDisplayName());
                    c.setProvider(key.getProvider());
                    c.setApiKey(tokenCryptoService.decryptIfEncrypted(key.getApiKeyCipher()));
                    c.setBaseUrl(key.getBaseUrl());
                    c.setApiVersion(key.getApiVersion());
                    c.setExtraHeaders(parseExtraHeaders(key.getExtraHeaders()));
                    c.setModelType(m.getModelType() != null ? m.getModelType() : "TEXT");
                    c.setCapabilities(m.getCapabilities());
                    c.setEnabled(Boolean.TRUE.equals(m.getEnabled()));
                    c.setWeight(m.getWeight() != null ? m.getWeight() : 1);
                    c.setPriority(m.getPriority() != null ? m.getPriority() : 0);
                    configs.add(c);
                }

                configs.sort(
                        Comparator.comparingInt(LlmBalancerProperties.ModelConfig::getPriority).reversed()
                                .thenComparingInt(LlmBalancerProperties.ModelConfig::getWeight).reversed()
                                .thenComparing(LlmBalancerProperties.ModelConfig::getName)
                );

                return configs;
            }
        };
    }

    private static Map<String, String> parseExtraHeaders(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse extraHeaders JSON: {}", e.getMessage());
            return null;
        }
    }
}

