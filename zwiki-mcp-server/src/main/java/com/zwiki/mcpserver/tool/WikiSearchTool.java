package com.zwiki.mcpserver.tool;

import com.zwiki.mcpserver.entity.Catalogue;
import com.zwiki.mcpserver.entity.Task;
import com.zwiki.mcpserver.repository.CatalogueRepository;
import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WikiSearchTool {

    private final CatalogueRepository catalogueRepository;
    private final TaskRepository taskRepository;

    @Value("${zwiki.mcp-server.max-search-results:20}")
    private int maxSearchResults;

    public String search_wiki(String query, String project_name) {

        log.info("MCP tool search_wiki called, query={}, project_name={}", query, project_name);

        if (!StringUtils.hasText(query) || query.trim().length() < 2) {
            return "搜索关键词至少需要2个字符。";
        }

        String keyword = query.trim();
        List<Catalogue> results;

        if (StringUtils.hasText(project_name)) {
            // 先找到匹配的 task
            List<Task> tasks = taskRepository.findByProjectNameContainingIgnoreCase(project_name.trim());
            if (tasks.isEmpty()) {
                return "未找到名为 \"" + project_name + "\" 的项目。请使用 list_projects 工具查看可用项目。";
            }
            // 在匹配的 task 中搜索
            StringBuilder sb = new StringBuilder();
            int totalFound = 0;
            for (Task task : tasks) {
                List<Catalogue> taskResults = catalogueRepository.searchByTaskIdAndKeyword(task.getTaskId(), keyword);
                for (Catalogue cat : taskResults) {
                    if (totalFound >= maxSearchResults) break;
                    appendResult(sb, cat, task.getProjectName());
                    totalFound++;
                }
                if (totalFound >= maxSearchResults) break;
            }
            if (totalFound == 0) {
                return "在项目 \"" + project_name + "\" 中未找到与 \"" + keyword + "\" 相关的文档。";
            }
            return "找到 " + totalFound + " 条匹配结果：\n\n" + sb;
        } else {
            results = catalogueRepository.searchByKeyword(keyword);
            if (results.isEmpty()) {
                return "未找到与 \"" + keyword + "\" 相关的文档。";
            }

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Catalogue cat : results) {
                if (count >= maxSearchResults) break;
                String projectName = resolveProjectName(cat.getTaskId());
                appendResult(sb, cat, projectName);
                count++;
            }
            return "找到 " + count + " 条匹配结果（共 " + results.size() + " 条）：\n\n" + sb;
        }
    }

    private void appendResult(StringBuilder sb, Catalogue cat, String projectName) {
        String title = StringUtils.hasText(cat.getTitle()) ? cat.getTitle() : cat.getName();
        sb.append("### ").append(safe(title)).append("\n");
        sb.append("- 项目: ").append(safe(projectName)).append("\n");
        sb.append("- taskId: `").append(cat.getTaskId()).append("`\n");
        sb.append("- catalogueId: `").append(cat.getCatalogueId()).append("`\n");

        if (StringUtils.hasText(cat.getContent())) {
            String snippet = cat.getContent().length() > 500
                    ? cat.getContent().substring(0, 500) + "..."
                    : cat.getContent();
            sb.append("- 内容预览:\n```\n").append(snippet).append("\n```\n");
        }
        sb.append("\n");
    }

    private String resolveProjectName(String taskId) {
        return taskRepository.findFirstByTaskId(taskId)
                .map(Task::getProjectName)
                .orElse("(未知项目)");
    }

    private String safe(String s) {
        return StringUtils.hasText(s) ? s : "(无标题)";
    }
}
