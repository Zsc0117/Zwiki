package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.dao.CatalogueRepository;
import com.zwiki.repository.dao.TaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author pai
 * @description: 全局搜索控制器
 * @date 2026/1/27
 */
@RestController
@RequestMapping("/api/search")
public class GlobalSearchController {

    @Autowired
    private TaskRepository taskMapper;

    @Autowired
    private CatalogueRepository catalogueMapper;

    @GetMapping("/global")
    public ResultVo<Map<String, Object>> globalSearch(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {

        if (!StringUtils.hasText(query) || query.trim().length() < 2) {
            return ResultVo.error("搜索关键词至少需要2个字符");
        }

        String keyword = query.trim().toLowerCase();
        int maxResults = Math.min(limit, 50);

        // Search tasks by project name or URL
        List<Task> allTasks = taskMapper.findAll();
        List<Map<String, Object>> taskResults = allTasks.stream()
                .filter(t -> {
                    String name = t.getProjectName() != null ? t.getProjectName().toLowerCase() : "";
                    String url = t.getProjectUrl() != null ? t.getProjectUrl().toLowerCase() : "";
                    return name.contains(keyword) || url.contains(keyword);
                })
                .limit(maxResults)
                .map(t -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("type", "task");
                    item.put("taskId", t.getTaskId());
                    item.put("projectName", t.getProjectName());
                    item.put("projectUrl", t.getProjectUrl());
                    item.put("status", t.getStatus());
                    item.put("updateTime", t.getUpdateTime());
                    return item;
                })
                .collect(Collectors.toList());

        // Search catalogue content (wiki documents)
        List<Map<String, Object>> docResults = new ArrayList<>();
        List<Catalogue> allCatalogues = catalogueMapper.findAll();

        for (Catalogue cat : allCatalogues) {
            if (docResults.size() >= maxResults) break;

            String title = cat.getTitle() != null ? cat.getTitle().toLowerCase() : "";
            String name = cat.getName() != null ? cat.getName().toLowerCase() : "";
            String content = cat.getContent() != null ? cat.getContent().toLowerCase() : "";

            boolean titleMatch = title.contains(keyword) || name.contains(keyword);
            boolean contentMatch = content.contains(keyword);

            if (titleMatch || contentMatch) {
                Map<String, Object> item = new HashMap<>();
                item.put("type", "document");
                item.put("taskId", cat.getTaskId());
                item.put("catalogueId", cat.getCatalogueId());
                item.put("title", cat.getTitle() != null ? cat.getTitle() : cat.getName());

                // Extract snippet around the keyword match
                if (contentMatch && cat.getContent() != null) {
                    item.put("snippet", extractSnippet(cat.getContent(), keyword, 120));
                } else {
                    String raw = cat.getContent();
                    item.put("snippet", raw != null && raw.length() > 120 ? raw.substring(0, 120) + "..." : raw);
                }

                // Resolve project name from task
                String projectName = allTasks.stream()
                        .filter(t -> t.getTaskId().equals(cat.getTaskId()))
                        .map(Task::getProjectName)
                        .findFirst()
                        .orElse(null);
                item.put("projectName", projectName);

                docResults.add(item);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tasks", taskResults);
        result.put("documents", docResults);
        result.put("taskCount", taskResults.size());
        result.put("documentCount", docResults.size());
        return ResultVo.success(result);
    }

    private String extractSnippet(String content, String keyword, int contextLen) {
        String lower = content.toLowerCase();
        int idx = lower.indexOf(keyword);
        if (idx < 0) {
            return content.length() > contextLen ? content.substring(0, contextLen) + "..." : content;
        }
        int start = Math.max(0, idx - contextLen / 2);
        int end = Math.min(content.length(), idx + keyword.length() + contextLen / 2);
        String snippet = content.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) snippet = "..." + snippet;
        if (end < content.length()) snippet = snippet + "...";
        return snippet;
    }
}
