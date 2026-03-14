package com.zwiki.mcpserver.tool;

import com.zwiki.mcpserver.entity.Catalogue;
import com.zwiki.mcpserver.entity.Task;
import com.zwiki.mcpserver.repository.CatalogueRepository;
import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WikiDocumentTool {

    private final CatalogueRepository catalogueRepository;
    private final TaskRepository taskRepository;

    public String get_wiki_document(String task_id, String title, String catalogue_id) {

        log.info("MCP tool get_wiki_document called, taskId={}, title={}, catalogueId={}", task_id, title, catalogue_id);

        if (!StringUtils.hasText(task_id)) {
            return "错误：task_id 不能为空。请先使用 list_projects 工具获取项目的 taskId。";
        }

        Task task = taskRepository.findFirstByTaskId(task_id.trim()).orElse(null);
        if (task == null) {
            return "错误：未找到 taskId 为 \"" + task_id + "\" 的项目。";
        }

        // 精确获取某个文档节点
        if (StringUtils.hasText(catalogue_id)) {
            Catalogue cat = catalogueRepository.findFirstByCatalogueId(catalogue_id.trim()).orElse(null);
            if (cat == null) {
                return "错误：未找到 catalogueId 为 \"" + catalogue_id + "\" 的文档节点。";
            }
            return formatSingleDocument(cat, task.getProjectName());
        }

        List<Catalogue> catalogues = catalogueRepository.findByTaskId(task_id.trim());
        if (catalogues.isEmpty()) {
            return "该项目尚未生成 Wiki 文档。请确认项目已完成分析。";
        }

        // 按标题过滤
        if (StringUtils.hasText(title)) {
            String keyword = title.trim().toLowerCase();
            List<Catalogue> matched = catalogues.stream()
                    .filter(c -> {
                        String t = c.getTitle() != null ? c.getTitle().toLowerCase() : "";
                        String n = c.getName() != null ? c.getName().toLowerCase() : "";
                        return t.contains(keyword) || n.contains(keyword);
                    })
                    .collect(Collectors.toList());

            if (matched.isEmpty()) {
                // 返回目录结构供参考
                return "未找到标题包含 \"" + title + "\" 的文档。\n\n" + buildToc(catalogues, task.getProjectName());
            }

            if (matched.size() == 1) {
                return formatSingleDocument(matched.get(0), task.getProjectName());
            }

            // 多个匹配，返回列表
            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(matched.size()).append(" 个匹配的文档节点，请通过 catalogue_id 精确获取：\n\n");
            for (Catalogue cat : matched) {
                String docTitle = StringUtils.hasText(cat.getTitle()) ? cat.getTitle() : cat.getName();
                sb.append("- **").append(safe(docTitle)).append("** (catalogueId: `").append(cat.getCatalogueId()).append("`)\n");
            }
            return sb.toString();
        }

        // 无过滤条件：返回项目文档目录 + 顶层概述
        return buildToc(catalogues, task.getProjectName());
    }

    private String formatSingleDocument(Catalogue cat, String projectName) {
        StringBuilder sb = new StringBuilder();
        String docTitle = StringUtils.hasText(cat.getTitle()) ? cat.getTitle() : cat.getName();
        sb.append("# ").append(safe(docTitle)).append("\n\n");
        sb.append("- 项目: ").append(safe(projectName)).append("\n");
        sb.append("- catalogueId: `").append(cat.getCatalogueId()).append("`\n\n");

        if (StringUtils.hasText(cat.getContent())) {
            sb.append(cat.getContent());
        } else {
            sb.append("（该文档节点暂无内容）");
        }

        return sb.toString();
    }

    private String buildToc(List<Catalogue> catalogues, String projectName) {
        // 分为顶层和子节点
        List<Catalogue> topLevel = catalogues.stream()
                .filter(c -> !StringUtils.hasText(c.getParentCatalogueId()))
                .sorted(Comparator.comparing(Catalogue::getId))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(safe(projectName)).append(" — Wiki 文档目录\n\n");
        sb.append("共 ").append(catalogues.size()).append(" 个文档节点。使用 title 或 catalogue_id 参数获取具体章节内容。\n\n");

        for (Catalogue parent : topLevel) {
            String title = StringUtils.hasText(parent.getTitle()) ? parent.getTitle() : parent.getName();
            sb.append("## ").append(safe(title)).append("\n");
            sb.append("catalogueId: `").append(parent.getCatalogueId()).append("`\n");

            // 显示简要概述
            if (StringUtils.hasText(parent.getContent())) {
                String preview = parent.getContent().length() > 200
                        ? parent.getContent().substring(0, 200) + "..."
                        : parent.getContent();
                sb.append(preview).append("\n");
            }

            // 子节点
            List<Catalogue> children = catalogues.stream()
                    .filter(c -> parent.getCatalogueId().equals(c.getParentCatalogueId()))
                    .sorted(Comparator.comparing(Catalogue::getId))
                    .collect(Collectors.toList());

            for (Catalogue child : children) {
                String childTitle = StringUtils.hasText(child.getTitle()) ? child.getTitle() : child.getName();
                sb.append("  - ").append(safe(childTitle))
                        .append(" (`").append(child.getCatalogueId()).append("`)\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String safe(String s) {
        return StringUtils.hasText(s) ? s : "(无标题)";
    }
}
