package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.DocChange;
import com.zwiki.repository.dao.DocChangeRepository;
import com.zwiki.service.DocDiffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pai
 * @description: 文档变更历史查询接口
 * @date 2026/2/1
 */
@Slf4j
@RestController
@RequestMapping("/api/wiki/doc-change")
@RequiredArgsConstructor
public class DocChangeController {

    private final DocChangeRepository docChangeRepository;
    private final DocDiffService docDiffService;

    /**
     * 获取任务的变更历史（分页）
     */
    @GetMapping("/list/{taskId}")
    public ResultVo<Page<DocChange>> listChanges(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocChange> changes = docChangeRepository.findByTaskIdOrderByCreatedAtDesc(taskId, pageable);
        return ResultVo.success(changes);
    }

    /**
     * 获取指定章节的变更历史
     */
    @GetMapping("/catalogue/{taskId}/{catalogueId}")
    public ResultVo<List<DocChange>> listCatalogueChanges(
            @PathVariable("taskId") String taskId,
            @PathVariable("catalogueId") String catalogueId) {

        List<DocChange> changes = docChangeRepository.findByTaskIdAndCatalogueIdOrderByCreatedAtDesc(taskId, catalogueId);
        return ResultVo.success(changes);
    }

    /**
     * 获取任务的变更统计
     */
    @GetMapping("/stats/{taskId}")
    public ResultVo<Map<String, Object>> getChangeStats(@PathVariable("taskId") String taskId) {
        Map<String, Object> stats = docDiffService.getTaskChangeStats(taskId);
        return ResultVo.success(stats);
    }

    /**
     * 获取指定时间范围内的变更
     */
    @GetMapping("/range/{taskId}")
    public ResultVo<List<DocChange>> getChangesByTimeRange(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(name = "end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        List<DocChange> changes = docDiffService.getChangesByTimeRange(taskId, start, end);
        return ResultVo.success(changes);
    }

    /**
     * 手动触发变更对比（用于测试或手动重分析后）
     */
    @PostMapping("/compare/{taskId}")
    public ResultVo<Map<String, Object>> manualCompare(@PathVariable("taskId") String taskId) {
        log.info("手动触发文档变更对比: taskId={}", taskId);
        List<DocChange> changes = docDiffService.compareAndRecordTaskChanges(
            taskId, DocChange.TriggerSource.MANUAL, "manual"
        );

        Map<String, Object> result = new HashMap<>();
        result.put("changes", changes);
        result.put("count", changes.size());

        return ResultVo.success(result);
    }

    /**
     * 获取单个变更详情
     */
    @GetMapping("/detail/{changeId}")
    public ResultVo<DocChange> getChangeDetail(@PathVariable("changeId") Long changeId) {
        return docChangeRepository.findById(changeId)
            .map(ResultVo::success)
            .orElse(ResultVo.error("变更记录不存在"));
    }

    /**
     * 清理任务的变更历史
     */
    @DeleteMapping("/clear/{taskId}")
    public ResultVo<Void> clearChanges(@PathVariable("taskId") String taskId) {
        log.info("清理任务变更历史: taskId={}", taskId);
        docDiffService.clearTaskChanges(taskId);
        return ResultVo.success(null);
    }
}
