package com.zwiki.service;

import com.alibaba.fastjson2.JSON;
import com.zwiki.domain.enums.CatalogueStatusEnum;
import com.zwiki.domain.enums.TaskStatusEnum;
import com.zwiki.util.GenDocPrompt;
import com.zwiki.util.CodebasePreReader;
import com.zwiki.util.FileSystemTool;
import com.zwiki.service.auth.SaTokenUserContext;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.dao.CatalogueRepository;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.service.notification.NotificationService;
import com.zwiki.repository.entity.DocumentGenerationTask;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author hxg
 * @description: 文档处理服务
 * @date 2025/8/5
 */
@Service
@Slf4j
public class DocumentProcessingService {
    
    private final LlmService llmService;
    private final CatalogueRepository catalogueMapper;
    private final TaskRepository taskMapper;
    private final MemoryIntegrationService memoryIntegrationService;
    private final CatalogueService catalogueService;
    private final NotificationService notificationService;
    private final TaskProgressService taskProgressService;
    private final SceneModelResolver sceneModelResolver;
    private final CodebasePreReader codebasePreReader;
    private final com.zwiki.service.TaskQueueService taskQueueService;

    private static final int ER_DIAGRAM_MAX_CHARS = 12000;
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\((.*?)\\)\\s*(?:ENGINE|COMMENT|;)");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("(?is)^\\s*`?([a-zA-Z0-9_]+)`?\\s+([a-zA-Z0-9_]+(?:\\([^)]*\\))?)\\b");
    private static final Pattern FK_PATTERN = Pattern.compile("(?is)FOREIGN\\s+KEY\\s*\\(\\s*`?([a-zA-Z0-9_]+)`?\\s*\\)\\s*REFERENCES\\s*`?([a-zA-Z0-9_]+)`?\\s*\\(\\s*`?([a-zA-Z0-9_]+)`?\\s*\\)");
    
    // 用于记录已经索引代码文件的任务，防止重复索引
    private static final Set<String> codeFilesIndexedTasks = ConcurrentHashMap.newKeySet();
    
    public DocumentProcessingService(LlmService llmService, 
                                   CatalogueRepository catalogueMapper,
                                   TaskRepository taskMapper,
                                   MemoryIntegrationService memoryIntegrationService,
                                   CatalogueService catalogueService,
                                   NotificationService notificationService,
                                   TaskProgressService taskProgressService,
                                   SceneModelResolver sceneModelResolver,
                                   CodebasePreReader codebasePreReader,
                                   com.zwiki.service.TaskQueueService taskQueueService) {
        this.llmService = llmService;
        this.catalogueMapper = catalogueMapper;
        this.taskMapper = taskMapper;
        this.memoryIntegrationService = memoryIntegrationService;
        this.catalogueService = catalogueService;
        this.notificationService = notificationService;
        this.taskProgressService = taskProgressService;
        this.sceneModelResolver = sceneModelResolver;
        this.codebasePreReader = codebasePreReader;
        this.taskQueueService = taskQueueService;
    }
    
    /**
     * 处理文档生成任务
     * @param task 文档生成任务
     */
    @Transactional
    public void processTask(DocumentGenerationTask task) {
        String taskId = task.getTaskId();
        String catalogueName = task.getCatalogueName();
        
        log.info("开始处理文档生成任务: taskId={}, catalogueName={}, retryCount={}", 
                taskId, catalogueName, task.getRetryCount());
        
        // 0. 恢复用户上下文（异步线程中ThreadLocal为空，需要从task中恢复userId）
        // 这是关键步骤：LLM模型配置加载依赖AuthUtil.getCurrentUserId()
        if (StringUtils.hasText(task.getUserId())) {
            SaTokenUserContext.setUserId(task.getUserId());
            log.debug("已恢复用户上下文: userId={}", task.getUserId());
        }
        
        // 1. 首先检查任务是否还存在
        Task existingTask = getTaskById(taskId);
        if (existingTask == null) {
            log.warn("任务已被删除，跳过处理: taskId={}, catalogueName={}", taskId, catalogueName);
            throw new TaskDeletedException("任务已被删除: " + taskId);
        }

        if (StringUtils.hasText(existingTask.getUserId())) {
            MDC.put("userId", existingTask.getUserId());
        }
        
        // 2. 检查目录记录是否存在
        Catalogue existingCatalogue = catalogueMapper
                .findFirstByCatalogueId(task.getCatalogueId())
                .orElse(null);
        
        if (existingCatalogue == null) {
            log.warn("目录记录已被删除，跳过处理: taskId={}, catalogueId={}", taskId, task.getCatalogueId());
            throw new TaskDeletedException("目录记录已被删除: " + task.getCatalogueId());
        }
        
        // 先清理可能存在的旧ThreadLocal值
        FileSystemTool.clearProjectRoot();
        
        // 设置项目根路径到 ThreadLocal，供 FileSystemTool 使用
        FileSystemTool.setProjectRoot(task.getLocalPath());
        log.debug("为任务 {} 设置项目根路径: {}", taskId, task.getLocalPath());
        
        try {
            // 解析场景模型
            String sceneModel = sceneModelResolver.resolve(existingTask.getUserId(), SceneModelResolver.Scene.DOC_GEN);

            // 构建完整的prompt（注入dependent files实际内容）
            String prompt = buildPrompt(task);
            
            // 记录token消耗监控信息
            logTokenUsageInfo(prompt, task);
            
            log.info("开始生成文档详情, catalogueName: {}, promptLength: {}, sceneModel: {}", 
                    catalogueName, prompt.length(), sceneModel);
            
            // 调用LLM服务生成内容
            String result;
            try {
                result = llmService.callWithToolsUsingModel(prompt, sceneModel);
            } catch (Exception e) {
                log.warn("LLM生成文档详情 callWithTools 失败，尝试不使用工具降级: taskId={}, catalogueName={}, error={}",
                        taskId, catalogueName, e.getMessage());
                result = llmService.callWithoutToolsUsingModel(prompt, sceneModel);
            }

            if (!StringUtils.hasText(result)) {
                log.warn("LLM生成文档详情返回空内容，尝试不使用工具降级: taskId={}, catalogueName={}", taskId, catalogueName);
                result = llmService.callWithoutToolsUsingModel(prompt, sceneModel);
            }
            
            if (!StringUtils.hasText(result)) {
                throw new RuntimeException("LLM生成文档详情结果为空");
            }
            
            log.info("LLM生成完成: taskId={}, catalogueName={}, resultLength={}", 
                    taskId, catalogueName, result.length());
            
            // 更新数据库状态为完成
            updateCatalogueStatus(task.getCatalogueId(), result, 
                    CatalogueStatusEnum.COMPLETED.getCode(), null);

            updateTaskProgressAfterDocGenerated(task.getTaskId(), task.getCatalogueName());
            tryMarkTaskCompleted(task.getTaskId());
            
            // 异步索引到Mem0记忆系统
            indexToMemorySystemAsync(task, result);
            
            log.info("文档生成任务处理完成: taskId={}, catalogueName={}", taskId, catalogueName);
            
        } catch (TaskDeletedException e) {
            // 任务已删除异常，直接重新抛出，不需要更新状态
            throw e;
        } catch (Exception e) {
            log.error("处理文档生成任务失败: taskId={}, catalogueName={}, error={}", 
                    taskId, catalogueName, e.getMessage(), e);
            
            // 更新数据库状态为失败
            updateCatalogueStatus(task.getCatalogueId(), null, 
                    CatalogueStatusEnum.FAILED.getCode(), e.getMessage());
            
            // 重新抛出异常，让消费者处理重试逻辑
            throw new RuntimeException("文档生成失败: " + e.getMessage(), e);
        } finally {
            // 确保清理 ThreadLocal，避免内存泄漏和状态污染
            try {
                FileSystemTool.clearProjectRoot();
                SaTokenUserContext.clear();
                log.debug("任务 {} 完成，已清理ThreadLocal", taskId);
            } catch (Exception cleanupError) {
                log.warn("清理ThreadLocal时发生异常: taskId={}, error={}", taskId, cleanupError.getMessage());
            }

            MDC.remove("userId");
        }
    }

    private void updateTaskProgressAfterDocGenerated(String taskId, String catalogueName) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        Task task = getTaskById(taskId);
        if (task == null) {
            return;
        }

        String step = StringUtils.hasText(catalogueName) ? ("生成文档: " + catalogueName) : "生成文档";
        TaskProgressService.Progress progress = taskProgressService.incrementDocCompleted(taskId, step, 50, 95);
        if (progress != null && StringUtils.hasText(task.getUserId())) {
            notificationService.notifyTaskProgress(task.getUserId(), taskId, progress.getProgress(), progress.getCurrentStep());
        }
    }

    private void tryMarkTaskCompleted(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        try {
            long total = catalogueMapper.countByTaskId(taskId);
            if (total <= 0) {
                return;
            }
            long completed = catalogueMapper.countByTaskIdAndStatus(taskId, CatalogueStatusEnum.COMPLETED.getCode());
            long failed = catalogueMapper.countByTaskIdAndStatus(taskId, CatalogueStatusEnum.FAILED.getCode());

            // 还有目录未到达终态（IN_PROGRESS），不处理
            if (completed + failed < total) {
                return;
            }

            Task task = getTaskById(taskId);
            if (task == null) {
                return;
            }
            if (TaskStatusEnum.COMPLETED.equals(task.getStatus()) || TaskStatusEnum.FAILED.equals(task.getStatus())) {
                return;
            }

            if (failed == 0) {
                // 全部完成
                task.setStatus(TaskStatusEnum.COMPLETED);
                task.setUpdateTime(LocalDateTime.now());
                taskMapper.save(task);

                taskQueueService.markTaskFinished(taskId);
                taskProgressService.complete(taskId, "分析完成");
                if (StringUtils.hasText(task.getUserId())) {
                    notificationService.notifyTaskCompleted(task.getUserId(), taskId, task.getProjectName());
                }
            } else {
                // 有失败的目录，标记任务为失败
                String reason = String.format("文档生成部分失败: %d/%d 成功, %d/%d 失败", completed, total, failed, total);
                task.setStatus(TaskStatusEnum.FAILED);
                task.setFailReason(reason);
                task.setUpdateTime(LocalDateTime.now());
                taskMapper.save(task);

                taskQueueService.markTaskFinished(taskId);
                taskProgressService.fail(taskId, reason);
                if (StringUtils.hasText(task.getUserId())) {
                    notificationService.notifyTaskFailed(task.getUserId(), taskId, task.getProjectName(), reason);
                }
                log.warn("任务部分失败: taskId={}, completed={}/{}, failed={}/{}", taskId, completed, total, failed, total);
            }
        } catch (Exception e) {
            log.warn("尝试标记任务完成失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * 在目录任务处理事务提交后再次检查任务是否可进入终态。
     *
     * 说明：processTask() 运行在事务内，并发目录在彼此提交前不可见，
     * 事务内 tryMarkTaskCompleted() 可能出现“都差一个目录”的漏判。
     */
    public void tryMarkTaskCompletedAfterCommit(String taskId) {
        tryMarkTaskCompleted(taskId);
    }
    
    /**
     * 构建完整的prompt：读取dependent files的实际源码内容
     */
    private String buildPrompt(DocumentGenerationTask task) {
        List<String> dependentFilePaths = getDependentFilePaths(task);
        String dependentFileContents = codebasePreReader.readSpecificFiles(task.getLocalPath(), dependentFilePaths);
        String catalogueContext = buildCatalogueContext(task);
        String erDiagram = getErDiagramString(task.getLocalPath(), task.getCatalogueName(), task.getPrompt());

        log.info("buildPrompt: 已读取 {} 个依赖文件，内容长度={} 字符, catalogueName={}",
                dependentFilePaths.size(), dependentFileContents.length(), task.getCatalogueName());

        return GenDocPrompt.PROMPT
                .replace("{{repository_location}}", task.getLocalPath())
                .replace("{{prompt}}", task.getPrompt() != null ? task.getPrompt() : "")
                .replace("{{title}}", task.getCatalogueName())
                .replace("{{dependent_file_contents}}", dependentFileContents)
                .replace("{{catalogue_context}}", catalogueContext)
                .replace("{{er_diagram}}", erDiagram);
    }

    private String buildCatalogueContext(DocumentGenerationTask task) {
        List<String> allNames = task.getAllCatalogueNames();
        if (allNames == null || allNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < allNames.size(); i++) {
            String name = allNames.get(i);
            sb.append(i + 1).append(". ").append(name);
            if (name.equals(task.getCatalogueName())) {
                sb.append(" ← 当前");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从目录记录中获取dependent_file路径列表
     */
    private List<String> getDependentFilePaths(DocumentGenerationTask task) {
        try {
            Catalogue catalogue = catalogueMapper
                    .findFirstByCatalogueId(task.getCatalogueId())
                    .orElse(null);
            if (catalogue != null && StringUtils.hasText(catalogue.getDependentFile())) {
                List<String> files = JSON.parseArray(catalogue.getDependentFile(), String.class);
                if (files != null && !files.isEmpty()) {
                    return files.stream()
                            .filter(StringUtils::hasText)
                            .map(String::trim)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("获取dependent_files失败: {}", e.getMessage());
        }
        return List.of();
    }

    private boolean shouldIncludeRepoFile(Path path) {
        if (path == null) {
            return false;
        }
        String p = path.toString().replace('\\', '/');
        String lower = p.toLowerCase(Locale.ROOT);

        if (lower.contains("/.git/") || lower.contains("/.idea/") || lower.contains("/.vscode/")) {
            return false;
        }
        if (lower.contains("/target/") || lower.contains("/build/") || lower.contains("/node_modules/") || lower.contains("/dist/") || lower.contains("/out/")) {
            return false;
        }
        if (lower.contains("/logs/") || lower.endsWith(".log") || lower.endsWith(".gz")) {
            return false;
        }

        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (name.startsWith(".")) {
            return false;
        }

        return true;
    }

    private String getErDiagramString(String projectRoot, String catalogueName, String cataloguePrompt) {
        if (!StringUtils.hasText(projectRoot)) {
            return "";
        }

        boolean looksLikeDataModelDoc = false;
        String name = catalogueName != null ? catalogueName : "";
        String prompt = cataloguePrompt != null ? cataloguePrompt : "";
        String combined = (name + " " + prompt).toLowerCase(Locale.ROOT);
        if (combined.contains("数据模型") || combined.contains("数据库") || combined.contains("er") || combined.contains("schema") || combined.contains("ddl")) {
            looksLikeDataModelDoc = true;
        }

        if (!looksLikeDataModelDoc) {
            return "";
        }

        String ddl = readFirstSchemaSql(projectRoot);
        if (!StringUtils.hasText(ddl)) {
            return "";
        }

        String mermaid = generateMermaidErDiagramFromSql(ddl);
        if (!StringUtils.hasText(mermaid)) {
            return "";
        }

        if (mermaid.length() > ER_DIAGRAM_MAX_CHARS) {
            mermaid = mermaid.substring(0, ER_DIAGRAM_MAX_CHARS);
        }

        return "```mermaid\n" + mermaid.trim() + "\n```";
    }

    private String readFirstSchemaSql(String projectRoot) {
        Path root = Paths.get(projectRoot);
        List<Path> preferred = List.of(
                root.resolve("init-sql").resolve("init.sql"),
                root.resolve("init.sql"),
                root.resolve("schema.sql"),
                root.resolve("db").resolve("schema.sql"),
                root.resolve("src").resolve("main").resolve("resources").resolve("schema.sql")
        );
        for (Path p : preferred) {
            if (Files.exists(p) && Files.isRegularFile(p)) {
                try {
                    String content = Files.readString(p);
                    if (StringUtils.hasText(content)) {
                        return content;
                    }
                } catch (IOException ignore) {
                }
            }
        }

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(this::shouldIncludeRepoFile)
                    .filter(p -> {
                        String fn = p.getFileName() != null ? p.getFileName().toString().toLowerCase(Locale.ROOT) : "";
                        return fn.endsWith(".sql") && (fn.contains("init") || fn.contains("schema") || fn.contains("ddl"));
                    })
                    .sorted(Comparator.comparingInt(p -> p.toString().length()))
                    .limit(10)
                    .collect(Collectors.toList());

            for (Path p : candidates) {
                try {
                    String content = Files.readString(p);
                    if (StringUtils.hasText(content) && content.toLowerCase(Locale.ROOT).contains("create table")) {
                        return content;
                    }
                } catch (IOException ignore) {
                }
            }
        } catch (Exception ignore) {
        }

        return null;
    }

    private String generateMermaidErDiagramFromSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            return null;
        }

        String ddl = sql;
        if (ddl.length() > 200000) {
            ddl = ddl.substring(0, 200000);
        }

        List<TableDef> tables = new ArrayList<>();
        Matcher m = CREATE_TABLE_PATTERN.matcher(ddl);
        while (m.find()) {
            String tableName = m.group(1);
            String body = m.group(2);
            if (!StringUtils.hasText(tableName) || !StringUtils.hasText(body)) {
                continue;
            }
            TableDef t = new TableDef(tableName);
            parseTableBody(t, body);
            tables.add(t);
        }

        if (tables.isEmpty()) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        out.append("erDiagram\n");
        for (TableDef t : tables) {
            out.append("    ").append(t.name).append(" {\n");
            for (ColumnDef c : t.columns) {
                out.append("        ").append(c.type).append(" ").append(c.name).append("\n");
            }
            out.append("    }\n");
        }
        for (TableDef t : tables) {
            for (ForeignKeyDef fk : t.foreignKeys) {
                out.append("    ")
                        .append(fk.refTable)
                        .append(" ||--o{ ")
                        .append(t.name)
                        .append(" : \"")
                        .append(fk.column)
                        .append("->")
                        .append(fk.refColumn)
                        .append("\"\n");
            }
        }
        return out.toString();
    }

    private void parseTableBody(TableDef table, String body) {
        String[] lines = body.split("\\r?\\n");
        for (String rawLine : lines) {
            if (!StringUtils.hasText(rawLine)) {
                continue;
            }
            String line = rawLine.trim();
            if (line.endsWith(",")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("primary key") || lower.startsWith("unique") || lower.startsWith("key ") || lower.startsWith("index") || lower.startsWith("constraint")) {
                Matcher fkMatcher = FK_PATTERN.matcher(line);
                if (fkMatcher.find()) {
                    table.foreignKeys.add(new ForeignKeyDef(fkMatcher.group(1), fkMatcher.group(2), fkMatcher.group(3)));
                }
                continue;
            }

            Matcher fkMatcher = FK_PATTERN.matcher(line);
            if (fkMatcher.find()) {
                table.foreignKeys.add(new ForeignKeyDef(fkMatcher.group(1), fkMatcher.group(2), fkMatcher.group(3)));
                continue;
            }

            Matcher colMatcher = COLUMN_PATTERN.matcher(line);
            if (!colMatcher.find()) {
                continue;
            }
            String colName = colMatcher.group(1);
            String colType = colMatcher.group(2);
            if (!StringUtils.hasText(colName) || !StringUtils.hasText(colType)) {
                continue;
            }
            table.columns.add(new ColumnDef(colName, normalizeMermaidType(colType)));
        }
    }

    private String normalizeMermaidType(String sqlType) {
        String t = sqlType.trim().toUpperCase(Locale.ROOT);
        int idx = t.indexOf('(');
        if (idx > 0) {
            t = t.substring(0, idx);
        }
        if (t.length() > 20) {
            t = t.substring(0, 20);
        }
        return t;
    }

    private static class TableDef {
        private final String name;
        private final List<ColumnDef> columns = new ArrayList<>();
        private final List<ForeignKeyDef> foreignKeys = new ArrayList<>();

        private TableDef(String name) {
            this.name = name;
        }
    }

    private static class ColumnDef {
        private final String name;
        private final String type;

        private ColumnDef(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static class ForeignKeyDef {
        private final String column;
        private final String refTable;
        private final String refColumn;

        private ForeignKeyDef(String column, String refTable, String refColumn) {
            this.column = column;
            this.refTable = refTable;
            this.refColumn = refColumn;
        }
    }
    
    /**
     * 记录Token使用监控信息
     */
    private void logTokenUsageInfo(String prompt, DocumentGenerationTask task) {
        try {
            int promptLength = prompt.length();
            int estimatedTokens = promptLength / 4;
            log.info("Token监控 - taskId: {}, catalogueName: {}, promptLength: {}, estimatedTokens: ~{}",
                    task.getTaskId(), task.getCatalogueName(), promptLength, estimatedTokens);
        } catch (Exception e) {
            log.warn("记录Token监控信息失败: {}", e.getMessage());
        }
    }
    
    /**
     * DLQ 处理：标记目录为失败并检查任务整体状态。
     * 当目录因重试耗尽（处理失败或许可获取失败）进入死信队列时调用。
     */
    public void markCatalogueFailedAndCheckTask(String catalogueId, String taskId, String reason) {
        updateCatalogueStatus(catalogueId, null, CatalogueStatusEnum.FAILED.getCode(), reason);
        tryMarkTaskCompleted(taskId);
    }

    /**
     * 更新目录状态
     */
    private void updateCatalogueStatus(String catalogueId, String content, Integer status, String failReason) {
        try {
            // 查询现有记录
            Catalogue existingCatalogue = catalogueMapper
                    .findFirstByCatalogueId(catalogueId)
                    .orElse(null);

            if (existingCatalogue == null) {
                log.error("无法找到要更新的目录记录: catalogueId={}", catalogueId);
                return;
            }

            // 更新记录内容和状态
            existingCatalogue.setContent(content);
            existingCatalogue.setStatus(status);
            existingCatalogue.setFailReason(failReason);
            existingCatalogue.setUpdateTime(LocalDateTime.now());

            catalogueMapper.save(existingCatalogue);
            log.info("目录状态更新成功: catalogueId={}, status={}", catalogueId, status);

            if (StringUtils.hasText(existingCatalogue.getTaskId())) {
                try {
                    catalogueService.refreshCatalogueCache(existingCatalogue.getTaskId());
                } catch (Exception ignore) {
                }
            }

        } catch (Exception e) {
            log.error("更新目录状态失败: catalogueId={}, status={}, error={}",
                    catalogueId, status, e.getMessage(), e);
        }
    }

    /**
     * 异步索引到Mem0记忆系统
     */
    private void indexToMemorySystemAsync(DocumentGenerationTask task, String content) {
        if (!memoryIntegrationService.isMemoryServiceAvailable()) {
            log.debug("Mem0记忆服务不可用，跳过索引: taskId={}", task.getTaskId());
            return;
        }

        try {
            // 构建Catalogue对象用于索引
            Catalogue catalogue = new Catalogue();
            catalogue.setTaskId(task.getTaskId());
            catalogue.setCatalogueId(task.getCatalogueId());
            catalogue.setName(task.getCatalogueName());
            catalogue.setContent(content);
            catalogue.setStatus(CatalogueStatusEnum.COMPLETED.getCode());

            // 使用projectName作为repositoryId，如果没有则使用taskId
            String repositoryId = task.getProjectName() != null ? task.getProjectName() : task.getTaskId();

            // 异步索引单个文档
            memoryIntegrationService.indexDocumentToMemoryAsync(repositoryId, catalogue)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("索引文档到Mem0失败: repositoryId={}, catalogueName={}, error={}",
                                    repositoryId, task.getCatalogueName(), throwable.getMessage());
                        } else {
                            log.debug("成功索引文档到Mem0: repositoryId={}, catalogueName={}",
                                    repositoryId, task.getCatalogueName());
                        }
                    });

            // 索引代码文件（每个任务只索引一次）
            if (!codeFilesIndexedTasks.contains(task.getTaskId())) {
                codeFilesIndexedTasks.add(task.getTaskId());
                memoryIntegrationService.indexCodeFilesToMemoryAsync(repositoryId, task.getLocalPath())
                        .whenComplete((result, throwable) -> {
                            if (throwable != null) {
                                log.warn("索引代码文件到Mem0失败: repositoryId={}, error={}",
                                        repositoryId, throwable.getMessage());
                            } else {
                                log.debug("成功索引代码文件到Mem0: repositoryId={}", repositoryId);
                            }
                        });
            }

        } catch (Exception e) {
            log.error("索引到Mem0记忆系统失败: taskId={}, catalogueName={}, error={}",
                    task.getTaskId(), task.getCatalogueName(), e.getMessage(), e);
        }
    }
    
    /**
     * 根据taskId查询任务
     */
    private Task getTaskById(String taskId) {
        try {
            return taskMapper.findFirstByTaskId(taskId).orElse(null);
        } catch (Exception e) {
            log.error("查询任务失败: taskId={}, error={}", taskId, e.getMessage());
            return null;
        }
    }
    
    /**
     * 任务已删除异常
     */
    public static class TaskDeletedException extends RuntimeException {
        public TaskDeletedException(String message) {
            super(message);
        }
    }
}
