package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.Diagram;
import com.zwiki.repository.dao.DiagramRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author pai
 * @description: 图表管理控制器
 * @date 2026/1/28
 */
@Slf4j
@RestController
@RequestMapping("/api/diagram")
@RequiredArgsConstructor
public class DiagramController {

    private final DiagramRepository diagramRepository;

    @GetMapping("/list")
    public ResultVo<List<Map<String, Object>>> list(@RequestParam("taskId") String taskId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        List<Diagram> diagrams = diagramRepository.findByTaskIdAndUserIdOrderByCreatedAtDesc(taskId, userId);
        List<Map<String, Object>> result = diagrams.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("diagramId", d.getDiagramId());
            item.put("taskId", d.getTaskId());
            item.put("userId", d.getUserId());
            item.put("name", d.getName());
            item.put("svgData", d.getSvgData());
            item.put("sourceUrl", d.getSourceUrl());
            item.put("createdAt", d.getCreatedAt());
            item.put("updatedAt", d.getUpdatedAt());
            return item;
        }).collect(Collectors.toList());

        return ResultVo.success(result);
    }

    @GetMapping("/{diagramId}")
    public ResultVo<Diagram> getDetail(@PathVariable("diagramId") String diagramId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        return diagramRepository.findByDiagramId(diagramId)
                .filter(d -> userId.equals(d.getUserId()))
                .map(ResultVo::success)
                .orElse(ResultVo.error("图表不存在"));
    }

    @PostMapping
    public ResultVo<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        String taskId = body.get("taskId");
        String name = body.get("name");
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(name)) {
            return ResultVo.error("taskId和name不能为空");
        }

        String sourceUrl = null;
        if (body.containsKey("sourceUrl") && StringUtils.hasText(body.get("sourceUrl"))) {
            sourceUrl = body.get("sourceUrl").trim();
        }

        // AI 自动保存场景去重：同一用户 + 同一任务 + 同一 sourceUrl 只保留一条
        if (StringUtils.hasText(sourceUrl)) {
            Optional<Diagram> existing = diagramRepository
                    .findFirstByTaskIdAndUserIdAndSourceUrlOrderByCreatedAtDesc(taskId, userId, sourceUrl);
            if (existing.isPresent()) {
                Diagram hit = existing.get();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("diagramId", hit.getDiagramId());
                result.put("name", hit.getName());
                return ResultVo.success(result);
            }
        }

        String diagramId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();

        Diagram diagram = Diagram.builder()
                .diagramId(diagramId)
                .taskId(taskId)
                .userId(userId)
                .name(name.trim())
                .xmlData(body.getOrDefault("xmlData", ""))
                .svgData(body.getOrDefault("svgData", ""))
                .sourceUrl(sourceUrl)
                .createdAt(now)
                .updatedAt(now)
                .build();
        diagramRepository.save(diagram);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diagramId", diagramId);
        result.put("name", diagram.getName());
        log.info("Created diagram {} for task {} by user {}", diagramId, taskId, userId);
        return ResultVo.success(result);
    }

    @PutMapping("/{diagramId}")
    public ResultVo<String> update(@PathVariable("diagramId") String diagramId, @RequestBody Map<String, String> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        Diagram diagram = diagramRepository.findByDiagramId(diagramId).orElse(null);
        if (diagram == null) return ResultVo.error("图表不存在");
        if (!userId.equals(diagram.getUserId())) return ResultVo.error(403, "无权限操作该图表");

        if (body.containsKey("name") && StringUtils.hasText(body.get("name"))) {
            diagram.setName(body.get("name").trim());
        }
        if (body.containsKey("xmlData")) {
            diagram.setXmlData(body.get("xmlData"));
        }
        if (body.containsKey("svgData")) {
            diagram.setSvgData(body.get("svgData"));
        }
        if (body.containsKey("sourceUrl")) {
            String sourceUrl = body.get("sourceUrl");
            diagram.setSourceUrl(StringUtils.hasText(sourceUrl) ? sourceUrl.trim() : null);
        }
        diagram.setUpdatedAt(LocalDateTime.now());
        diagramRepository.save(diagram);

        log.info("Updated diagram {} by user {}", diagramId, userId);
        return ResultVo.success("更新成功");
    }

    @DeleteMapping("/{diagramId}")
    @Transactional
    public ResultVo<String> delete(@PathVariable("diagramId") String diagramId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        Diagram diagram = diagramRepository.findByDiagramId(diagramId).orElse(null);
        if (diagram == null) return ResultVo.error("图表不存在");
        if (!userId.equals(diagram.getUserId())) return ResultVo.error(403, "无权限操作该图表");

        diagramRepository.deleteByDiagramId(diagramId);
        log.info("Deleted diagram {} by user {}", diagramId, userId);
        return ResultVo.success("删除成功");
    }
}
