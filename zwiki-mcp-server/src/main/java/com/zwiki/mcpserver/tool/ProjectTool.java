package com.zwiki.mcpserver.tool;

import com.zwiki.mcpserver.entity.Task;
import com.zwiki.mcpserver.repository.CatalogueRepository;
import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTool {

    private final TaskRepository taskRepository;
    private final CatalogueRepository catalogueRepository;

    public String list_projects(String keyword) {

        log.info("MCP tool list_projects called, keyword={}", keyword);

        List<Task> tasks;
        if (StringUtils.hasText(keyword)) {
            tasks = taskRepository.findByProjectNameContainingIgnoreCase(keyword.trim());
        } else {
            tasks = taskRepository.findAll();
        }

        if (tasks.isEmpty()) {
            return "当前没有已分析的项目。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共找到 ").append(tasks.size()).append(" 个项目：\n\n");

        for (Task task : tasks) {
            sb.append("- **").append(safe(task.getProjectName())).append("**\n");
            sb.append("  - taskId: `").append(task.getTaskId()).append("`\n");
            if (StringUtils.hasText(task.getProjectUrl())) {
                sb.append("  - 仓库地址: ").append(task.getProjectUrl()).append("\n");
            }
            sb.append("  - 状态: ").append(statusText(task.getStatus())).append("\n");
            long docCount = catalogueRepository.countByTaskId(task.getTaskId());
            sb.append("  - Wiki文档数: ").append(docCount).append("\n");
            if (task.getUpdateTime() != null) {
                sb.append("  - 更新时间: ").append(task.getUpdateTime()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String statusText(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "pending" -> "进行中";
            case "completed" -> "已完成";
            case "failed" -> "失败";
            default -> status;
        };
    }

    private String safe(String s) {
        return StringUtils.hasText(s) ? s : "(未命名)";
    }
}
