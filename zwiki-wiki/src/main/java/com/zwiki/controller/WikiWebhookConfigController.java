package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.entity.WikiWebhookConfig;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.dao.WikiWebhookConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * @author pai
 * @description: Wiki Webhook配置控制器
 * @date 2026/1/29
 */
@RestController
@RequestMapping("/api/auth/wiki-webhook")
public class WikiWebhookConfigController {

    @Autowired
    private WikiWebhookConfigRepository webhookConfigRepository;

    @Autowired
    private TaskRepository taskMapper;

    @GetMapping
    public ResultVo<List<WikiWebhookConfig>> list() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");
        return ResultVo.success(webhookConfigRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @GetMapping("/by-task/{taskId}")
    public ResultVo<WikiWebhookConfig> getByTask(@PathVariable("taskId") String taskId) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");
        WikiWebhookConfig config = webhookConfigRepository.findByUserIdAndTaskId(userId, taskId).orElse(null);
        return ResultVo.success(config);
    }

    @PostMapping
    @Transactional
    public ResultVo<WikiWebhookConfig> create(@RequestBody Map<String, Object> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        String taskId = (String) body.get("taskId");
        if (!StringUtils.hasText(taskId)) return ResultVo.error("taskId 不能为空");

        // 检查是否已存在
        if (webhookConfigRepository.findByUserIdAndTaskId(userId, taskId).isPresent()) {
            return ResultVo.error("该任务已配置 Webhook");
        }

        // 从 task 获取 repo 信息
        Task task = taskMapper.findFirstByTaskId(taskId).orElse(null);
        if (task == null) return ResultVo.error("任务不存在");

        String repoFullName = extractRepoFullName(task.getProjectUrl());
        if (!StringUtils.hasText(repoFullName)) {
            return ResultVo.error("无法从项目URL解析仓库名，请确保是 GitHub 仓库");
        }

        String secret = (String) body.getOrDefault("webhookSecret", "");
        Boolean enabled = body.get("enabled") != null ? Boolean.parseBoolean(body.get("enabled").toString()) : true;

        WikiWebhookConfig config = WikiWebhookConfig.builder()
                .userId(userId)
                .taskId(taskId)
                .repoFullName(repoFullName)
                .webhookSecret(secret)
                .enabled(enabled)
                .build();
        webhookConfigRepository.save(config);
        return ResultVo.success(config);
    }

    @PutMapping("/{id}")
    public ResultVo<WikiWebhookConfig> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        WikiWebhookConfig config = webhookConfigRepository.findByIdAndUserId(id, userId).orElse(null);
        if (config == null) return ResultVo.error("配置不存在");

        if (body.containsKey("webhookSecret")) {
            config.setWebhookSecret((String) body.get("webhookSecret"));
        }
        if (body.containsKey("enabled")) {
            config.setEnabled(Boolean.parseBoolean(body.get("enabled").toString()));
        }
        webhookConfigRepository.save(config);
        return ResultVo.success(config);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResultVo<String> delete(@PathVariable("id") Long id) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) return ResultVo.error(401, "未登录");

        webhookConfigRepository.deleteByIdAndUserId(id, userId);
        return ResultVo.success("已删除");
    }

    /**
     * 从 GitHub URL 提取 owner/repo 格式的仓库全名
     */
    private String extractRepoFullName(String projectUrl) {
        if (!StringUtils.hasText(projectUrl)) return null;
        String url = projectUrl.trim();
        // https://github.com/owner/repo or https://github.com/owner/repo.git
        if (url.contains("github.com/")) {
            String path = url.substring(url.indexOf("github.com/") + "github.com/".length());
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String[] parts = path.split("/");
            if (parts.length >= 2) {
                return parts[0] + "/" + parts[1];
            }
        }
        return null;
    }
}
