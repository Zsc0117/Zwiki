package com.zwiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.dao.CatalogueRepository;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.entity.ThesisAnalysisReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ThesisAnalysisReportService {

    private static final int MAX_OVERVIEW_CHARS = 1600;
    private static final int MAX_REPORT_CHARS = 30000;

    private final CatalogueRepository catalogueRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ThesisAnalysisReport buildReport(String taskId) {
        List<Catalogue> catalogues = catalogueRepository.findByTaskId(taskId);
        Task task = taskRepository.findFirstByTaskId(taskId).orElse(null);
        String projectName = task != null && StringUtils.hasText(task.getProjectName())
                ? task.getProjectName()
                : (taskId != null ? taskId : "项目");

        String combinedContent = buildCombinedContent(catalogues);
        String overview = resolveOverview(catalogues, combinedContent);
        String techStack = detectTechStack(combinedContent);
        String dependencyAnalysis = extractSection(combinedContent, List.of("依赖", "dependency"));
        String designPatterns = extractSection(combinedContent, List.of("设计模式", "pattern"));
        String qualityAnalysis = extractSection(combinedContent, List.of("质量", "性能", "优化"));
        String businessFlows = extractSection(combinedContent, List.of("流程", "业务", "flow"));
        String coreModulesAnalysis = buildModulesAnalysis(catalogues);
        String comprehensiveReport = trimContent(combinedContent, MAX_REPORT_CHARS);

        return ThesisAnalysisReport.builder()
                .taskId(taskId)
                .projectName(projectName)
                .projectOverview(overview)
                .coreModulesAnalysis(coreModulesAnalysis)
                .dependencyAnalysis(dependencyAnalysis)
                .designPatterns(designPatterns)
                .qualityAnalysis(qualityAnalysis)
                .businessFlows(businessFlows)
                .techStack(techStack)
                .comprehensiveReport(comprehensiveReport)
                .deepReadingReport("")
                .build();
    }

    public String collectCatalogueContent(String taskId) {
        List<Catalogue> catalogues = catalogueRepository.findByTaskId(taskId);
        return buildCombinedContent(catalogues);
    }

    public List<String> collectDependentFiles(String taskId) {
        List<Catalogue> catalogues = catalogueRepository.findByTaskId(taskId);
        Set<String> files = new LinkedHashSet<>();
        for (Catalogue catalogue : catalogues) {
            if (!StringUtils.hasText(catalogue.getDependentFile())) {
                continue;
            }
            try {
                List<String> deps = objectMapper.readValue(catalogue.getDependentFile(), new TypeReference<List<String>>() {});
                for (String dep : deps) {
                    if (StringUtils.hasText(dep)) {
                        files.add(dep.replace('\\', '/'));
                    }
                }
            } catch (Exception e) {
                log.debug("解析dependent_file失败: {}", e.getMessage());
            }
        }
        return new ArrayList<>(files);
    }

    public Map<String, List<String>> summarizeTopLevelModules(String taskId) {
        List<Catalogue> catalogues = catalogueRepository.findByTaskId(taskId);
        Map<String, List<Catalogue>> childrenMap = catalogues.stream()
                .filter(c -> StringUtils.hasText(c.getParentCatalogueId()))
                .collect(Collectors.groupingBy(Catalogue::getParentCatalogueId));

        List<Catalogue> topLevel = catalogues.stream()
                .filter(c -> !StringUtils.hasText(c.getParentCatalogueId()))
                .sorted(Comparator.comparing(Catalogue::getId))
                .collect(Collectors.toList());

        Map<String, List<String>> modules = new LinkedHashMap<>();
        for (Catalogue parent : topLevel) {
            String name = StringUtils.hasText(parent.getTitle()) ? parent.getTitle() : parent.getName();
            if (!StringUtils.hasText(name)) {
                continue;
            }
            List<Catalogue> children = childrenMap.getOrDefault(parent.getCatalogueId(), List.of());
            List<String> childNames = children.stream()
                    .map(child -> StringUtils.hasText(child.getTitle()) ? child.getTitle() : child.getName())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            modules.put(name, childNames);
        }
        return modules;
    }

    private String buildCombinedContent(List<Catalogue> catalogues) {
        StringBuilder sb = new StringBuilder();
        for (Catalogue catalogue : catalogues) {
            if (StringUtils.hasText(catalogue.getTitle())) {
                sb.append("## ").append(catalogue.getTitle()).append("\n");
            } else if (StringUtils.hasText(catalogue.getName())) {
                sb.append("## ").append(catalogue.getName()).append("\n");
            }
            if (StringUtils.hasText(catalogue.getContent())) {
                sb.append(catalogue.getContent()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String resolveOverview(List<Catalogue> catalogues, String fallback) {
        Optional<Catalogue> overview = catalogues.stream()
                .filter(c -> containsAny(c.getTitle(), "概述", "简介", "Overview")
                        || containsAny(c.getName(), "概述", "简介", "Overview"))
                .findFirst();
        if (overview.isPresent() && StringUtils.hasText(overview.get().getContent())) {
            return trimContent(overview.get().getContent(), MAX_OVERVIEW_CHARS);
        }
        return trimContent(fallback, MAX_OVERVIEW_CHARS);
    }

    private String buildModulesAnalysis(List<Catalogue> catalogues) {
        Map<String, List<Catalogue>> childrenMap = catalogues.stream()
                .filter(c -> StringUtils.hasText(c.getParentCatalogueId()))
                .collect(Collectors.groupingBy(Catalogue::getParentCatalogueId));
        List<Catalogue> topLevel = catalogues.stream()
                .filter(c -> !StringUtils.hasText(c.getParentCatalogueId()))
                .sorted(Comparator.comparing(Catalogue::getId))
                .collect(Collectors.toList());

        List<Map<String, Object>> modules = new ArrayList<>();
        for (Catalogue parent : topLevel) {
            String moduleName = StringUtils.hasText(parent.getTitle()) ? parent.getTitle() : parent.getName();
            if (!StringUtils.hasText(moduleName)) {
                continue;
            }
            Map<String, Object> module = new HashMap<>();
            module.put("name", moduleName);
            module.put("description", trimContent(parent.getContent(), 400));
            List<String> subModules = childrenMap.getOrDefault(parent.getCatalogueId(), List.of())
                    .stream()
                    .map(child -> StringUtils.hasText(child.getTitle()) ? child.getTitle() : child.getName())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
            module.put("subModules", subModules);
            modules.add(module);
        }

        if (modules.isEmpty()) {
            return "暂无模块信息";
        }

        try {
            return objectMapper.writeValueAsString(modules);
        } catch (Exception e) {
            return modules.toString();
        }
    }

    private String extractSection(String content, List<String> keywords) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            int idx = lower.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                int end = Math.min(content.length(), idx + 800);
                return content.substring(idx, end);
            }
        }
        return "";
    }

    private String detectTechStack(String content) {
        if (!StringUtils.hasText(content)) {
            return "Java";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> candidates = List.of(
                "Spring Boot", "Spring Cloud", "MyBatis", "JPA", "Hibernate",
                "MySQL", "PostgreSQL", "Redis", "MongoDB",
                "Vue", "React", "Angular", "Node.js", "Docker", "Nginx", "Kafka"
        );
        Set<String> found = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                found.add(candidate);
            }
        }
        if (found.isEmpty()) {
            return "Java";
        }
        return String.join(", ", found);
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String trimContent(String content, int maxChars) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "...";
    }
}
