package com.zwiki.mcpserver.tool;

import com.zwiki.mcpserver.entity.Catalogue;
import com.zwiki.mcpserver.entity.Task;
import com.zwiki.mcpserver.repository.CatalogueRepository;
import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectReportTool {

    private final CatalogueRepository catalogueRepository;
    private final TaskRepository taskRepository;

    public String get_project_report(String task_id) {

        log.info("MCP tool get_project_report called, taskId={}", task_id);

        if (!StringUtils.hasText(task_id)) {
            return "错误：task_id 不能为空。请先使用 list_projects 工具获取项目的 taskId。";
        }

        Task task = taskRepository.findFirstByTaskId(task_id.trim()).orElse(null);
        if (task == null) {
            return "错误：未找到 taskId 为 \"" + task_id + "\" 的项目。";
        }

        List<Catalogue> catalogues = catalogueRepository.findByTaskId(task_id.trim());
        if (catalogues.isEmpty()) {
            return "该项目尚未生成分析报告。请确认项目已完成分析。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(safe(task.getProjectName())).append(" — 项目分析报告\n\n");

        // 基本信息
        sb.append("## 基本信息\n");
        sb.append("- 项目名称: ").append(safe(task.getProjectName())).append("\n");
        if (StringUtils.hasText(task.getProjectUrl())) {
            sb.append("- 仓库地址: ").append(task.getProjectUrl()).append("\n");
        }
        sb.append("- 来源类型: ").append(safe(task.getSourceType())).append("\n");
        sb.append("- 文档节点数: ").append(catalogues.size()).append("\n");
        if (task.getCreateTime() != null) {
            sb.append("- 分析时间: ").append(task.getCreateTime()).append("\n");
        }
        sb.append("\n");

        // 项目概述
        String overview = resolveOverview(catalogues);
        if (StringUtils.hasText(overview)) {
            sb.append("## 项目概述\n");
            sb.append(trimContent(overview, 1500)).append("\n\n");
        }

        // 技术栈
        String techStack = detectTechStack(buildCombinedContent(catalogues));
        if (StringUtils.hasText(techStack)) {
            sb.append("## 技术栈\n");
            sb.append(techStack).append("\n\n");
        }

        // 核心模块
        sb.append("## 核心模块\n");
        List<Catalogue> topLevel = catalogues.stream()
                .filter(c -> !StringUtils.hasText(c.getParentCatalogueId()))
                .sorted(Comparator.comparing(Catalogue::getId))
                .collect(Collectors.toList());

        Map<String, List<Catalogue>> childrenMap = catalogues.stream()
                .filter(c -> StringUtils.hasText(c.getParentCatalogueId()))
                .collect(Collectors.groupingBy(Catalogue::getParentCatalogueId));

        for (Catalogue parent : topLevel) {
            String title = StringUtils.hasText(parent.getTitle()) ? parent.getTitle() : parent.getName();
            sb.append("### ").append(safe(title)).append("\n");

            if (StringUtils.hasText(parent.getContent())) {
                String summary = trimContent(parent.getContent(), 300);
                sb.append(summary).append("\n");
            }

            List<Catalogue> children = childrenMap.getOrDefault(parent.getCatalogueId(), List.of());
            if (!children.isEmpty()) {
                sb.append("子章节: ");
                sb.append(children.stream()
                        .map(c -> StringUtils.hasText(c.getTitle()) ? c.getTitle() : c.getName())
                        .filter(StringUtils::hasText)
                        .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String resolveOverview(List<Catalogue> catalogues) {
        Optional<Catalogue> overview = catalogues.stream()
                .filter(c -> containsAny(c.getTitle(), "概述", "简介", "Overview")
                        || containsAny(c.getName(), "概述", "简介", "Overview"))
                .findFirst();
        if (overview.isPresent() && StringUtils.hasText(overview.get().getContent())) {
            return overview.get().getContent();
        }
        // Fallback: first catalogue with content
        return catalogues.stream()
                .filter(c -> StringUtils.hasText(c.getContent()))
                .map(Catalogue::getContent)
                .findFirst()
                .orElse("");
    }

    private String buildCombinedContent(List<Catalogue> catalogues) {
        StringBuilder sb = new StringBuilder();
        for (Catalogue cat : catalogues) {
            if (StringUtils.hasText(cat.getTitle())) {
                sb.append(cat.getTitle()).append(" ");
            }
            if (StringUtils.hasText(cat.getContent())) {
                sb.append(cat.getContent()).append(" ");
            }
        }
        return sb.toString();
    }

    private String detectTechStack(String content) {
        if (!StringUtils.hasText(content)) return "Java";
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> candidates = List.of(
                "Spring Boot", "Spring Cloud", "MyBatis", "JPA", "Hibernate",
                "MySQL", "PostgreSQL", "Redis", "MongoDB", "Nacos",
                "Vue", "React", "Angular", "Node.js", "Docker", "Nginx",
                "Kafka", "RabbitMQ", "Elasticsearch", "MinIO"
        );
        Set<String> found = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                found.add(candidate);
            }
        }
        return found.isEmpty() ? "Java" : String.join(", ", found);
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) return false;
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword)) return true;
        }
        return false;
    }

    private String trimContent(String content, int maxChars) {
        if (!StringUtils.hasText(content)) return "";
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "...";
    }

    private String safe(String s) {
        return StringUtils.hasText(s) ? s : "(未知)";
    }
}
