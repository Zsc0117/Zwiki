package com.zwiki.service;
import com.zwiki.repository.dao.CatalogueRepository;
import com.zwiki.repository.dao.DocChangeRepository;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.DocChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author pai
 * @description: 文档对比与变更追踪服务，智能分析文档变更，生成章节级别的 Diff
 * @date 2026/1/28
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocDiffService {

    private final DocChangeRepository docChangeRepository;
    private final CatalogueRepository  catalogueRepository;

    /**
     * 计算内容哈希值（SHA-256）
     */
    public String computeHash(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // 取前16位即可
        } catch (NoSuchAlgorithmException e) {
            log.error("计算哈希失败", e);
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * 生成内容摘要（前500字符）
     */
    private String generateSummary(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int maxLen = 500;
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }

    /**
     * 生成统一格式的 Diff 文本
     */
    public String generateDiff(String beforeContent, String afterContent) {
        if (beforeContent == null) {
            beforeContent = "";
        }
        if (afterContent == null) {
            afterContent = "";
        }

        String[] beforeLines = beforeContent.split("\n");
        String[] afterLines = afterContent.split("\n");

        StringBuilder diff = new StringBuilder();
        diff.append("--- 变更前\n");
        diff.append("+++ 变更后\n");
        diff.append("\n");

        // 使用简单的行级 diff 算法
        int maxLines = Math.max(beforeLines.length, afterLines.length);
        int unchangedCount = 0;

        for (int i = 0; i < maxLines; i++) {
            String beforeLine = i < beforeLines.length ? beforeLines[i] : null;
            String afterLine = i < afterLines.length ? afterLines[i] : null;

            if (Objects.equals(beforeLine, afterLine)) {
                // 行未变更
                unchangedCount++;
                if (unchangedCount <= 3 || i >= maxLines - 3) {
                    diff.append(" ").append(beforeLine != null ? beforeLine : "").append("\n");
                } else if (unchangedCount == 4) {
                    diff.append("...\n");
                }
            } else {
                unchangedCount = 0;
                // 行有变更
                if (beforeLine != null) {
                    diff.append("-").append(beforeLine).append("\n");
                }
                if (afterLine != null) {
                    diff.append("+").append(afterLine).append("\n");
                }
            }
        }

        return diff.toString();
    }

    /**
     * 检测变更类型
     */
    public DocChange.ChangeType detectChangeType(String beforeContent, String afterContent) {
        boolean hasBefore = beforeContent != null && !beforeContent.isEmpty();
        boolean hasAfter = afterContent != null && !afterContent.isEmpty();

        if (!hasBefore && hasAfter) {
            return DocChange.ChangeType.ADD;
        } else if (hasBefore && !hasAfter) {
            return DocChange.ChangeType.DELETE;
        } else {
            return DocChange.ChangeType.MODIFY;
        }
    }

    /**
     * 记录章节变更
     */
    @Transactional
    public DocChange recordCatalogueChange(String taskId, String catalogueId,
                                          String beforeContent, String afterContent,
                                          DocChange.TriggerSource triggerSource, String triggeredBy) {
        try {
            DocChange.ChangeType changeType = detectChangeType(beforeContent, afterContent);

            // 如果内容相同，不记录变更
            if (changeType == DocChange.ChangeType.MODIFY &&
                Objects.equals(computeHash(beforeContent), computeHash(afterContent))) {
                log.debug("章节 {} 内容未变更，跳过记录", catalogueId);
                return null;
            }

            DocChange change = new DocChange();
            change.setTaskId(taskId);
            change.setCatalogueId(catalogueId);
            change.setChangeType(changeType.name());
            change.setTriggerSource(triggerSource.name());
            change.setBeforeContentHash(computeHash(beforeContent));
            change.setAfterContentHash(computeHash(afterContent));
            change.setBeforeContentSummary(generateSummary(beforeContent));
            change.setAfterContentSummary(generateSummary(afterContent));
            change.setDiffContent(generateDiff(beforeContent, afterContent));
            change.setTriggeredBy(triggeredBy);

            DocChange saved = docChangeRepository.save(change);
            log.info("记录章节变更: taskId={}, catalogueId={}, type={}", taskId, catalogueId, changeType);
            return saved;
        } catch (Exception e) {
            log.error("记录章节变更失败: taskId={}, catalogueId={}", taskId, catalogueId, e);
            return null;
        }
    }

    /**
     * 比较并记录整个任务的变更
     * 在重新分析完成后调用
     */
    @Transactional
    public List<DocChange> compareAndRecordTaskChanges(String taskId,
                                                       DocChange.TriggerSource triggerSource,
                                                       String triggeredBy) {
        log.info("开始对比任务文档变更: taskId={}", taskId);
        List<DocChange> changes = new ArrayList<>();

        try {
            // 获取所有章节
            List<Catalogue> catalogues = catalogueRepository.findByTaskId(taskId);

            for (Catalogue catalogue : catalogues) {
                // 查询该章节最近一次变更记录
                List<DocChange> latestChanges = docChangeRepository
                    .findByTaskIdAndCatalogueIdOrderByCreatedAtDesc(taskId, catalogue.getCatalogueId());

                String beforeContent = "";
                if (!latestChanges.isEmpty()) {
                    // 获取上次变更后的内容摘要
                    beforeContent = latestChanges.get(0).getAfterContentSummary();
                }

                String afterContent = catalogue.getContent();

                // 检测并记录变更
                DocChange change = recordCatalogueChange(
                    taskId, catalogue.getCatalogueId(),
                    beforeContent, afterContent,
                    triggerSource, triggeredBy
                );

                if (change != null) {
                    changes.add(change);
                }
            }

            log.info("任务 {} 共记录 {} 个章节变更", taskId, changes.size());
            return changes;
        } catch (Exception e) {
            log.error("对比任务文档变更失败: taskId={}", taskId, e);
            return changes;
        }
    }

    /**
     * 获取任务的变更历史统计
     */
    public Map<String, Object> getTaskChangeStats(String taskId) {
        Map<String, Object> stats = new HashMap<>();

        List<Object[]> counts = docChangeRepository.countChangesByType(taskId);
        Map<String, Long> typeCount = new HashMap<>();
        long total = 0;
        for (Object[] row : counts) {
            String type = (String) row[0];
            Long count = (Long) row[1];
            typeCount.put(type, count);
            total += count;
        }

        stats.put("totalChanges", total);
        stats.put("addCount", typeCount.getOrDefault("ADD", 0L));
        stats.put("modifyCount", typeCount.getOrDefault("MODIFY", 0L));
        stats.put("deleteCount", typeCount.getOrDefault("DELETE", 0L));

        // 最近变更时间
        List<DocChange> latest = docChangeRepository
            .findLatestChangesByTaskId(taskId, PageRequest.of(0, 1));
        if (!latest.isEmpty()) {
            stats.put("lastChangeAt", latest.get(0).getCreatedAt());
        }

        return stats;
    }

    /**
     * 获取指定时间范围内的变更
     */
    public List<DocChange> getChangesByTimeRange(String taskId, LocalDateTime start, LocalDateTime end) {
        return docChangeRepository.findChangesByTimeRange(taskId, start, end);
    }

    /**
     * 删除任务的变更记录（清理历史）
     */
    @Transactional
    public void clearTaskChanges(String taskId) {
        docChangeRepository.deleteByTaskId(taskId);
        log.info("已清理任务 {} 的变更历史", taskId);
    }
}
