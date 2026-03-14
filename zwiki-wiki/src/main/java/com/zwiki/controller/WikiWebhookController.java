package com.zwiki.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.entity.WikiWebhookConfig;
import com.zwiki.repository.dao.WikiWebhookConfigRepository;
import com.zwiki.service.DocDiffService;
import com.zwiki.service.TaskService;
import com.zwiki.repository.entity.DocChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * @author pai
 * @description: Wiki Webhook控制器，处理GitHub Push事件
 * @date 2026/2/2
 */
@RestController
@RequestMapping("/api/webhook/wiki")
public class WikiWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WikiWebhookController.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    @Autowired
    private WikiWebhookConfigRepository webhookConfigRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private DocDiffService docDiffService;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/push")
    @Transactional
    public ResponseEntity<String> handlePushEvent(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        logger.info("Received Wiki webhook: eventType={}", eventType);

        if (!"push".equals(eventType)) {
            logger.debug("Ignoring non-push event: {}", eventType);
            return ResponseEntity.ok("Event ignored, only push events are processed");
        }

        // 解析 payload 获取仓库信息
        String repoFullName;
        String ref;
        try {
            JsonNode root = objectMapper.readTree(payload);
            repoFullName = root.path("repository").path("full_name").asText(null);
            ref = root.path("ref").asText("");
            if (repoFullName == null || repoFullName.isEmpty()) {
                logger.warn("Push event missing repository.full_name");
                return ResponseEntity.badRequest().body("Missing repository info");
            }
        } catch (Exception e) {
            logger.error("Failed to parse push payload", e);
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        // 只处理默认分支（main/master）的 push，忽略 tag 推送
        if (!ref.startsWith("refs/heads/")) {
            logger.debug("Ignoring non-branch push: ref={}", ref);
            return ResponseEntity.ok("Non-branch push ignored");
        }

        // 查找该仓库的所有启用的 wiki webhook 配置
        List<WikiWebhookConfig> configs = webhookConfigRepository.findByRepoFullNameAndEnabledTrue(repoFullName);
        if (configs.isEmpty()) {
            logger.info("No enabled wiki webhook config found for repo={}", repoFullName);
            return ResponseEntity.ok("No wiki webhook configured for this repo");
        }

        int triggered = 0;
        for (WikiWebhookConfig config : configs) {
            // 签名验证
            String secret = config.getWebhookSecret();
            if (secret != null && !secret.isEmpty()) {
                if (!verifySignature(payload, signature, secret)) {
                    logger.warn("Signature verification failed for config id={}, repo={}", config.getId(), repoFullName);
                    continue;
                }
            }

            String taskId = config.getTaskId();
            logger.info("Triggering wiki re-analysis for taskId={}, repo={}, ref={}", taskId, repoFullName, ref);

            try {
                // 获取重新分析前的内容快照（用于对比）
                docDiffService.compareAndRecordTaskChanges(
                    taskId, DocChange.TriggerSource.WEBHOOK, "github-webhook"
                );
                
                taskService.reanalyze(taskId);
                webhookConfigRepository.incrementTriggerCount(config.getId());
                triggered++;
                logger.info("Wiki re-analysis triggered successfully for taskId={}", taskId);
            } catch (Exception e) {
                logger.error("Failed to trigger re-analysis for taskId={}: {}", taskId, e.getMessage());
            }
        }

        return ResponseEntity.ok("Triggered " + triggered + " wiki re-analysis(es)");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Wiki webhook OK");
    }

    private boolean verifySignature(String payload, String signatureHeader, String secret) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = SIGNATURE_PREFIX + bytesToHex(hash);
            return constantTimeEquals(expected, signatureHeader);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Signature verification error", e);
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
