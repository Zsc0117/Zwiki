package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.domain.enums.TaskStatusEnum;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.service.CatalogueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务补偿控制器
 * 用于重试失败的文档生成任务
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/task-compensation")
@RequiredArgsConstructor
public class TaskCompensationController {

    private final TaskRepository taskMapper;
    private final CatalogueService catalogueService;

    /**
     * 获取用户的失败任务列表
     */
    @GetMapping("/failed-tasks")
    public ResultVo<List<Map<String, Object>>> getFailedTasks() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "请先登录");
        }

        List<Task> failedTasks = taskMapper.findByUserIdAndStatusInOrderByUpdateTimeDesc(
                userId, List.of(TaskStatusEnum.FAILED, TaskStatusEnum.IN_PROGRESS));
        
        List<Map<String, Object>> result = failedTasks.stream().map(task -> {
            Map<String, Object> taskMap = new HashMap<>();
            taskMap.put("taskId", task.getTaskId());
            taskMap.put("projectName", task.getProjectName());
            taskMap.put("projectUrl", task.getProjectUrl());
            taskMap.put("status", task.getStatus());
            taskMap.put("failReason", task.getFailReason());
            taskMap.put("createTime", task.getCreateTime());
            taskMap.put("updateTime", task.getUpdateTime());
            
            // 获取该任务的未完成目录数
            List<Catalogue> incompleteCatalogues = catalogueService.getIncompleteCatalogues(task.getTaskId());
            taskMap.put("incompleteCatalogueCount", incompleteCatalogues.size());
            
            return taskMap;
        })
        .filter(taskMap -> (Integer) taskMap.get("incompleteCatalogueCount") > 0)
        .collect(Collectors.toList());

        return ResultVo.success(result);
    }

    /**
     * 获取任务的未完成目录列表
     */
    @GetMapping("/incomplete-catalogues/{taskId}")
    public ResultVo<List<Map<String, Object>>> getIncompleteCatalogues(@PathVariable("taskId") String taskId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "请先登录");
        }

        // 验证任务属于当前用户
        Task task = taskMapper.findFirstByTaskId(taskId).orElse(null);
        if (task == null) {
            return ResultVo.error(404, "任务不存在");
        }
        if (!userId.equals(task.getUserId())) {
            return ResultVo.error(403, "无权访问该任务");
        }

        List<Catalogue> incompleteCatalogues = catalogueService.getIncompleteCatalogues(taskId);
        
        List<Map<String, Object>> result = incompleteCatalogues.stream().map(catalogue -> {
            Map<String, Object> catalogueMap = new HashMap<>();
            catalogueMap.put("catalogueId", catalogue.getCatalogueId());
            catalogueMap.put("name", catalogue.getName());
            catalogueMap.put("title", catalogue.getTitle());
            catalogueMap.put("status", catalogue.getStatus());
            catalogueMap.put("failReason", catalogue.getFailReason());
            catalogueMap.put("createTime", catalogue.getCreateTime());
            catalogueMap.put("updateTime", catalogue.getUpdateTime());
            return catalogueMap;
        }).collect(Collectors.toList());

        return ResultVo.success(result);
    }

    /**
     * 重试失败的目录
     */
    @PostMapping("/retry/{taskId}")
    public ResultVo<Map<String, Object>> retryIncompleteCatalogues(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) Map<String, Object> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "请先登录");
        }

        // 验证任务属于当前用户
        Task task = taskMapper.findFirstByTaskId(taskId).orElse(null);
        if (task == null) {
            return ResultVo.error(404, "任务不存在");
        }
        if (!userId.equals(task.getUserId())) {
            return ResultVo.error(403, "无权访问该任务");
        }

        // 解析要重试的目录ID列表（可选）
        List<String> catalogueIds = null;
        if (body != null && body.containsKey("catalogueIds")) {
            Object ids = body.get("catalogueIds");
            if (ids instanceof List) {
                catalogueIds = ((List<?>) ids).stream()
                        .filter(id -> id instanceof String)
                        .map(id -> (String) id)
                        .collect(Collectors.toList());
            }
        }

        try {
            int retryCount = catalogueService.retryIncompleteCatalogues(taskId, catalogueIds);
            
            // 如果有任务被重试，更新主任务状态为进行中
            if (retryCount > 0) {
                task.setStatus(TaskStatusEnum.IN_PROGRESS);
                task.setFailReason(null);
                task.setUpdateTime(java.time.LocalDateTime.now());
                taskMapper.save(task);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("retryCount", retryCount);
            result.put("message", retryCount > 0 ? "已重新提交 " + retryCount + " 个文档生成任务" : "没有需要重试的任务");
            
            return ResultVo.success(result);
        } catch (IllegalStateException e) {
            return ResultVo.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("重试任务失败: taskId={}", taskId, e);
            return ResultVo.error(500, "重试失败: " + e.getMessage());
        }
    }
}
