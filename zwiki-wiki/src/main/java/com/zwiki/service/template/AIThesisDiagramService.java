package com.zwiki.service.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.service.MinioService;
import com.zwiki.repository.entity.ThesisAnalysisReport;
import com.zwiki.service.ThesisAnalysisReportService;
import com.zwiki.service.ThesisLLMService;
import freemarker.template.Configuration;
import freemarker.template.Template;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI驱动的毕业论文图表生成服务（Zwiki版）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIThesisDiagramService {

    private final ThesisAnalysisReportService analysisReportService;
    private final ThesisLLMService llmService;
    private final MinioService minioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${zwiki.workspace.base-path:}")
    private String workspaceBasePath;

    @Value("${zwiki.diagram.use-ai:true}")
    private boolean useAI;

    @Value("${zwiki.thesis.diagram.module-usecase.max:0}")
    private int maxModuleUseCaseDiagrams;

    private Configuration freemarkerConfig;

    @PostConstruct
    public void init() {
        try {
            Configuration config = new Configuration(Configuration.VERSION_2_3_33);
            config.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates/ftl");
            config.setDefaultEncoding("UTF-8");
            config.setTemplateExceptionHandler(freemarker.template.TemplateExceptionHandler.RETHROW_HANDLER);
            config.setLogTemplateExceptions(false);
            config.setWrapUncheckedExceptions(true);
            freemarkerConfig = config;
        } catch (Exception e) {
            log.error("初始化 FreeMarker 配置失败", e);
            freemarkerConfig = null;
        }
    }

    public enum DiagramType {
        SYSTEM_ARCHITECTURE("系统架构图", "system-architecture", "systemArchitecture", "thesis_diagram_system_architecture.ftl"),
        LAYER_ARCHITECTURE("分层架构图", "layer-architecture", "layerArchitecture", "thesis_diagram_layer_architecture.ftl"),
        MODULE_STRUCTURE("模块结构图", "module-structure", "moduleStructure", "thesis_diagram_module.ftl"),
        CLASS_DIAGRAM("类图", "class-diagram", "classDiagram", "thesis_diagram_class.ftl"),
        SEQUENCE_DIAGRAM("时序图", "sequence-diagram", "sequenceDiagram", "thesis_diagram_sequence.ftl"),
        USE_CASE_DIAGRAM("用例图", "use-case-diagram", "useCaseDiagram", "thesis_diagram_usecase.ftl"),
        ER_DIAGRAM("E-R图", "er-diagram", "erDiagram", "thesis_diagram_er.ftl"),
        COMPONENT_DIAGRAM("组件图", "component-diagram", "componentDiagram", "thesis_diagram_component.ftl"),
        DEPLOYMENT_DIAGRAM("部署图", "deployment-diagram", "deploymentDiagram", "thesis_diagram_deployment.ftl"),
        ACTIVITY_DIAGRAM("活动图", "activity-diagram", "activityDiagram", "thesis_diagram_activity.ftl"),
        STATE_DIAGRAM("状态图", "state-diagram", "stateDiagram", "thesis_diagram_state.ftl"),
        DATA_FLOW_DIAGRAM("数据流图", "data-flow-diagram", "dataFlowDiagram", "thesis_diagram_dataflow.ftl"),
        FUNCTION_STRUCTURE("功能结构图", "function-structure", "functionStructure", "thesis_diagram_function_structure.ftl"),
        BUSINESS_FLOW("业务流程图", "business-flow", "businessFlow", "thesis_diagram_business_flow.ftl");

        private final String displayName;
        private final String fileName;
        private final String key;
        private final String templateName;

        DiagramType(String displayName, String fileName, String key, String templateName) {
            this.displayName = displayName;
            this.fileName = fileName;
            this.key = key;
            this.templateName = templateName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getFileName() {
            return fileName;
        }

        public String getKey() {
            return key;
        }

        public String getTemplateName() {
            return templateName;
        }
    }

    public Map<String, String> generateAllThesisDiagrams(String taskId) {
        log.info("开始生成毕业论文图表, taskId={}", taskId);
        Map<String, String> diagrams = new LinkedHashMap<>();

        if (!useAI) {
            return diagrams;
        }

        ThesisAnalysisReport report = analysisReportService.buildReport(taskId);
        String combinedContent = analysisReportService.collectCatalogueContent(taskId);
        List<String> dependentFiles = analysisReportService.collectDependentFiles(taskId);

        Map<String, Object> baseContext = prepareBaseContext(taskId, report, combinedContent, dependentFiles);

        for (DiagramType type : DiagramType.values()) {
            try {
                Map<String, Object> context = prepareDiagramContext(type, baseContext, report, combinedContent, dependentFiles);
                String prompt = renderPromptTemplate(type.getTemplateName(), context);
                if (!StringUtils.hasText(prompt)) {
                    continue;
                }

                String response = llmService.generateCodeSummary(prompt);
                String plantUml = extractAndCleanPlantUML(response);
                if (type == DiagramType.USE_CASE_DIAGRAM && !isValidUseCaseDiagram(plantUml)) {
                    String fixPrompt = prompt
                            + "\n\n# 纠错任务\n"
                            + "你刚才输出的内容不是标准用例图（缺少 actor/usecase/rectangle 或输出成了其它图）。\n"
                            + "请严格按用例图语法重写，只输出 @startuml 到 @enduml。\n"
                            + "必须包含：actor、usecase（或(用例)）、rectangle（系统边界）。\n";
                    String retry = llmService.generateCodeSummary(fixPrompt);
                    String retriedUml = extractAndCleanPlantUML(retry);
                    if (isValidUseCaseDiagram(retriedUml)) {
                        plantUml = retriedUml;
                    }
                }
                if (!StringUtils.hasText(plantUml)) {
                    continue;
                }

                String path = renderPlantUMLToPNG(taskId, type.getFileName(), plantUml);
                if (path != null) {
                    diagrams.put(type.getKey(), path);
                }
            } catch (Exception e) {
                log.warn("生成图表失败: {}", type.getDisplayName(), e);
            }
        }

        try {
            generateModuleUseCaseDiagrams(taskId, report, combinedContent, dependentFiles, diagrams);
        } catch (Exception e) {
            log.warn("生成模块用例图失败: {}", e.getMessage());
        }

        return diagrams;
    }

    private void generateModuleUseCaseDiagrams(String taskId,
                                               ThesisAnalysisReport report,
                                               String combinedContent,
                                               List<String> dependentFiles,
                                               Map<String, String> diagrams) {
        if (report == null) {
            return;
        }

        List<Map<String, Object>> modules = extractModules(report);
        if (modules.isEmpty()) {
            return;
        }

        if (maxModuleUseCaseDiagrams > 0 && modules.size() > maxModuleUseCaseDiagrams) {
            log.warn("模块数量过多({})，将仅生成前 {} 个模块用例图（可通过 zwiki.thesis.diagram.module-usecase.max 调整）",
                    modules.size(), maxModuleUseCaseDiagrams);
            modules = modules.subList(0, maxModuleUseCaseDiagrams);
        }

        Map<String, Object> baseContext = prepareBaseContext(taskId, report, combinedContent, dependentFiles);
        List<String> classNames = extractClassNamesFromFiles(dependentFiles);
        List<String> controllers = classNames.stream().filter(name -> name.endsWith("Controller"))
                .limit(12).collect(Collectors.toList());

        for (Map<String, Object> module : modules) {
            String moduleName = String.valueOf(module.getOrDefault("name", "")).trim();
            if (!StringUtils.hasText(moduleName)) {
                continue;
            }
            String diagramKey = "useCaseDiagram_" + moduleName;
            if (diagrams.containsKey(diagramKey)) {
                continue;
            }

            Map<String, Object> context = new HashMap<>(baseContext);
            context.put("functionModules", List.of(buildSingleFunctionModule(module)));
            context.put("controllers", controllers);
            context.put("actors", List.of("普通用户", "管理员"));
            context.put("systemDescription", safeGet(report.getProjectOverview()));
            context.put("moduleName", moduleName);

            String prompt = renderPromptTemplate(DiagramType.USE_CASE_DIAGRAM.getTemplateName(), context);
            if (!StringUtils.hasText(prompt)) {
                continue;
            }
            prompt = prompt
                    + "\n\n# 本次生成范围\n"
                    + "仅生成【" + moduleName + "】模块的用例图。\n"
                    + "必须在 title 与系统边界 rectangle 中体现模块名（例如：title XXX模块用例图，rectangle \"系统-XXX模块\"）。\n"
                    + "用例数量控制在 4-8 个，优先体现该模块核心用例。\n";

            String response = llmService.generateCodeSummary(prompt);
            String plantUml = extractAndCleanPlantUML(response);
            if (!isValidUseCaseDiagram(plantUml)) {
                String fixPrompt = prompt
                        + "\n\n# 纠错任务\n"
                        + "你刚才输出的内容不是标准用例图。请重写，只输出 @startuml 到 @enduml。\n"
                        + "必须包含：actor、usecase（或(用例)）、rectangle（系统边界），并体现模块名：" + moduleName + "。\n";
                String retry = llmService.generateCodeSummary(fixPrompt);
                String retriedUml = extractAndCleanPlantUML(retry);
                if (isValidUseCaseDiagram(retriedUml)) {
                    plantUml = retriedUml;
                }
            }
            if (!StringUtils.hasText(plantUml)) {
                continue;
            }

            String fileName = "use-case-diagram-" + sanitizeFileNameComponent(moduleName);
            String path = renderPlantUMLToPNG(taskId, fileName, plantUml);
            if (path != null) {
                diagrams.put(diagramKey, path);
            }
        }
    }

    private Map<String, Object> buildSingleFunctionModule(Map<String, Object> module) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("name", module.getOrDefault("name", "模块"));
        Object subs = module.get("subModules");
        if (subs instanceof List<?> list) {
            entry.put("functions", list.stream().map(String::valueOf).collect(Collectors.toList()));
        } else {
            entry.put("functions", List.of());
        }
        return entry;
    }

    private String sanitizeFileNameComponent(String text) {
        if (!StringUtils.hasText(text)) {
            return "module";
        }
        String cleaned = text.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "-");
        cleaned = cleaned.replaceAll("\\s+", "-");
        cleaned = cleaned.replaceAll("-+", "-");
        cleaned = cleaned.replaceAll("^-", "").replaceAll("-$", "");
        if (cleaned.isBlank()) {
            return "module";
        }
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return cleaned;
    }

    private boolean isValidUseCaseDiagram(String plantUml) {
        if (!StringUtils.hasText(plantUml)) {
            return false;
        }
        String lower = plantUml.toLowerCase(Locale.ROOT);
        if (!lower.contains("@startuml") || !lower.contains("@enduml")) {
            return false;
        }
        boolean hasActor = lower.contains("actor");
        boolean hasUsecase = lower.contains("usecase") || lower.contains("(");
        boolean hasRectangle = lower.contains("rectangle");
        return hasActor && hasUsecase && hasRectangle;
    }

    private Map<String, Object> prepareBaseContext(String taskId,
                                                   ThesisAnalysisReport report,
                                                   String combinedContent,
                                                   List<String> dependentFiles) {
        Map<String, Object> context = new HashMap<>();
        context.put("taskId", taskId);
        context.put("projectName", report.getProjectName());
        context.put("projectType", "Java Web应用");
        context.put("projectOverview", report.getProjectOverview());
        context.put("comprehensiveReport", report.getComprehensiveReport());
        context.put("techStack", splitTechStack(report.getTechStack()));
        context.put("packages", extractPackages(dependentFiles));
        context.put("dependentFiles", dependentFiles);
        context.put("combinedContent", combinedContent);
        return context;
    }

    private Map<String, Object> prepareDiagramContext(DiagramType type,
                                                      Map<String, Object> baseContext,
                                                      ThesisAnalysisReport report,
                                                      String combinedContent,
                                                      List<String> dependentFiles) {
        Map<String, Object> context = new HashMap<>(baseContext);
        List<String> classNames = extractClassNamesFromFiles(dependentFiles);
        List<String> controllers = classNames.stream().filter(name -> name.endsWith("Controller"))
                .limit(8).collect(Collectors.toList());
        List<String> services = classNames.stream().filter(name -> name.endsWith("Service"))
                .limit(8).collect(Collectors.toList());

        switch (type) {
            case SYSTEM_ARCHITECTURE -> {
                context.put("modules", extractModuleNames(report));
                context.put("controllers", controllers);
                context.put("services", services);
                String summary = String.format("包含 %d 个Controller, %d 个Service, %d 个Entity",
                        controllers.size(), services.size(),
                        classNames.stream().filter(n -> n.endsWith("Entity")).count());
                context.put("codeStructureSummary", summary);
            }
            case LAYER_ARCHITECTURE -> context.put("layers", buildLayers(classNames));
            case MODULE_STRUCTURE -> {
                context.put("modules", extractModules(report));
                context.put("functionSummary", report.getCoreModulesAnalysis());
            }
            case CLASS_DIAGRAM -> context.put("classes", buildClassInfos(dependentFiles));
            case SEQUENCE_DIAGRAM -> {
                context.put("apis", buildApiList(controllers));
                context.put("callChains", List.of("Controller -> Service -> Repository -> Database"));
                context.put("participants", List.of("用户", "Controller", "Service", "Repository", "Database"));
                context.put("businessFlow", report.getBusinessFlows());
            }
            case USE_CASE_DIAGRAM -> {
                context.put("functionModules", buildFunctionModules(report));
                context.put("controllers", controllers);
                context.put("actors", List.of("普通用户", "管理员"));
                context.put("systemDescription", safeGet(report.getProjectOverview()));
            }
            case ER_DIAGRAM -> {
                context.put("entities", buildEntityInfos(classNames));
                context.put("tables", extractTableNames(combinedContent));
            }
            case COMPONENT_DIAGRAM -> context.put("components", buildComponents(report));
            case DEPLOYMENT_DIAGRAM -> {
                context.put("deploymentInfo", "基于当前技术栈的标准部署架构");
                context.put("services", services);
            }
            case ACTIVITY_DIAGRAM -> {
                context.put("functionModules", buildFunctionModules(report));
                context.put("businessFlow", report.getBusinessFlows());
                context.put("controllers", controllers);
                context.put("services", services);
            }
            case STATE_DIAGRAM -> {
                context.put("entities", buildEntityInfos(classNames));
                context.put("businessFlow", report.getBusinessFlows());
                context.put("stateDescription", "系统核心业务对象的状态流转");
            }
            case DATA_FLOW_DIAGRAM -> {
                context.put("modules", extractModuleNames(report));
                context.put("controllers", controllers);
                context.put("services", services);
                context.put("dataFlowDescription", "系统数据流向和处理过程");
            }
            case FUNCTION_STRUCTURE -> {
                context.put("functionModules", buildFunctionModules(report));
                context.put("modules", extractModules(report));
                context.put("controllers", controllers);
            }
            case BUSINESS_FLOW -> {
                context.put("functionModules", buildFunctionModules(report));
                context.put("businessFlow", report.getBusinessFlows());
                context.put("controllers", controllers);
                context.put("services", services);
            }
            default -> {
            }
        }
        return context;
    }

    private String renderPromptTemplate(String templateName, Map<String, Object> context) {
        if (freemarkerConfig == null) {
            return null;
        }
        try {
            Template template = freemarkerConfig.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(context, writer);
            return writer.toString();
        } catch (Exception e) {
            log.warn("渲染Prompt模板失败: {}", templateName, e);
            return null;
        }
    }

    private String extractAndCleanPlantUML(String response) {
        if (!StringUtils.hasText(response)) {
            return null;
        }

        String code = response.trim();
        code = code.replaceAll("```plantuml\\s*", "")
                .replaceAll("```puml\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        Pattern umlPattern = Pattern.compile("(@startuml.*?@enduml)", Pattern.DOTALL);
        Matcher matcher = umlPattern.matcher(code);
        if (matcher.find()) {
            code = matcher.group(1);
        }

        if (!code.startsWith("@startuml")) {
            code = "@startuml\n" + code + "\n@enduml";
        }

        return code;
    }

    private String renderPlantUMLToPNG(String taskId, String diagramName, String plantUmlCode) {
        try {
            String pngFilename = diagramName + ".png";
            SourceStringReader reader = new SourceStringReader(plantUmlCode);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            reader.outputImage(baos, new FileFormatOption(net.sourceforge.plantuml.FileFormat.PNG));
            byte[] pngBytes = baos.toByteArray();

            if (pngBytes.length == 0) {
                return null;
            }

            if (minioService != null && minioService.isEnabled()) {
                try {
                    String url = minioService.uploadBytes(
                            "thesis-diagrams/" + taskId,
                            pngBytes,
                            "image/png",
                            "png");
                    if (StringUtils.hasText(url)) {
                        log.info("图表上传MinIO成功: {} -> {}", pngFilename, url);
                        return url;
                    }
                } catch (Exception e) {
                    log.warn("图表上传MinIO失败，降级到本地存储: {}", e.getMessage());
                }
            }

            Path diagramDir = Paths.get(resolveWorkspaceBasePath(), taskId, "diagrams");
            Files.createDirectories(diagramDir);
            Path pngFilePath = diagramDir.resolve(pngFilename);

            try (FileOutputStream fos = new FileOutputStream(pngFilePath.toFile())) {
                fos.write(pngBytes);
            }

            return pngFilePath.toString();
        } catch (Exception e) {
            log.error("渲染PlantUML图表失败: {}", diagramName, e);
            return null;
        }
    }

    private String resolveWorkspaceBasePath() {
        if (StringUtils.hasText(workspaceBasePath)) {
            return workspaceBasePath;
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "zwiki-workspace").toString();
    }

    private List<String> splitTechStack(String techStack) {
        if (!StringUtils.hasText(techStack)) {
            return List.of();
        }
        String normalized = techStack.replace(";", ",").replace("\n", ",");
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> extractPackages(List<String> dependentFiles) {
        if (dependentFiles == null) {
            return List.of();
        }
        Set<String> packages = new LinkedHashSet<>();
        for (String file : dependentFiles) {
            if (!StringUtils.hasText(file)) {
                continue;
            }
            String normalized = file.replace('\\', '/');
            int idx = normalized.lastIndexOf('/');
            if (idx > 0) {
                packages.add(normalized.substring(0, idx));
            }
        }
        return packages.stream().limit(30).collect(Collectors.toList());
    }

    private List<String> extractClassNamesFromFiles(List<String> dependentFiles) {
        if (dependentFiles == null) {
            return List.of();
        }
        return dependentFiles.stream()
                .filter(StringUtils::hasText)
                .filter(path -> path.endsWith(".java"))
                .map(path -> {
                    String normalized = path.replace('\\', '/');
                    int idx = normalized.lastIndexOf('/');
                    String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
                    int dot = name.lastIndexOf('.');
                    return dot > 0 ? name.substring(0, dot) : name;
                })
                .filter(StringUtils::hasText)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildClassInfos(List<String> dependentFiles) {
        if (dependentFiles == null) {
            return List.of();
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        for (String file : dependentFiles) {
            if (file == null || !file.endsWith(".java")) {
                continue;
            }
            String normalized = file.replace('\\', '/');
            String name = normalized.substring(normalized.lastIndexOf('/') + 1, normalized.length() - 5);
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> info = new HashMap<>();
            info.put("name", name);
            info.put("type", name.startsWith("I") ? "interface" : "class");
            info.put("packageName", normalized.contains("/") ? normalized.substring(0, normalized.lastIndexOf('/')).replace('/', '.') : "");
            info.put("fields", List.of("- id: Long", "- name: String"));
            info.put("methods", List.of("+ getId(): Long", "+ getName(): String"));
            classes.add(info);
            if (classes.size() >= 8) {
                break;
            }
        }
        return classes;
    }

    private Map<String, List<String>> buildLayers(List<String> classNames) {
        Map<String, List<String>> layers = new LinkedHashMap<>();
        layers.put("Controller", new ArrayList<>());
        layers.put("Service", new ArrayList<>());
        layers.put("Repository", new ArrayList<>());
        layers.put("Entity", new ArrayList<>());

        for (String name : classNames) {
            if (name.endsWith("Controller")) {
                layers.get("Controller").add(name);
            } else if (name.endsWith("Service")) {
                layers.get("Service").add(name);
            } else if (name.endsWith("Repository") || name.endsWith("Mapper") || name.endsWith("Dao")) {
                layers.get("Repository").add(name);
            } else if (name.endsWith("Entity")) {
                layers.get("Entity").add(name);
            }
        }

        return layers;
    }

    private List<String> buildApiList(List<String> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            return List.of();
        }
        return controllers.stream()
                .map(ctrl -> "" + ctrl + " -> /api/..." )
                .collect(Collectors.toList());
    }

    private List<String> extractModuleNames(ThesisAnalysisReport report) {
        List<Map<String, Object>> modules = extractModules(report);
        if (modules.isEmpty()) {
            return List.of();
        }
        return modules.stream()
                .map(m -> String.valueOf(m.getOrDefault("name", "模块")))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> extractModules(ThesisAnalysisReport report) {
        String json = report.getCoreModulesAnalysis();
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> buildFunctionModules(ThesisAnalysisReport report) {
        List<Map<String, Object>> modules = extractModules(report);
        if (modules.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> module : modules) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", module.getOrDefault("name", "模块"));
            Object subs = module.get("subModules");
            if (subs instanceof List<?> list) {
                entry.put("functions", list.stream().map(String::valueOf).collect(Collectors.toList()));
            }
            result.add(entry);
        }
        return result;
    }

    private List<Map<String, Object>> buildComponents(ThesisAnalysisReport report) {
        List<Map<String, Object>> modules = extractModules(report);
        if (modules.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> components = new ArrayList<>();
        for (Map<String, Object> module : modules) {
            Map<String, Object> component = new HashMap<>();
            component.put("name", module.getOrDefault("name", "模块"));
            component.put("description", module.getOrDefault("description", ""));
            component.put("dependencies", Collections.emptyList());
            components.add(component);
        }
        return components;
    }

    private List<Map<String, Object>> buildEntityInfos(List<String> classNames) {
        List<Map<String, Object>> entities = new ArrayList<>();
        for (String name : classNames) {
            if (!name.endsWith("Entity")) {
                continue;
            }
            Map<String, Object> entity = new HashMap<>();
            entity.put("name", name);
            entity.put("fields", List.of("id: Long <<PK>>", "name: String"));
            entity.put("relations", List.of());
            entities.add(entity);
            if (entities.size() >= 8) {
                break;
            }
        }
        return entities;
    }

    private List<String> extractTableNames(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        Pattern pattern = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z0-9_]+)`?");
        Matcher matcher = pattern.matcher(content);
        Set<String> tables = new LinkedHashSet<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
            if (tables.size() >= 10) {
                break;
            }
        }
        return new ArrayList<>(tables);
    }

    private String safeGet(String value) {
        return value != null ? value : "";
    }
}
