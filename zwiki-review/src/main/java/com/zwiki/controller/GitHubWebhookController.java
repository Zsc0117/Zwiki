package com.zwiki.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.service.GitHubConfigLookupService;
import com.zwiki.service.GithubWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@RestController
@RequestMapping("/api/webhook")
public class GitHubWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    @Autowired
    private GithubWebhookService webhookService;

    @Autowired
    private GitHubConfigLookupService configLookupService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.github.webhook.secret:}")
    private String globalWebhookSecret;

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {
        
        logger.info("Received GitHub webhook: eventType={}", eventType);
        
        // 签名验证: 先尝试从payload提取repo名，查找per-user secret；否则用全局secret
        String effectiveSecret = resolveWebhookSecret(payload);
        
        if (effectiveSecret != null && !effectiveSecret.isEmpty()) {
            if (!verifySignature(payload, signature, effectiveSecret)) {
                logger.warn("Webhook signature verification failed");
                return ResponseEntity.status(401).body("Invalid signature");
            }
            logger.debug("Webhook signature verified");
        } else {
            logger.debug("No webhook secret configured, skipping signature verification");
        }
        
        if (!"pull_request".equals(eventType)) {
            logger.debug("Ignoring non-PR event: {}", eventType);
            return ResponseEntity.ok("Event ignored");
        }
        
        try {
            webhookService.handlePullRequestEvent(payload);
            return ResponseEntity.ok("Webhook processed");
        } catch (Exception e) {
            logger.error("Error processing webhook", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * 从payload中提取仓库全名，查找per-user webhook secret，否则回退到全局secret
     */
    private String resolveWebhookSecret(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode repoNode = root.path("repository").path("full_name");
            if (!repoNode.isMissingNode() && repoNode.isTextual()) {
                String repoFullName = repoNode.asText();
                GitHubConfigLookupService.RepoReviewConfig config = configLookupService.findConfigByRepo(repoFullName);
                if (config != null && config.hasWebhookSecret()) {
                    logger.debug("Using per-user webhook secret for repo={}", repoFullName);
                    return config.webhookSecret();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract repo from payload for secret lookup: {}", e.getMessage());
        }
        return globalWebhookSecret;
    }

    /**
     * 验证GitHub Webhook签名 (HMAC-SHA256)
     * GitHub发送的签名格式: sha256=<hex_digest>
     */
    private boolean verifySignature(String payload, String signatureHeader, String secret) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            logger.warn("Missing or malformed X-Hub-Signature-256 header");
            return false;
        }
        
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            String expectedSignature = SIGNATURE_PREFIX + bytesToHex(hash);
            
            // 常量时间比较，防止时序攻击
            return constantTimeEquals(expectedSignature, signatureHeader);
            
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Webhook signature verification error", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
