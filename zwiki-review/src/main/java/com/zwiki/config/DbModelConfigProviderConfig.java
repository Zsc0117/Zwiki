package com.zwiki.config;

import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.llm.provider.ModelConfigProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Configuration
public class DbModelConfigProviderConfig {

    private static final String ENC_PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    @Bean
    public ModelConfigProvider reviewDbModelConfigProvider(
            JdbcTemplate jdbcTemplate,
            @Value("${zwiki.auth.token-crypto-key:}") String cryptoKey) {

        final SecretKey secretKey = buildKeyIfConfigured(cryptoKey);

        return () -> {
            String userId = resolveUserId();
            if (!StringUtils.hasText(userId)) {
                return List.of();
            }

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT m.llm_key_id, m.name, m.display_name, m.model_type, m.capabilities, m.enabled, m.weight, m.priority, " +
                            "k.provider, k.api_key_cipher " +
                            "FROM zwiki_llm_model m " +
                            "JOIN zwiki_llm_key k ON m.llm_key_id = k.id " +
                            "WHERE k.user_id = ? AND k.enabled = 1 AND m.enabled = 1 " +
                            "ORDER BY m.priority DESC, m.weight DESC",
                    userId
            );

            List<LlmBalancerProperties.ModelConfig> configs = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                LlmBalancerProperties.ModelConfig c = new LlmBalancerProperties.ModelConfig();
                c.setLlmKeyId(toLong(row.get("llm_key_id")));
                c.setName(toString(row.get("name")));
                c.setDisplayName(toString(row.get("display_name")));
                c.setProvider(toString(row.get("provider")));
                c.setApiKey(decryptIfEncrypted(toString(row.get("api_key_cipher")), secretKey));
                c.setModelType(defaultString(toString(row.get("model_type")), "TEXT"));
                c.setCapabilities(toString(row.get("capabilities")));
                c.setEnabled(toBoolean(row.get("enabled"), true));
                c.setWeight(toInt(row.get("weight"), 1));
                c.setPriority(toInt(row.get("priority"), 0));
                if (StringUtils.hasText(c.getName())) {
                    configs.add(c);
                }
            }

            configs.sort(
                    Comparator.comparingInt(LlmBalancerProperties.ModelConfig::getPriority).reversed()
                            .thenComparingInt(LlmBalancerProperties.ModelConfig::getWeight).reversed()
                            .thenComparing(LlmBalancerProperties.ModelConfig::getName)
            );

            return configs;
        };
    }

    private static String resolveUserId() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletRequestAttributes) {
                HttpServletRequest request = servletRequestAttributes.getRequest();
                if (request != null) {
                    String headerUserId = request.getHeader("X-User-Id");
                    if (StringUtils.hasText(headerUserId)) {
                        return headerUserId;
                    }
                    String paramUserId = request.getParameter("userId");
                    if (StringUtils.hasText(paramUserId)) {
                        return paramUserId;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static SecretKey buildKeyIfConfigured(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        byte[] raw = tryBase64(key.trim());
        if (raw == null || !(raw.length == 16 || raw.length == 24 || raw.length == 32)) {
            raw = sha256(key.trim());
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String decryptIfEncrypted(String stored, SecretKey secretKey) {
        if (!StringUtils.hasText(stored)) {
            return stored;
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored;
        }
        if (secretKey == null) {
            return null;
        }
        try {
            String b64 = stored.substring(ENC_PREFIX.length());
            byte[] payload = Base64.getDecoder().decode(b64);
            if (payload.length <= IV_LEN) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[payload.length - IV_LEN];
            System.arraycopy(payload, 0, iv, 0, IV_LEN);
            System.arraycopy(payload, IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] tryBase64(String key) {
        try {
            return Base64.getDecoder().decode(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean toBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return "1".equals(String.valueOf(value)) || Boolean.parseBoolean(String.valueOf(value));
    }
}
