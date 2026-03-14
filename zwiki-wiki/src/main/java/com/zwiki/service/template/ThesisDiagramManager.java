package com.zwiki.service.template;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 论文图表管理器
 * 管理论文中图表的插入、排版、编号和引用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThesisDiagramManager {

    private static final Map<String, String> DIAGRAM_TYPE_NAMES = new HashMap<>();

    static {
        DIAGRAM_TYPE_NAMES.put("systemArchitecture", "系统架构图");
        DIAGRAM_TYPE_NAMES.put("layerArchitecture", "分层架构图");
        DIAGRAM_TYPE_NAMES.put("moduleStructure", "模块结构图");
        DIAGRAM_TYPE_NAMES.put("classDiagram", "核心类图");
        DIAGRAM_TYPE_NAMES.put("useCaseDiagram", "系统用例图");
        DIAGRAM_TYPE_NAMES.put("sequenceDiagram", "系统交互时序图");
        DIAGRAM_TYPE_NAMES.put("erDiagram", "数据库E-R图");
        DIAGRAM_TYPE_NAMES.put("componentDiagram", "系统组件图");
        DIAGRAM_TYPE_NAMES.put("deploymentDiagram", "系统部署架构图");
        DIAGRAM_TYPE_NAMES.put("activityDiagram", "系统活动图");
        DIAGRAM_TYPE_NAMES.put("stateDiagram", "状态转换图");
        DIAGRAM_TYPE_NAMES.put("dataFlowDiagram", "数据流图");
        DIAGRAM_TYPE_NAMES.put("functionStructure", "功能结构图");
        DIAGRAM_TYPE_NAMES.put("businessFlow", "业务流程图");

        DIAGRAM_TYPE_NAMES.put("packageDependency", "包依赖图");
        DIAGRAM_TYPE_NAMES.put("componentInteraction", "组件交互图");
        DIAGRAM_TYPE_NAMES.put("moduleRelation", "模块关系图");
        DIAGRAM_TYPE_NAMES.put("stateMachine", "状态机图");
        DIAGRAM_TYPE_NAMES.put("designPatterns", "设计模式图");
        DIAGRAM_TYPE_NAMES.put("deploymentArchitecture", "部署架构图");
        DIAGRAM_TYPE_NAMES.put("monitoringArchitecture", "监控架构图");
        DIAGRAM_TYPE_NAMES.put("tableStructure", "表结构图");
    }

    private String insertModuleUseCaseDiagrams(String content,
                                              Map<String, String> moduleDiagrams,
                                              Map<String, List<DiagramInsertInfo>> chapterDiagrams) {

        Set<String> insertedKeys = new HashSet<>();
        for (List<DiagramInsertInfo> infos : chapterDiagrams.values()) {
            for (DiagramInsertInfo info : infos) {
                insertedKeys.add(info.getDiagramType());
            }
        }

        int maxFigureNumber = 0;
        for (List<DiagramInsertInfo> diagrams : chapterDiagrams.values()) {
            for (DiagramInsertInfo diagram : diagrams) {
                maxFigureNumber = Math.max(maxFigureNumber, diagram.getFigureNumber());
            }
        }
        int figureNumber = maxFigureNumber + 1;

        StringBuilder result = new StringBuilder(content);
        for (Map.Entry<String, String> entry : moduleDiagrams.entrySet()) {
            String diagramKey = entry.getKey();
            if (insertedKeys.contains(diagramKey)) {
                continue;
            }
            String moduleName = extractModuleNameFromUseCaseKey(diagramKey);
            String diagramPath = entry.getValue();

            DiagramInsertInfo insertInfo = new DiagramInsertInfo();
            insertInfo.setDiagramType(diagramKey);
            insertInfo.setDiagramPath(diagramPath);
            insertInfo.setFigureNumber(figureNumber);
            String caption = (moduleName == null || moduleName.isBlank()) ? "模块用例图" : (moduleName + "模块用例图");
            insertInfo.setCaption(caption);
            insertInfo.setDescription("该图展示了“" + (moduleName == null ? "模块" : moduleName) + "”的主要功能用例与用户角色关系。");

            int insertPos = findInsertPositionInUseCaseDesignSection(result.toString(), moduleName);
            if (insertPos <= 0) {
                int fallback = findChapterInsertPosition(result.toString(), "用例设计");
                insertPos = fallback;
            }
            if (insertPos > 0) {
                String diagramContent = "\n" + formatDiagramInsertion(insertInfo);
                result.insert(insertPos, diagramContent);
                insertedKeys.add(diagramKey);
                figureNumber++;
            }
        }

        return result.toString();
    }

    private int findInsertPositionInUseCaseDesignSection(String content, String moduleName) {
        if (content == null || content.isBlank()) {
            return -1;
        }

        String[] lines = content.split("\n");
        int pos = 0;
        boolean inUseCaseSection = false;
        int useCaseSectionStartPos = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String title = null;
            boolean isHeading = false;
            if (line.startsWith("# ")) {
                isHeading = true;
                title = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                isHeading = true;
                title = line.substring(3).trim();
            } else if (line.startsWith("### ")) {
                isHeading = true;
                title = line.substring(4).trim();
            }

            if (isHeading) {
                if (!inUseCaseSection) {
                    if (title != null && title.contains("用例设计")) {
                        inUseCaseSection = true;
                        useCaseSectionStartPos = pos + line.length() + 1;
                    }
                } else {
                    if (line.startsWith("# ")) {
                        break;
                    }
                }
            }

            pos += line.length() + 1;
        }

        if (!inUseCaseSection) {
            return -1;
        }

        if (moduleName == null || moduleName.isBlank()) {
            return useCaseSectionStartPos;
        }

        pos = 0;
        inUseCaseSection = false;
        int bestInsertPos = useCaseSectionStartPos;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ")) {
                String title;
                if (line.startsWith("# ")) {
                    title = line.substring(2).trim();
                } else if (line.startsWith("## ")) {
                    title = line.substring(3).trim();
                } else {
                    title = line.substring(4).trim();
                }

                if (!inUseCaseSection) {
                    if (title.contains("用例设计")) {
                        inUseCaseSection = true;
                    }
                } else {
                    if (line.startsWith("# ")) {
                        break;
                    }
                    if (title.contains(moduleName)) {
                        bestInsertPos = pos + line.length() + 1;
                    }
                }
            }
            pos += line.length() + 1;
        }

        return bestInsertPos;
    }

    private static final Map<String, List<String>> DIAGRAM_CHAPTER_MAPPING = new HashMap<>();

    private static final List<String> DIAGRAM_INSERT_ORDER = Arrays.asList(
            "systemArchitecture", "layerArchitecture", "functionStructure", "moduleStructure",
            "useCaseDiagram", "activityDiagram", "businessFlow", "classDiagram", 
            "sequenceDiagram", "stateDiagram", "dataFlowDiagram",
            "erDiagram", "componentDiagram", "deploymentDiagram"
    );

    static {
        DIAGRAM_CHAPTER_MAPPING.put("systemArchitecture", Arrays.asList(
                "系统架构", "架构概述", "总体架构", "技术架构", "系统概述"));
        DIAGRAM_CHAPTER_MAPPING.put("layerArchitecture", Arrays.asList(
                "架构设计", "分层设计", "系统设计", "总体设计", "分层架构"));
        DIAGRAM_CHAPTER_MAPPING.put("moduleStructure", Arrays.asList(
                "模块划分", "模块结构", "模块设计", "功能模块", "系统功能", "功能介绍"));
        DIAGRAM_CHAPTER_MAPPING.put("classDiagram", Arrays.asList(
                "类图", "类设计", "详细设计", "代码结构", "系统设计"));
        DIAGRAM_CHAPTER_MAPPING.put("useCaseDiagram", Arrays.asList(
                "用例设计", "用例图", "用例分析"));
        DIAGRAM_CHAPTER_MAPPING.put("sequenceDiagram", Arrays.asList(
                "时序图", "交互设计", "业务流程", "流程设计", "系统设计"));
        DIAGRAM_CHAPTER_MAPPING.put("erDiagram", Arrays.asList(
                "数据库设计", "E-R图", "数据模型", "数据表设计", "实体关系"));
        DIAGRAM_CHAPTER_MAPPING.put("componentDiagram", Arrays.asList(
                "组件", "组件设计", "组件图", "接口设计", "架构设计"));
        DIAGRAM_CHAPTER_MAPPING.put("deploymentDiagram", Arrays.asList(
                "部署", "部署设计", "部署架构", "运维", "环境部署", "系统部署"));

        DIAGRAM_CHAPTER_MAPPING.put("activityDiagram", Arrays.asList(
                "业务流程", "活动图", "流程设计", "系统流程", "处理流程", "操作流程"));
        DIAGRAM_CHAPTER_MAPPING.put("stateDiagram", Arrays.asList(
                "状态设计", "状态图", "状态转换", "状态流转", "对象状态", "生命周期"));
        DIAGRAM_CHAPTER_MAPPING.put("dataFlowDiagram", Arrays.asList(
                "数据流", "数据流程", "数据处理", "信息流", "系统设计"));
        DIAGRAM_CHAPTER_MAPPING.put("functionStructure", Arrays.asList(
                "功能结构", "功能设计", "功能模块", "系统功能", "功能划分", "功能概述"));
        DIAGRAM_CHAPTER_MAPPING.put("businessFlow", Arrays.asList(
                "业务流程", "业务设计", "业务分析", "流程设计", "核心业务"));

        DIAGRAM_CHAPTER_MAPPING.put("packageDependency", Arrays.asList("系统设计", "架构设计"));
        DIAGRAM_CHAPTER_MAPPING.put("componentInteraction", Arrays.asList("系统架构", "架构设计"));
        DIAGRAM_CHAPTER_MAPPING.put("moduleRelation", Arrays.asList("系统架构", "模块设计"));
        DIAGRAM_CHAPTER_MAPPING.put("stateMachine", Arrays.asList("详细设计", "状态设计"));
        DIAGRAM_CHAPTER_MAPPING.put("designPatterns", Arrays.asList("详细设计", "设计模式", "技术选型"));
        DIAGRAM_CHAPTER_MAPPING.put("deploymentArchitecture", Arrays.asList("系统部署", "部署方案"));
        DIAGRAM_CHAPTER_MAPPING.put("monitoringArchitecture", Arrays.asList("运维方案", "监控设计"));
        DIAGRAM_CHAPTER_MAPPING.put("tableStructure", Arrays.asList("数据库设计", "表结构设计"));
    }

    public String intelligentlyInsertDiagrams(String thesisContent, Map<String, String> availableDiagrams) {
        log.info("开始智能插入图表，可用图表数量: {}", availableDiagrams.size());

        Map<String, String> moduleUseCaseDiagrams = new LinkedHashMap<>();
        Map<String, String> baseDiagrams = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : availableDiagrams.entrySet()) {
            if (isModuleUseCaseDiagramKey(entry.getKey())) {
                moduleUseCaseDiagrams.put(entry.getKey(), entry.getValue());
            } else {
                baseDiagrams.put(entry.getKey(), entry.getValue());
            }
        }

        List<ChapterInfo> chapters = parseChapters(thesisContent);
        Set<String> preInsertedTypes = detectAlreadyInsertedDiagramTypes(thesisContent, baseDiagrams);
        Map<String, List<DiagramInsertInfo>> chapterDiagrams = matchDiagramsToChapters(chapters, baseDiagrams, preInsertedTypes);
        String result = insertDiagramsIntoContent(thesisContent, chapterDiagrams);
        result = insertRemainingDiagrams(result, baseDiagrams, chapterDiagrams, preInsertedTypes);

        if (!moduleUseCaseDiagrams.isEmpty()) {
            result = insertModuleUseCaseDiagrams(result, moduleUseCaseDiagrams, chapterDiagrams);
        }
        result = addDiagramIndex(result, chapterDiagrams);

        log.info("图表插入完成");
        return result;
    }

    private Set<String> detectAlreadyInsertedDiagramTypes(String content, Map<String, String> availableDiagrams) {
        Set<String> inserted = new HashSet<>();
        if (content == null || content.isBlank() || availableDiagrams == null || availableDiagrams.isEmpty()) {
            return inserted;
        }
        for (String diagramType : availableDiagrams.keySet()) {
            String existing = findDiagramInContent(content, diagramType);
            if (existing != null && !existing.isBlank()) {
                inserted.add(diagramType);
            }
        }
        return inserted;
    }

    private boolean isModuleUseCaseDiagramKey(String diagramKey) {
        return diagramKey != null && diagramKey.startsWith("useCaseDiagram_") && diagramKey.length() > "useCaseDiagram_".length();
    }

    private String extractModuleNameFromUseCaseKey(String diagramKey) {
        if (!isModuleUseCaseDiagramKey(diagramKey)) {
            return null;
        }
        return diagramKey.substring("useCaseDiagram_".length()).trim();
    }

    private String insertRemainingDiagrams(String content, Map<String, String> availableDiagrams,
                                          Map<String, List<DiagramInsertInfo>> chapterDiagrams,
                                          Set<String> preInsertedTypes) {
        Set<String> insertedTypes = new HashSet<>();
        for (List<DiagramInsertInfo> diagrams : chapterDiagrams.values()) {
            for (DiagramInsertInfo diagram : diagrams) {
                insertedTypes.add(diagram.getDiagramType());
            }
        }

        if (preInsertedTypes != null && !preInsertedTypes.isEmpty()) {
            insertedTypes.addAll(preInsertedTypes);
        }

        List<String> remainingDiagrams = new ArrayList<>();
        for (String diagramKey : DIAGRAM_INSERT_ORDER) {
            if (availableDiagrams.containsKey(diagramKey) && !insertedTypes.contains(diagramKey)) {
                remainingDiagrams.add(diagramKey);
            }
        }

        for (String diagramKey : availableDiagrams.keySet()) {
            if (!insertedTypes.contains(diagramKey) && !remainingDiagrams.contains(diagramKey)) {
                remainingDiagrams.add(diagramKey);
            }
        }

        if (remainingDiagrams.isEmpty()) {
            log.info("所有图表都已匹配到章节，无需回退插入");
            return content;
        }

        log.info("智能插入：尝试将 {} 个未匹配的图表插入到相关章节: {}", remainingDiagrams.size(), remainingDiagrams);

        int maxFigureNumber = 0;
        for (List<DiagramInsertInfo> diagrams : chapterDiagrams.values()) {
            for (DiagramInsertInfo diagram : diagrams) {
                maxFigureNumber = Math.max(maxFigureNumber, diagram.getFigureNumber());
            }
        }
        int figureNumber = maxFigureNumber + 1;

        StringBuilder result = new StringBuilder(content);
        
        for (String diagramKey : remainingDiagrams) {
            String diagramPath = availableDiagrams.get(diagramKey);
            String typeName = DIAGRAM_TYPE_NAMES.getOrDefault(diagramKey, "系统图");

            DiagramInsertInfo insertInfo = new DiagramInsertInfo();
            insertInfo.setDiagramType(diagramKey);
            insertInfo.setDiagramPath(diagramPath);
            insertInfo.setFigureNumber(figureNumber);
            insertInfo.setCaption(typeName);
            insertInfo.setDescription(generateDescription(diagramKey));

            String targetChapter = findBestMatchingChapter(result.toString(), diagramKey);
            if (targetChapter != null) {
                int insertPos = findChapterInsertPosition(result.toString(), targetChapter);
                if (insertPos > 0) {
                    String diagramContent = "\n" + formatDiagramInsertion(insertInfo);
                    result.insert(insertPos, diagramContent);
                    log.info("图表 {} 已插入到章节 {} 之后", typeName, targetChapter);
                    figureNumber++;
                }
            }
        }

        return result.toString();
    }

    private String findBestMatchingChapter(String content, String diagramKey) {
        Map<String, List<String>> fallbackMapping = new HashMap<>();
        fallbackMapping.put("useCaseDiagram", Arrays.asList("用例设计", "用例", "详细设计"));
        fallbackMapping.put("activityDiagram", Arrays.asList("流程", "业务", "设计"));
        fallbackMapping.put("stateDiagram", Arrays.asList("设计", "详细", "状态"));
        fallbackMapping.put("dataFlowDiagram", Arrays.asList("设计", "数据", "系统"));
        fallbackMapping.put("functionStructure", Arrays.asList("功能", "系统", "概述", "设计"));
        fallbackMapping.put("businessFlow", Arrays.asList("业务", "流程", "设计"));
        fallbackMapping.put("systemArchitecture", Arrays.asList("架构", "系统", "设计", "概述"));
        fallbackMapping.put("layerArchitecture", Arrays.asList("架构", "设计", "系统"));
        fallbackMapping.put("moduleStructure", Arrays.asList("模块", "功能", "设计"));
        fallbackMapping.put("classDiagram", Arrays.asList("设计", "详细", "类"));
        fallbackMapping.put("sequenceDiagram", Arrays.asList("设计", "交互", "流程"));
        fallbackMapping.put("erDiagram", Arrays.asList("数据库", "数据", "设计"));
        fallbackMapping.put("componentDiagram", Arrays.asList("组件", "架构", "设计"));
        fallbackMapping.put("deploymentDiagram", Arrays.asList("部署", "运维", "环境"));

        List<String> keywords;
        if (isModuleUseCaseDiagramKey(diagramKey)) {
            String moduleName = extractModuleNameFromUseCaseKey(diagramKey);
            keywords = new ArrayList<>();
            if (moduleName != null && !moduleName.isBlank()) {
                keywords.add(moduleName);
            }
            keywords.addAll(Arrays.asList("用例", "用例设计", "详细设计"));
        } else {
            keywords = fallbackMapping.getOrDefault(diagramKey, Arrays.asList("设计", "系统"));
        }
        
        String[] lines = content.split("\n");
        for (String keyword : keywords) {
            for (String line : lines) {
                if ((line.startsWith("# ") || line.startsWith("## ")) && line.contains(keyword)) {
                    return line.replaceAll("^#+\\s*", "").trim();
                }
            }
        }
        return null;
    }

    private int findChapterInsertPosition(String content, String chapterTitle) {
        String[] lines = content.split("\n");
        int currentPos = 0;
        boolean foundChapter = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if ((line.startsWith("# ") || line.startsWith("## ")) && 
                line.replaceAll("^#+\\s*", "").trim().equals(chapterTitle)) {
                foundChapter = true;
            } else if (foundChapter && (line.startsWith("# ") || line.startsWith("## "))) {
                return currentPos;
            }
            currentPos += line.length() + 1;
        }
        
        if (foundChapter) {
            return content.length();
        }
        return -1;
    }

    private Map<String, List<DiagramInsertInfo>> matchDiagramsToChapters(
            List<ChapterInfo> chapters,
            Map<String, String> availableDiagrams,
            Set<String> preInsertedTypes) {

        Map<String, List<DiagramInsertInfo>> result = new LinkedHashMap<>();
        int figureNumber = 1;
        Set<String> insertedTypes = new HashSet<>();
        if (preInsertedTypes != null) {
            insertedTypes.addAll(preInsertedTypes);
        }

        String preferredUseCaseChapter = null;
        for (ChapterInfo chapter : chapters) {
            if (chapter.getTitle() != null && chapter.getTitle().contains("用例设计")) {
                preferredUseCaseChapter = chapter.getTitle();
                break;
            }
        }

        for (ChapterInfo chapter : chapters) {
            List<DiagramInsertInfo> diagrams = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : DIAGRAM_CHAPTER_MAPPING.entrySet()) {
                String diagramType = entry.getKey();
                List<String> keywords = entry.getValue();

                if (insertedTypes.contains(diagramType)) {
                    continue;
                }

                if (!availableDiagrams.containsKey(diagramType)) {
                    continue;
                }

                if ("useCaseDiagram".equals(diagramType) && preferredUseCaseChapter != null
                        && !preferredUseCaseChapter.equals(chapter.getTitle())) {
                    continue;
                }

                for (String keyword : keywords) {
                    if (chapter.getTitle().contains(keyword)) {
                        DiagramInsertInfo insertInfo = new DiagramInsertInfo();
                        insertInfo.setDiagramType(diagramType);
                        insertInfo.setDiagramPath(availableDiagrams.get(diagramType));
                        insertInfo.setFigureNumber(figureNumber++);
                        insertInfo.setCaption(DIAGRAM_TYPE_NAMES.getOrDefault(diagramType, "图表"));
                        insertInfo.setDescription(generateDescription(diagramType));
                        diagrams.add(insertInfo);
                        insertedTypes.add(diagramType);
                        break;
                    }
                }
            }

            if (!diagrams.isEmpty()) {
                result.put(chapter.getTitle(), diagrams);
            }
        }

        return result;
    }

    private String insertDiagramsIntoContent(String content, Map<String, List<DiagramInsertInfo>> chapterDiagrams) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            result.append(line).append("\n");

            if (line.startsWith("# ") || line.startsWith("## ")) {
                String title = line.replaceAll("^#+\\s*", "").trim();

                List<DiagramInsertInfo> diagrams = chapterDiagrams.get(title);
                if (diagrams != null) {
                    for (DiagramInsertInfo diagram : diagrams) {
                        result.append("\n").append(formatDiagramInsertion(diagram)).append("\n");
                    }
                }
            }
        }

        return result.toString();
    }

    private String addDiagramIndex(String content, Map<String, List<DiagramInsertInfo>> chapterDiagrams) {
        if (chapterDiagrams.isEmpty()) {
            return content;
        }

        StringBuilder index = new StringBuilder();
        index.append("\n\n## 图表目录\n\n");

        for (Map.Entry<String, List<DiagramInsertInfo>> entry : chapterDiagrams.entrySet()) {
            for (DiagramInsertInfo diagram : entry.getValue()) {
                index.append(String.format("图%d %s - %s\n",
                        diagram.getFigureNumber(),
                        diagram.getCaption(),
                        entry.getKey()));
            }
        }

        int tocIndex = content.indexOf("目录");
        if (tocIndex > 0) {
            int insertIndex = content.indexOf("\n", tocIndex);
            if (insertIndex > 0) {
                return content.substring(0, insertIndex) + index + content.substring(insertIndex);
            }
        }

        return index + "\n" + content;
    }

    private String formatDiagramInsertion(DiagramInsertInfo info) {
        return String.format("![%s](%s)\n\n图%d %s\n\n%s\n",
                info.getCaption(),
                info.getDiagramPath(),
                info.getFigureNumber(),
                info.getCaption(),
                info.getDescription());
    }

    private String generateDescription(String diagramType) {
        return switch (diagramType) {
            case "systemArchitecture" -> "该图展示了系统的总体架构设计，包括主要层次结构和组件关系。";
            case "layerArchitecture" -> "该图展示了系统的分层架构，各层职责清晰，层次分明。";
            case "moduleStructure" -> "该图展示了系统的模块划分结构，各模块职责明确。";
            case "classDiagram" -> "该图展示了系统的核心类结构及其关系。";
            case "useCaseDiagram" -> "该图展示了系统的主要功能用例和用户角色关系。";
            case "sequenceDiagram" -> "该图展示了系统核心业务流程的交互时序。";
            case "erDiagram" -> "该图展示了系统数据库的实体关系模型。";
            case "componentDiagram" -> "该图展示了系统组件之间的关系与依赖。";
            case "deploymentDiagram" -> "该图展示了系统的部署架构和环境分布。";
            case "activityDiagram" -> "该图展示了系统核心业务的活动流程和处理步骤。";
            case "stateDiagram" -> "该图展示了系统核心对象的状态转换过程。";
            case "dataFlowDiagram" -> "该图展示了系统的数据流向和处理过程。";
            case "functionStructure" -> "该图展示了系统的功能结构层次和模块划分。";
            case "businessFlow" -> "该图展示了系统的核心业务流程和参与角色。";
            default -> "该图展示了系统的相关设计内容。";
        };
    }

    private List<ChapterInfo> parseChapters(String content) {
        List<ChapterInfo> chapters = new ArrayList<>();
        String[] lines = content.split("\n");

        for (String line : lines) {
            if (line.startsWith("# ") || line.startsWith("## ")) {
                String title = line.replaceAll("^#+\\s*", "").trim();
                int level = line.startsWith("# ") ? 1 : 2;
                chapters.add(new ChapterInfo(title, level));
            }
        }

        return chapters;
    }

    public List<DiagramSuggestion> generateInsertionSuggestions(String content, Map<String, String> availableDiagrams) {
        List<DiagramSuggestion> suggestions = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : DIAGRAM_CHAPTER_MAPPING.entrySet()) {
            String diagramType = entry.getKey();
            if (!availableDiagrams.containsKey(diagramType)) {
                continue;
            }

            List<String> keywords = Collections.singletonList(String.valueOf(entry.getValue()));
            for (String keyword : keywords) {
                if (content.contains(keyword)) {
                    DiagramSuggestion suggestion = new DiagramSuggestion();
                    suggestion.setDiagramType(diagramType);
                    suggestion.setConfidence(0.7);
                    suggestion.setReason("内容包含关键词: " + keyword);
                    suggestions.add(suggestion);
                    break;
                }
            }
        }

        return suggestions;
    }

    @Data
    public static class ChapterInfo {
        private final String title;
        private final int level;
    }

    @Data
    public static class DiagramInsertInfo {
        private String diagramType;
        private String diagramPath;
        private int figureNumber;
        private String caption;
        private String description;
    }

    @Data
    public static class DiagramSuggestion {
        private String diagramType;
        private double confidence;
        private String reason;
    }

    public boolean diagramExists(String diagramPath) {
        if (diagramPath == null) {
            return false;
        }
        if (diagramPath.startsWith("http://") || diagramPath.startsWith("https://")) {
            return true;
        }
        try {
            Path path = Paths.get(diagramPath);
            return Files.exists(path);
        } catch (Exception e) {
            return false;
        }
    }

    public String findDiagramInContent(String content, String diagramType) {
        String typeName = DIAGRAM_TYPE_NAMES.get(diagramType);
        if (typeName == null) {
            return null;
        }

        Pattern pattern = Pattern.compile("!\\[" + Pattern.quote(typeName) + "\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }
}
