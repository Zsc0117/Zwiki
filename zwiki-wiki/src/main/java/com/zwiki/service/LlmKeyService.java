package com.zwiki.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.service.auth.TokenCryptoService;
import com.zwiki.domain.dto.LlmKeyDto;
import com.zwiki.repository.dao.LlmKeyRepository;
import com.zwiki.repository.dao.LlmModelRepository;
import com.zwiki.repository.entity.LlmKey;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LlmKeyService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmKeyRepository llmKeyRepository;
    private final LlmModelRepository llmModelRepository;
    private final TokenCryptoService tokenCryptoService;

    @Value("${spring.ai.openai.api-key:}")
    private String defaultOpenAiApiKey;

    @PostConstruct
    @Transactional
    public void migrateLegacyModelsToDefaultKeys() {
        if (!StringUtils.hasText(defaultOpenAiApiKey)) {
            return;
        }
        List<Map<String, Object>> pairs = llmModelRepository.findLegacyUserProviderPairsWithoutKey();
        for (Map<String, Object> row : pairs) {
            String userId = row.get("userId") != null ? String.valueOf(row.get("userId")) : null;
            String provider = row.get("provider") != null ? String.valueOf(row.get("provider")) : "dashscope";
            if (!StringUtils.hasText(userId)) {
                continue;
            }

            String keyName = "default-" + provider;
            LlmKey key = llmKeyRepository.findByUserIdOrderByCreateTimeDesc(userId).stream()
                    .filter(k -> keyName.equals(k.getName()))
                    .findFirst()
                    .orElseGet(() -> {
                        LlmKey created = LlmKey.builder()
                                .userId(userId)
                                .name(keyName)
                                .provider(normalizeProvider(provider))
                                .apiKeyCipher(tokenCryptoService.encryptIfEnabled(defaultOpenAiApiKey.trim()))
                                .apiKeyMasked(maskApiKey(defaultOpenAiApiKey.trim()))
                                .enabled(true)
                                .description("Auto-migrated default key")
                                .build();
                        return llmKeyRepository.save(created);
                    });

            llmModelRepository.bindLegacyModelsToKey(userId, provider, key.getId());
        }
    }

    public List<LlmKeyDto> listByUserId(String userId) {
        return llmKeyRepository.findByUserIdOrderByCreateTimeDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public LlmKey getByIdAndUserId(Long id, String userId) {
        return llmKeyRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("LLM Key 不存在: " + id));
    }

    @Transactional
    public LlmKey createKey(String userId, LlmKey key, String apiKeyPlaintext) {
        validateCreateInput(userId, key, apiKeyPlaintext);
        if (llmKeyRepository.existsByUserIdAndName(userId, key.getName())) {
            throw new IllegalArgumentException("同名 LLM Key 已存在: " + key.getName());
        }

        key.setUserId(userId);
        key.setName(key.getName().trim());
        key.setProvider(normalizeProvider(key.getProvider()));
        key.setApiKeyCipher(tokenCryptoService.encryptIfEnabled(apiKeyPlaintext.trim()));
        key.setApiKeyMasked(maskApiKey(apiKeyPlaintext.trim()));
        key.setDescription(normalizeNullableText(key.getDescription()));
        key.setBaseUrl(normalizeNullableText(key.getBaseUrl()));
        key.setApiVersion(normalizeNullableText(key.getApiVersion()));
        key.setExtraHeaders(normalizeNullableText(key.getExtraHeaders()));
        validateExtraHeadersJsonObject(key.getExtraHeaders());
        if (key.getEnabled() == null) {
            key.setEnabled(true);
        }
        return llmKeyRepository.save(key);
    }

    @Transactional
    public LlmKey updateKey(Long id, String userId, LlmKey patch, String apiKeyPlaintext) {
        LlmKey existing = getByIdAndUserId(id, userId);

        if (StringUtils.hasText(patch.getName())
                && !patch.getName().equals(existing.getName())
                && llmKeyRepository.existsByUserIdAndNameAndIdNot(userId, patch.getName(), id)) {
            throw new IllegalArgumentException("同名 LLM Key 已存在: " + patch.getName());
        }

        if (StringUtils.hasText(patch.getName())) {
            existing.setName(patch.getName().trim());
        }
        if (StringUtils.hasText(patch.getProvider())) {
            existing.setProvider(normalizeProvider(patch.getProvider()));
        }
        if (patch.getEnabled() != null) {
            existing.setEnabled(patch.getEnabled());
        }
        if (patch.getDescription() != null) {
            existing.setDescription(patch.getDescription());
        }

        if (StringUtils.hasText(apiKeyPlaintext)) {
            String plain = apiKeyPlaintext.trim();
            existing.setApiKeyCipher(tokenCryptoService.encryptIfEnabled(plain));
            existing.setApiKeyMasked(maskApiKey(plain));
        }

        // 更新 baseUrl（允许设为空字符串来清除）
        if (patch.getBaseUrl() != null) {
            existing.setBaseUrl(normalizeNullableText(patch.getBaseUrl()));
        }
        // 更新 apiVersion
        if (patch.getApiVersion() != null) {
            existing.setApiVersion(normalizeNullableText(patch.getApiVersion()));
        }
        // 更新 extraHeaders
        if (patch.getExtraHeaders() != null) {
            String normalizedExtraHeaders = normalizeNullableText(patch.getExtraHeaders());
            validateExtraHeadersJsonObject(normalizedExtraHeaders);
            existing.setExtraHeaders(normalizedExtraHeaders);
        }

        return llmKeyRepository.save(existing);
    }

    @Transactional
    public void deleteKey(Long id, String userId) {
        LlmKey existing = getByIdAndUserId(id, userId);
        long modelCount = llmModelRepository.countByLlmKeyId(existing.getId());
        if (modelCount > 0) {
            throw new IllegalArgumentException("该 Key 下仍有模型，无法删除");
        }
        llmKeyRepository.delete(existing);
    }

    public String decryptApiKey(LlmKey key) {
        if (key == null || !StringUtils.hasText(key.getApiKeyCipher())) {
            return null;
        }
        return tokenCryptoService.decryptIfEncrypted(key.getApiKeyCipher());
    }

    private void validateCreateInput(String userId, LlmKey key, String apiKeyPlaintext) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (key == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (!StringUtils.hasText(key.getName())) {
            throw new IllegalArgumentException("Key 名称不能为空");
        }
        if (!StringUtils.hasText(key.getProvider())) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        if (!StringUtils.hasText(apiKeyPlaintext)) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "dashscope" : provider.trim().toLowerCase();
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void validateExtraHeadersJsonObject(String extraHeaders) {
        if (!StringUtils.hasText(extraHeaders)) {
            return;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(extraHeaders);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("extraHeaders 必须是 JSON 对象");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("extraHeaders 必须是合法 JSON 对象");
        }
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "****";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private LlmKeyDto toDto(LlmKey key) {
        return LlmKeyDto.builder()
                .id(key.getId())
                .userId(key.getUserId())
                .name(key.getName())
                .provider(key.getProvider())
                .enabled(key.getEnabled())
                .description(key.getDescription())
                .apiKeyMasked(key.getApiKeyMasked())
                .baseUrl(key.getBaseUrl())
                .apiVersion(key.getApiVersion())
                .extraHeaders(key.getExtraHeaders())
                .callCount(key.getCallCount())
                .errorCount(key.getErrorCount())
                .inputTokens(key.getInputTokens())
                .outputTokens(key.getOutputTokens())
                .totalTokens(key.getTotalTokens())
                .lastUsedTime(key.getLastUsedTime())
                .createTime(key.getCreateTime())
                .updateTime(key.getUpdateTime())
                .build();
    }
}
