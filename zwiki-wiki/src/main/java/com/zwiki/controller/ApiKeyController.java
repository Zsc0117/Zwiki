package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.ApiKey;
import com.zwiki.repository.dao.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author pai
 * @description: API密钥管理控制器
 * @date 2026/1/25
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/apikey")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyRepository apiKeyRepository;

    private static final int MAX_KEYS_PER_USER = 10;
    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @GetMapping("/list")
    public ResultVo<List<Map<String, Object>>> listKeys() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        List<ApiKey> keys = apiKeyRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> result = keys.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyId", k.getKeyId());
            m.put("name", k.getName());
            m.put("apiKeyMasked", maskApiKey(k.getApiKey()));
            m.put("createdAt", k.getCreatedAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            return m;
        }).collect(Collectors.toList());

        return ResultVo.success(result);
    }

    @PostMapping("/create")
    public ResultVo<Map<String, Object>> createKey(@RequestBody(required = false) Map<String, String> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        long count = apiKeyRepository.countByUserId(userId);
        if (count >= MAX_KEYS_PER_USER) {
            return ResultVo.error(400, "最多创建 " + MAX_KEYS_PER_USER + " 个 API Key");
        }

        String name = "默认项目";
        if (body != null && body.get("name") != null && !body.get("name").isBlank()) {
            name = body.get("name").trim();
            if (name.length() > 128) {
                name = name.substring(0, 128);
            }
        }

        String keyId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String secret = generateBase62(32);
        String fullApiKey = keyId + "." + secret;

        ApiKey apiKey = ApiKey.builder()
                .keyId(keyId)
                .apiKey(fullApiKey)
                .userId(userId)
                .name(name)
                .build();
        apiKeyRepository.save(apiKey);

        log.info("用户 {} 创建了 API Key: keyId={}, name={}", userId, keyId, name);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyId", keyId);
        result.put("name", name);
        result.put("apiKey", fullApiKey);
        result.put("createdAt", apiKey.getCreatedAt());

        return ResultVo.success(result);
    }

    @GetMapping("/{keyId}/reveal")
    public ResultVo<Map<String, String>> revealKey(@PathVariable("keyId") String keyId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        var existing = apiKeyRepository.findByKeyIdAndUserId(keyId, userId);
        if (existing.isEmpty()) {
            return ResultVo.error(404, "API Key 不存在");
        }

        return ResultVo.success(Map.of("apiKey", existing.get().getApiKey()));
    }

    @DeleteMapping("/{keyId}")
    public ResultVo<Void> deleteKey(@PathVariable("keyId") String keyId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        var existing = apiKeyRepository.findByKeyIdAndUserId(keyId, userId);
        if (existing.isEmpty()) {
            return ResultVo.error(404, "API Key 不存在");
        }

        apiKeyRepository.deleteByKeyIdAndUserId(keyId, userId);
        log.info("用户 {} 删除了 API Key: keyId={}", userId, keyId);

        return ResultVo.success();
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private String generateBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(SECURE_RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
