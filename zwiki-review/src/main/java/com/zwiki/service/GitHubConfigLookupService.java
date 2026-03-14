package com.zwiki.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 从共享数据库读取用户的GitHub审查配置
 * Review服务无需Spring Security，仅读取配置用于webhook处理
 * 包含Token解密逻辑（与Wiki服务的TokenCryptoService兼容）
 */
@Service
public class GitHubConfigLookupService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubConfigLookupService.class);
    private static final String ENC_PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretKey secretKey;

    public GitHubConfigLookupService(@Value("${zwiki.auth.token-crypto-key:}") String key) {
        if (StringUtils.hasText(key)) {
            this.secretKey = buildKey(key.trim());
            logger.info("Token decryption enabled for GitHubConfigLookupService");
        } else {
            this.secretKey = null;
            logger.info("Token decryption disabled (zwiki.auth.token-crypto-key not configured)");
        }
    }

    /**
     * 根据仓库全名查找已启用的审查配置
     *
     * @return 配置信息Map，包含 user_id, webhook_secret, custom_pat；未找到返回null
     */
    public RepoReviewConfig findConfigByRepo(String repoFullName) {
        if (repoFullName == null || repoFullName.isBlank()) {
            return null;
        }

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT c.user_id, c.webhook_secret, c.custom_pat, a.access_token " +
                    "FROM zwiki_github_review_config c " +
                    "LEFT JOIN zwiki_oauth_account a ON c.user_id = a.user_id AND a.provider = 'github' " +
                    "WHERE c.repo_full_name = ? AND c.enabled = 1 " +
                    "LIMIT 1",
                    repoFullName
            );

            if (rows.isEmpty()) {
                logger.debug("No review config found for repo: {}", repoFullName);
                return null;
            }

            Map<String, Object> row = rows.get(0);
            String userId = (String) row.get("user_id");
            String webhookSecret = (String) row.get("webhook_secret");
            String customPat = (String) row.get("custom_pat");
            String oauthToken = (String) row.get("access_token");

            // 解密token（兼容Wiki服务的TokenCryptoService加密格式）
            String decryptedPat = decryptIfEncrypted(customPat);
            String decryptedOauth = decryptIfEncrypted(oauthToken);

            // 优先使用自定义PAT，否则使用OAuth token
            String effectiveToken = StringUtils.hasText(decryptedPat) ? decryptedPat : decryptedOauth;

            logger.info("Found review config for repo={}: userId={}, hasCustomPat={}, hasOAuthToken={}",
                    repoFullName, userId,
                    StringUtils.hasText(decryptedPat),
                    StringUtils.hasText(decryptedOauth));

            return new RepoReviewConfig(userId, webhookSecret, effectiveToken);

        } catch (Exception e) {
            logger.error("Failed to lookup review config for repo={}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    /**
     * 更新审查统计（last_review_at + review_count）
     */
    public void incrementReviewCount(String repoFullName) {
        try {
            jdbcTemplate.update(
                    "UPDATE zwiki_github_review_config SET last_review_at = NOW(), review_count = review_count + 1 " +
                    "WHERE repo_full_name = ? AND enabled = 1",
                    repoFullName
            );
        } catch (Exception e) {
            logger.warn("Failed to update review count for repo={}: {}", repoFullName, e.getMessage());
        }
    }

    /**
     * 解密token（兼容Wiki服务的TokenCryptoService enc:v1: 格式）
     */
    private String decryptIfEncrypted(String stored) {
        if (!StringUtils.hasText(stored)) {
            return stored;
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored;
        }
        if (secretKey == null) {
            logger.warn("Encrypted token found but zwiki.auth.token-crypto-key not configured, cannot decrypt");
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
            logger.error("Token decryption failed: {}", e.getMessage());
            return null;
        }
    }

    private static SecretKey buildKey(String key) {
        byte[] raw = tryBase64(key);
        if (raw == null || !(raw.length == 16 || raw.length == 24 || raw.length == 32)) {
            raw = sha256(key);
        }
        return new SecretKeySpec(raw, "AES");
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

    /**
     * 审查配置数据对象
     */
    public record RepoReviewConfig(
            String userId,
            String webhookSecret,
            String githubToken
    ) {
        public boolean hasWebhookSecret() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }

        public boolean hasGithubToken() {
            return githubToken != null && !githubToken.isBlank();
        }
    }
}
