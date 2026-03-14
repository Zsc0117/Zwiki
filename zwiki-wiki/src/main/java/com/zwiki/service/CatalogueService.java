package com.zwiki.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.util.AuthUtil;
import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.domain.dto.CatalogueStruct;
import com.zwiki.domain.dto.GenCatalogueDTO;
import com.zwiki.domain.enums.CatalogueStatusEnum;
import com.zwiki.domain.vo.CatalogueListVo;
import com.zwiki.repository.entity.DocumentGenerationTask;
import com.zwiki.queue.producer.DocumentGenerationProducer;
import com.zwiki.repository.context.ExecutionContext;
import com.zwiki.repository.dao.CatalogueRepository;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.util.AnalyzeCataloguePrompt;
import com.zwiki.util.CodebasePreReader;
import com.zwiki.util.FileSystemTool;
import com.zwiki.util.RedisUtil;
import com.zwiki.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author pai
 * @description: 目录服务实现类
 * @date 2026/1/22 23:35
 */
@Slf4j
@Service
public class CatalogueService {
    private final LlmService llmService;
    private final MemoryIntegrationService memoryIntegrationService;
    private final DocumentGenerationProducer documentGenerationProducer;
    private final SceneModelResolver sceneModelResolver;
    private final CodebasePreReader codebasePreReader;

    private final CatalogueRepository catalogueRepository;
    private final TaskRepository taskRepository;

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    private static final int FILE_TREE_SEGMENT_THRESHOLD_CHARS = 12000;
    private static final int FILE_TREE_SEGMENT_CHUNK_CHARS = 8000;

    private static final int DEPENDENT_FILE_CLEAN_MAX_ITEMS = 20;
    private static final int DEPENDENT_FILE_GLOB_EXPAND_MAX_ITEMS = 50;

    private static final int CATALOGUE_TWO_PASS_THRESHOLD = 30000;
    private static final int CATALOGUE_KEY_FILES_LIMIT = 25000;

    public CatalogueService(LlmService llmService, 
                              MemoryIntegrationService memoryIntegrationService,
                              DocumentGenerationProducer documentGenerationProducer,
                              SceneModelResolver sceneModelResolver,
                              CodebasePreReader codebasePreReader,
                              CatalogueRepository catalogueRepository,
                              TaskRepository taskRepository,
                              RedisUtil redisUtil,
                              ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.memoryIntegrationService = memoryIntegrationService;
        this.documentGenerationProducer = documentGenerationProducer;
        this.sceneModelResolver = sceneModelResolver;
        this.codebasePreReader = codebasePreReader;
        this.catalogueRepository = catalogueRepository;
        this.taskRepository = taskRepository;
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
    }

    private String catalogueListCacheKey(String taskId) {
        return RedisKeyEnum.CATALOGUE_LIST_CACHE.getKey(taskId);
    }

    private String catalogueTreeCacheKey(String taskId) {
        return RedisKeyEnum.CATALOGUE_LIST_CACHE.getKey(taskId, "tree");
    }

    private void cacheCatalogueList(String taskId, List<Catalogue> list) {
        if (!StringUtils.hasText(taskId) || list == null) {
            return;
        }
        try {
            redisUtil.set(
                    catalogueListCacheKey(taskId),
                    objectMapper.writeValueAsString(list),
                    RedisKeyEnum.CATALOGUE_LIST_CACHE.getExpireTime()
            );
        } catch (Exception ignore) {
        }
    }

    private void cacheCatalogueTree(String taskId, List<CatalogueListVo> tree) {
        if (!StringUtils.hasText(taskId) || tree == null) {
            return;
        }
        try {
            redisUtil.set(
                    catalogueTreeCacheKey(taskId),
                    objectMapper.writeValueAsString(tree),
                    RedisKeyEnum.CATALOGUE_LIST_CACHE.getExpireTime()
            );
        } catch (Exception ignore) {
        }
    }

    private void evictCatalogueCache(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        try {
            redisUtil.delete(List.of(
                    catalogueListCacheKey(taskId),
                    catalogueTreeCacheKey(taskId)
            ));
        } catch (Exception ignore) {
        }
    }

    public GenCatalogueDTO<Catalogue> generateCatalogue(String fileTree, ExecutionContext context) {
        String fileTreeForPrompt = condenseFileTreeIfTooLarge(fileTree);

        String keyFiles = codebasePreReader.readKeyStructuralFilesCached(context.getLocalPath());
        log.info("目录生成：已读取关键结构文件，长度={} 字符", keyFiles.length());

        String userId = context.getTask() != null ? context.getTask().getUserId() : null;
        String sceneModel = sceneModelResolver.resolve(userId, SceneModelResolver.Scene.CATALOGUE);

        String result;
        try {
            FileSystemTool.setProjectRoot(context.getLocalPath());
            if (keyFiles.length() > CATALOGUE_TWO_PASS_THRESHOLD) {
                log.info("目录生成：关键文件超过 {}K 字符，启用两阶段生成策略", CATALOGUE_TWO_PASS_THRESHOLD / 1000);
                result = generateCatalogueTwoPass(fileTreeForPrompt, keyFiles, context, sceneModel);
            } else {
                result = callCatalogueGeneration(fileTreeForPrompt, keyFiles, context.getLocalPath(), sceneModel, "单次调用");
            }
        } finally {
            FileSystemTool.clearProjectRoot();
        }

        if (!StringUtils.hasText(result)) {
            log.error("LLM生成项目目录返回空内容: fileTreeLength={}", fileTree != null ? fileTree.length() : 0);
            throw new RuntimeException("LLM生成项目目录结构为空或无效");
        }

        log.info("LLM生成项目目录完成");
        CatalogueStruct catalogueStruct = processCatalogueStruct(result);
        List<Catalogue> catalogueList = saveCatalogueStruct(context, catalogueStruct);
        return new GenCatalogueDTO<>(catalogueStruct, catalogueList);
    }

    private String callCatalogueGeneration(String fileTreeForPrompt, String keyFilesContent,
                                            String localPath, String sceneModel, String mode) {
        String genCataloguePrompt = AnalyzeCataloguePrompt.PROMPT
                .replace("{{$key_files}}", keyFilesContent)
                .replace("{{$code_files}}", fileTreeForPrompt)
                .replace("{{$repository_location}}", localPath);
        log.info("LLM开始生成项目目录 [{}], sceneModel={}, promptLength={}", mode, sceneModel, genCataloguePrompt.length());
        String result = llmService.callWithToolsUsingModel(genCataloguePrompt, sceneModel);
        if (!StringUtils.hasText(result)) {
            log.warn("LLM生成项目目录返回空内容，尝试不使用工具重新调用 [{}]", mode);
            result = llmService.callWithoutToolsUsingModel(genCataloguePrompt, sceneModel);
        }
        return result;
    }

    private String generateCatalogueTwoPass(String fileTreeForPrompt, String keyFiles,
                                             ExecutionContext context, String sceneModel) {
        String pass1Prompt = "你是资深软件架构师。请分析以下项目文件树，选出最能体现项目架构和业务逻辑的15个关键文件。\n\n" +
                "选择标准（按优先级）：\n" +
                "1. 项目定义文件（pom.xml / package.json / go.mod 等）\n" +
                "2. 主配置文件（application.yml / .env 等）\n" +
                "3. README文档\n" +
                "4. 数据库脚本 / 数据模型\n" +
                "5. 路由/控制器/API入口文件\n\n" +
                "要求：仅输出JSON数组，每项为相对路径字符串，不要输出任何其他内容。\n\n" +
                "项目文件树：\n" + fileTreeForPrompt;

        List<String> priorityFiles = new ArrayList<>();
        try {
            log.info("目录生成Two-Pass: Pass1 开始，分析关键文件列表");
            String pass1Result = llmService.callWithoutToolsUsingModel(pass1Prompt, sceneModel);
            priorityFiles = extractFilePathsFromJson(pass1Result);
            log.info("目录生成Two-Pass: Pass1 完成，识别到 {} 个关键文件", priorityFiles.size());
        } catch (Exception e) {
            log.warn("目录生成Two-Pass: Pass1 失败，降级到截断关键文件方式: {}", e.getMessage());
        }

        String keyFilesForPass2;
        if (!priorityFiles.isEmpty()) {
            String rawSelected = codebasePreReader.readSpecificFiles(context.getLocalPath(), priorityFiles);
            keyFilesForPass2 = rawSelected.length() > CATALOGUE_KEY_FILES_LIMIT
                    ? rawSelected.substring(0, CATALOGUE_KEY_FILES_LIMIT) : rawSelected;
            log.info("目录生成Two-Pass: 已读取 {} 个优先文件，长度={} 字符", priorityFiles.size(), keyFilesForPass2.length());
        } else {
            keyFilesForPass2 = keyFiles.length() > CATALOGUE_KEY_FILES_LIMIT
                    ? keyFiles.substring(0, CATALOGUE_KEY_FILES_LIMIT) : keyFiles;
            log.info("目录生成Two-Pass: 使用截断关键文件，长度={} 字符", keyFilesForPass2.length());
        }

        return callCatalogueGeneration(fileTreeForPrompt, keyFilesForPass2, context.getLocalPath(), sceneModel, "Two-Pass-Pass2");
    }

    private List<String> extractFilePathsFromJson(String jsonText) {
        if (!StringUtils.hasText(jsonText)) {
            return new ArrayList<>();
        }
        try {
            String stripped = stripMarkdownCodeFence(jsonText.trim());
            int start = stripped.indexOf('[');
            int end = stripped.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return new ArrayList<>();
            }
            String arrayJson = stripped.substring(start, end + 1);
            List<String> paths = objectMapper.readValue(arrayJson, new TypeReference<List<String>>() {});
            return paths != null ? paths : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析Pass1关键文件路径失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String condenseFileTreeIfTooLarge(String fileTree) {
        if (!StringUtils.hasText(fileTree)) {
            return fileTree;
        }
        if (fileTree.length() <= FILE_TREE_SEGMENT_THRESHOLD_CHARS) {
            return fileTree;
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = fileTree.split("\\r?\\n");
        for (String line : lines) {
            if (current.length() + line.length() + 1 > FILE_TREE_SEGMENT_CHUNK_CHARS && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        log.info("fileTree过大，将分段摘要后再生成目录结构: fileTreeLength={}, chunks={}", fileTree.length(), chunks.size());

        StringJoiner summaries = new StringJoiner("\n");
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String summaryPrompt = "你是资深软件架构分析师。请基于以下仓库文件树片段，抽取该片段涉及的主要模块/目录，以及它们的职责（用简短要点）。\n" +
                    "要求：\n" +
                    "1) 只输出要点列表\n" +
                    "2) 不要输出代码块\n" +
                    "3) 内容尽量精炼\n" +
                    "文件树片段(" + (i + 1) + "/" + chunks.size() + ")：\n" + chunk;

            String summary;
            try {
                summary = llmService.callWithoutTools(summaryPrompt);
            } catch (Exception e) {
                summary = null;
            }

            if (StringUtils.hasText(summary)) {
                summaries.add("- 片段" + (i + 1) + ":\n" + summary.trim());
            } else {
                summaries.add("- 片段" + (i + 1) + ": (摘要失败，已跳过)");
            }
        }

        return "仓库文件树过大，已对文件树进行分段摘要（用于生成文档目录结构）：\n" + summaries;
    }

    public CatalogueStruct processCatalogueStruct(String result) {
        if (!StringUtils.hasText(result)) {
            throw new RuntimeException("LLM生成项目目录结构为空或无效");
        }

        String trimmedResult = result.trim();

        if (trimmedResult.startsWith("<documentation_structure>")) {
            String documentationStructure = RegexUtil.extractXmlTagContent(trimmedResult, "<documentation_structure>", "</documentation_structure>");
            documentationStructure = sanitizeJsonText(documentationStructure);
            if (!StringUtils.hasText(documentationStructure)) {
                throw new RuntimeException("LLM生成项目目录结构为空或无效");
            }

            try {
                CatalogueStruct catalogueStruct = parseCatalogueStruct(documentationStructure);
                if (catalogueStruct == null || catalogueStruct.getItems() == null || catalogueStruct.getItems().isEmpty()) {
                    log.error("LLM生成项目目录结构为空或无效，原始内容：{}", documentationStructure);
                    throw new RuntimeException("LLM生成项目目录结构为空或无效");
                }
                log.info("LLM生成项目目录结构解析成功，items数量：{}", catalogueStruct.getItems().size());
                return catalogueStruct;
            } catch (Exception e) {
                log.error("解析LLM生成的目录结构时发生错误，原始内容：{}", documentationStructure, e);
                throw new RuntimeException("解析LLM生成的目录结构失败: " + e.getMessage(), e);
            }
        } else {
            String documentationStructure = stripMarkdownCodeFence(trimmedResult);
            documentationStructure = sanitizeJsonText(documentationStructure);
            List<String> candidates = new ArrayList<>();
            if (StringUtils.hasText(documentationStructure)) {
                candidates.add(documentationStructure);
            }
            String extractedJson = extractFirstJsonPayload(documentationStructure);
            if (StringUtils.hasText(extractedJson) && !extractedJson.equals(documentationStructure)) {
                candidates.add(0, extractedJson);
            }

            Exception lastException = null;
            for (String candidate : candidates) {
                try {
                    CatalogueStruct catalogueStruct = parseCatalogueStruct(candidate);
                    if (catalogueStruct != null && catalogueStruct.getItems() != null && !catalogueStruct.getItems().isEmpty()) {
                        log.info("LLM生成项目目录结构解析成功，items数量：{}", catalogueStruct.getItems().size());
                        return catalogueStruct;
                    }
                } catch (Exception e) {
                    lastException = e;
                }
            }

            if (lastException != null) {
                try {
                    String recoveredJson = recoverTruncatedCatalogueJson(documentationStructure);
                    if (StringUtils.hasText(recoveredJson) && !recoveredJson.equals(documentationStructure)) {
                        CatalogueStruct recovered = parseCatalogueStruct(recoveredJson);
                        if (recovered != null && recovered.getItems() != null && !recovered.getItems().isEmpty()) {
                            log.warn("LLM目录结构JSON疑似截断，已自动恢复并解析成功，items数量：{}", recovered.getItems().size());
                            return recovered;
                        }
                    }
                } catch (Exception ignore) {
                }

                try {
                    String repairedJson = repairCatalogueJsonViaLlm(documentationStructure);
                    if (StringUtils.hasText(repairedJson)) {
                        CatalogueStruct repaired = parseCatalogueStruct(repairedJson);
                        if (repaired != null && repaired.getItems() != null && !repaired.getItems().isEmpty()) {
                            log.warn("LLM目录结构JSON解析失败，已通过二次修复生成并解析成功，items数量：{}", repaired.getItems().size());
                            return repaired;
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            log.error("LLM生成项目目录结构为空或无效，原始内容：{}", documentationStructure);
            if (lastException != null) {
                log.error("解析LLM生成的目录结构时发生错误", lastException);
                throw new RuntimeException("解析LLM生成的目录结构失败: " + lastException.getMessage(), lastException);
            }
            throw new RuntimeException("LLM生成项目目录结构为空或无效");
        }
    }

    private String repairCatalogueJsonViaLlm(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }

        String input = rawText;
        if (input.length() > 12000) {
            input = input.substring(0, 12000);
        }

        String repairPrompt = "你是JSON修复器。下面内容可能是截断/不完整的JSON。\n" +
                "请你修复它，使其成为严格有效的JSON，并且只输出JSON本身（不要输出任何解释、不要输出Markdown代码块）。\n" +
                "如果内容中包含额外文字，请提取并修复其中的JSON对象。\n" +
                "内容如下：\n" + input;
        String repaired = llmService.callWithoutTools(repairPrompt);
        if (!StringUtils.hasText(repaired)) {
            return null;
        }
        repaired = stripMarkdownCodeFence(repaired.trim());
        return sanitizeJsonText(repaired);
    }

    private String recoverTruncatedCatalogueJson(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }

        String t = sanitizeJsonText(rawText).trim();
        int objIndex = t.indexOf('{');
        int arrIndex = t.indexOf('[');
        int startIndex;
        if (objIndex < 0) {
            startIndex = arrIndex;
        } else if (arrIndex < 0) {
            startIndex = objIndex;
        } else {
            startIndex = Math.min(objIndex, arrIndex);
        }
        if (startIndex < 0) {
            return null;
        }

        List<Character> stack = new ArrayList<>();
        char first = t.charAt(startIndex);
        if (first == '{') {
            stack.add('}');
        } else if (first == '[') {
            stack.add(']');
        } else {
            return null;
        }

        boolean inString = false;
        boolean escape = false;
        int lastCompletedItemEnd = -1;

        for (int i = startIndex + 1; i < t.length(); i++) {
            char c = t.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                stack.add('}');
                continue;
            }
            if (c == '[') {
                stack.add(']');
                continue;
            }

            if (!stack.isEmpty() && c == stack.get(stack.size() - 1)) {
                stack.remove(stack.size() - 1);

                if (stack.isEmpty()) {
                    return t.substring(startIndex, i + 1).trim();
                }

                if (c == '}'
                        && stack.size() == 2
                        && stack.get(0) == '}'
                        && stack.get(1) == ']') {
                    lastCompletedItemEnd = i;
                }
            }
        }

        if (lastCompletedItemEnd > 0 && first == '{') {
            String prefix = t.substring(startIndex, lastCompletedItemEnd + 1).trim();
            while (prefix.endsWith(",")) {
                prefix = prefix.substring(0, prefix.length() - 1).trim();
            }
            return prefix + "]}";
        }

        return null;
    }

    private CatalogueStruct parseCatalogueStruct(String documentationStructure) {
        documentationStructure = sanitizeJsonText(documentationStructure);
        CatalogueStruct catalogueStruct = JSON.parseObject(documentationStructure, CatalogueStruct.class);
        if (catalogueStruct == null || catalogueStruct.getItems() == null || catalogueStruct.getItems().isEmpty()) {
            com.alibaba.fastjson2.JSONObject jsonObject = JSON.parseObject(documentationStructure);
            if (jsonObject.containsKey("documentation_structure")) {
                catalogueStruct = jsonObject.getObject("documentation_structure", CatalogueStruct.class);
            }
        }
        return catalogueStruct;
    }

    private String stripMarkdownCodeFence(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }

        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }

        int firstNewline = t.indexOf('\n');
        if (firstNewline >= 0) {
            t = t.substring(firstNewline + 1);
        } else {
            t = t.substring(3);
        }

        int lastFence = t.lastIndexOf("```");
        if (lastFence >= 0) {
            t = t.substring(0, lastFence);
        }

        return t.trim();
    }

    private String extractFirstJsonPayload(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String t = sanitizeJsonText(text);
        if (!StringUtils.hasText(t)) {
            return null;
        }
        t = t.trim();
        int objIndex = t.indexOf('{');
        int arrIndex = t.indexOf('[');
        int startIndex;
        if (objIndex < 0) {
            startIndex = arrIndex;
        } else if (arrIndex < 0) {
            startIndex = objIndex;
        } else {
            startIndex = Math.min(objIndex, arrIndex);
        }
        if (startIndex < 0) {
            return null;
        }

        List<Character> stack = new ArrayList<>();
        char first = t.charAt(startIndex);
        if (first == '{') {
            stack.add('}');
        } else if (first == '[') {
            stack.add(']');
        } else {
            return null;
        }

        boolean inString = false;
        boolean escape = false;
        for (int i = startIndex + 1; i < t.length(); i++) {
            char c = t.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                stack.add('}');
                continue;
            }
            if (c == '[') {
                stack.add(']');
                continue;
            }

            if (!stack.isEmpty() && c == stack.get(stack.size() - 1)) {
                stack.remove(stack.size() - 1);
                if (stack.isEmpty()) {
                    return t.substring(startIndex, i + 1).trim();
                }
            }
        }

        return null;
    }

    private String sanitizeJsonText(String text) {
        if (text == null) {
            return null;
        }

        String t = text;
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }

        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(c);
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public List<Catalogue> saveCatalogueStruct(ExecutionContext context, CatalogueStruct catalogueStruct) {
        List<Catalogue> allCatalogueList = new ArrayList<>();

        List<String> repoFileIndex = buildRepoFileIndex(context.getLocalPath());
        
        // 递归处理所有目录节点
        for (CatalogueStruct.Item item : catalogueStruct.getItems()) {
            allCatalogueList.addAll(saveCatalogueItem(context, item, null, repoFileIndex));
        }
        
        log.info("保存目录结构完成，总共保存了{}个目录节点", allCatalogueList.size());

        if (context != null && context.getTask() != null && StringUtils.hasText(context.getTask().getTaskId())) {
            String taskId = context.getTask().getTaskId();
            cacheCatalogueList(taskId, allCatalogueList);

            List<CatalogueListVo> rootNodes = allCatalogueList.stream()
                    .filter(catalogue -> !StringUtils.hasText(catalogue.getParentCatalogueId()))
                    .map(this::convertToCatalogueListVo)
                    .collect(Collectors.toList());
            rootNodes.forEach(rootNode -> buildCatalogueTree(rootNode, allCatalogueList));
            cacheCatalogueTree(taskId, rootNodes);
        }
        return allCatalogueList;
    }
    
    /**
     * 递归保存目录项及其子项
     * @param context 执行上下文
     * @param item 目录项
     * @param parentCatalogueId 父目录ID
     * @return 保存的目录列表
     */
    private List<Catalogue> saveCatalogueItem(ExecutionContext context, CatalogueStruct.Item item, String parentCatalogueId, List<String> repoFileIndex) {
        List<Catalogue> catalogueList = new ArrayList<>();
        
        // 创建当前目录实体
        Catalogue catalogueEntity = new Catalogue();
        catalogueEntity.setTaskId(context.getTask().getTaskId());
        if (context.getTask() != null && StringUtils.hasText(context.getTask().getUserId())) {
            catalogueEntity.setUserId(context.getTask().getUserId());
        } else if (context.getCreateParams() != null && StringUtils.hasText(context.getCreateParams().getCreatorUserId())) {
            catalogueEntity.setUserId(context.getCreateParams().getCreatorUserId());
        }
        catalogueEntity.setCatalogueId(java.util.UUID.randomUUID().toString());
        catalogueEntity.setParentCatalogueId(parentCatalogueId);
        catalogueEntity.setName(item.getName());
        catalogueEntity.setTitle(item.getTitle());
        catalogueEntity.setPrompt(item.getPrompt());

        List<String> cleanedDependentFiles = cleanDependentFiles(context.getLocalPath(), item.getDependent_file(), repoFileIndex);
        item.setDependent_file(cleanedDependentFiles);
        catalogueEntity.setDependentFile(JSON.toJSONString(cleanedDependentFiles));
        
        // 子目录信息也保存，但主要用于前端显示结构
        catalogueEntity.setChildren(JSON.toJSONString(item.getChildren()));
        catalogueEntity.setStatus(CatalogueStatusEnum.IN_PROGRESS.getCode());
        catalogueEntity.setCreateTime(LocalDateTime.now());
        
        // 保存当前目录
        catalogueRepository.save(catalogueEntity);
        catalogueList.add(catalogueEntity);
        
        log.debug("保存目录节点: name={}, catalogueId={}, parentId={}", 
                item.getName(), catalogueEntity.getCatalogueId(), parentCatalogueId);
        
        // 递归处理子目录
        if (item.getChildren() != null && !item.getChildren().isEmpty()) {
            for (CatalogueStruct.Item child : item.getChildren()) {
                catalogueList.addAll(saveCatalogueItem(context, child, catalogueEntity.getCatalogueId(), repoFileIndex));
            }
        }
        
        return catalogueList;
    }

    private List<String> buildRepoFileIndex(String projectRoot) {
        if (!StringUtils.hasText(projectRoot)) {
            return List.of();
        }

        Path root = Paths.get(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.startsWith(".git/") && !p.startsWith(".idea/") && !p.startsWith("target/")
                            && !p.startsWith("node_modules/") && !p.startsWith("logs/"))
                    .sorted(Comparator.comparingInt(String::length))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("构建仓库文件索引失败: root={}, error={}", projectRoot, e.getMessage());
            return List.of();
        }
    }

    private boolean containsGlobPattern(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return false;
        }
        return filePath.contains("*") || filePath.contains("?") || filePath.contains("[") || filePath.contains("]")
                || filePath.contains("{") || filePath.contains("}");
    }

    private List<String> cleanDependentFiles(String projectRoot, List<String> dependentFiles, List<String> repoFileIndex) {
        if (dependentFiles == null || dependentFiles.isEmpty() || !StringUtils.hasText(projectRoot)) {
            return List.of();
        }

        Path root = Paths.get(projectRoot);
        Set<String> result = new LinkedHashSet<>();

        for (String raw : dependentFiles) {
            if (!StringUtils.hasText(raw) || result.size() >= DEPENDENT_FILE_CLEAN_MAX_ITEMS) {
                continue;
            }

            String entry = raw.trim().replace('\\', '/');

            if (containsGlobPattern(entry)) {
                try {
                    java.nio.file.PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + entry);
                    int added = 0;
                    for (String candidate : repoFileIndex) {
                        if (matcher.matches(Paths.get(candidate))) {
                            result.add(candidate);
                            added++;
                            if (added >= DEPENDENT_FILE_GLOB_EXPAND_MAX_ITEMS || result.size() >= DEPENDENT_FILE_CLEAN_MAX_ITEMS) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("dependent_file glob展开失败: pattern={}, error={}", entry, e.getMessage());
                }
                continue;
            }

            String normalized = entry;
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }

            Path resolved = root.resolve(normalized);
            if (Files.exists(resolved)) {
                if (Files.isDirectory(resolved)) {
                    String prefix = normalized.endsWith("/") ? normalized : normalized + "/";
                    int added = 0;
                    for (String candidate : repoFileIndex) {
                        if (candidate.startsWith(prefix)) {
                            result.add(candidate);
                            added++;
                            if (added >= DEPENDENT_FILE_GLOB_EXPAND_MAX_ITEMS || result.size() >= DEPENDENT_FILE_CLEAN_MAX_ITEMS) {
                                break;
                            }
                        }
                    }
                } else {
                    String rel = root.relativize(resolved).toString().replace('\\', '/');
                    result.add(rel);
                }
                continue;
            }

            Path p = Paths.get(normalized);
            String fileName = p.getFileName() != null ? p.getFileName().toString() : normalized;
            int dot = fileName.lastIndexOf('.');
            final String preferredExt = (dot > 0 && dot < fileName.length() - 1) ? fileName.substring(dot + 1) : "";

            List<String> candidates = repoFileIndex.stream()
                    .filter(c -> {
                        String cName = Paths.get(c).getFileName() != null ? Paths.get(c).getFileName().toString() : c;
                        return cName.equalsIgnoreCase(fileName);
                    })
                    .collect(Collectors.toList());

            if (candidates.isEmpty() && dot > 0) {
                String baseName = fileName.substring(0, dot);
                candidates = repoFileIndex.stream()
                        .filter(c -> {
                            String cName = Paths.get(c).getFileName() != null ? Paths.get(c).getFileName().toString() : c;
                            int cDot = cName.lastIndexOf('.');
                            String cBase = cDot > 0 ? cName.substring(0, cDot) : cName;
                            return cBase.equalsIgnoreCase(baseName);
                        })
                        .collect(Collectors.toList());
            }

            if (!candidates.isEmpty()) {
                String chosen = candidates.stream()
                        .sorted((a, b) -> {
                            String aName = Paths.get(a).getFileName() != null ? Paths.get(a).getFileName().toString() : a;
                            String bName = Paths.get(b).getFileName() != null ? Paths.get(b).getFileName().toString() : b;
                            int aDot = aName.lastIndexOf('.');
                            int bDot = bName.lastIndexOf('.');
                            String aExt = aDot > 0 ? aName.substring(aDot + 1) : "";
                            String bExt = bDot > 0 ? bName.substring(bDot + 1) : "";
                            boolean aPref = StringUtils.hasText(preferredExt) && preferredExt.equalsIgnoreCase(aExt);
                            boolean bPref = StringUtils.hasText(preferredExt) && preferredExt.equalsIgnoreCase(bExt);
                            if (aPref != bPref) {
                                return aPref ? -1 : 1;
                            }
                            return Integer.compare(a.length(), b.length());
                        })
                        .findFirst()
                        .orElse(null);
                if (chosen != null) {
                    result.add(chosen);
                }
            }
        }

        return new ArrayList<>(result);
    }

    public void parallelGenerateCatalogueDetail(String fileTree, GenCatalogueDTO<Catalogue> genCatalogueDTO, String localPath, String projectName) {
        // 过滤出需要生成详细内容的目录
        List<Catalogue> cataloguesToProcess = genCatalogueDTO.getCatalogueList().stream()
                .filter(catalogue -> catalogue != null && StringUtils.hasText(catalogue.getName()))
                .collect(Collectors.toList());
                
        log.info("开始通过Redis队列生成目录详情，总数={}, projectName={}", cataloguesToProcess.size(), projectName);
        
        // 收集所有章节名称列表，用于跨章节上下文
        List<String> allCatalogueNamesList = cataloguesToProcess.stream()
                .map(Catalogue::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        
        int sentCount = 0;
        for (Catalogue catalogue : cataloguesToProcess) {
            try {
                // 为每个目录创建专门的上下文信息
                CatalogueStruct specificContext = createSpecificContext(catalogue, genCatalogueDTO.getCatalogueStruct());
                
                // 创建文档生成任务
                DocumentGenerationTask task = DocumentGenerationTask.create(catalogue, fileTree, specificContext, localPath);
                // 设置项目名称
                task.setProjectName(projectName);
                // 设置全部章节名列表（用于v5 prompt跨章节上下文）
                task.setAllCatalogueNames(allCatalogueNamesList);
                // 设置用户ID（用于在异步线程中恢复用户上下文，以便加载正确的LLM模型配置）
                task.setUserId(AuthUtil.getCurrentUserId());
                
                // 发送任务到Redis队列
                documentGenerationProducer.sendTask(task);
                sentCount++;
                
                log.debug("文档生成任务已发送到Redis: taskId={}, catalogueName={}, projectName={}", 
                        task.getTaskId(), task.getCatalogueName(), projectName);
                
            } catch (Exception e) {
                log.error("发送文档生成任务到Redis失败: catalogueName={}, error={}", 
                        catalogue.getName(), e.getMessage(), e);
                
                // 更新目录状态为失败
                catalogue.setStatus(CatalogueStatusEnum.FAILED.getCode());
                catalogue.setFailReason("发送到队列失败: " + e.getMessage());
                catalogue.setUpdateTime(LocalDateTime.now());
                catalogueRepository.save(catalogue);
            }
        }
        
        log.info("文档生成任务发送完成: 成功发送={}/{}, 使用Redis消息队列进行异步处理", 
                sentCount, cataloguesToProcess.size());
    }
    
    /**
     * 为特定目录创建专门的上下文信息
     * @param targetCatalogue 目标目录
     * @param fullStruct 完整的目录结构
     * @return 针对该目录的上下文信息
     */
    private CatalogueStruct createSpecificContext(Catalogue targetCatalogue, CatalogueStruct fullStruct) {
        CatalogueStruct specificContext = new CatalogueStruct();
        
        // 查找目标目录在结构中的位置和上下文
        CatalogueStruct.Item targetItem = findTargetItem(targetCatalogue, fullStruct.getItems());
        
        if (targetItem != null) {
            // 创建包含目标项及其上下文的结构
            List<CatalogueStruct.Item> contextItems = new ArrayList<>();
            contextItems.add(targetItem);
            specificContext.setItems(contextItems);
            
            log.debug("为目录 {} 创建特定上下文，包含 {} 个依赖文件", 
                    targetCatalogue.getName(), 
                    targetItem.getDependent_file() != null ? targetItem.getDependent_file().size() : 0);
        } else {
            // 如果找不到目标项，使用完整结构作为fallback
            log.warn("无法为目录 {} 找到对应的结构项，使用完整结构", targetCatalogue.getName());
            specificContext = fullStruct;
        }
        
        return specificContext;
    }
    
    /**
     * 在目录树中查找目标目录项
     */
    private CatalogueStruct.Item findTargetItem(Catalogue targetCatalogue, List<CatalogueStruct.Item> items) {
        if (items == null) {
            return null;
        }
        
        for (CatalogueStruct.Item item : items) {
            // 匹配名称和标题
            if ((targetCatalogue.getName() != null && targetCatalogue.getName().equals(item.getName())) ||
                (targetCatalogue.getTitle() != null && targetCatalogue.getTitle().equals(item.getTitle()))) {
                return item;
            }
            
            // 递归查找子项
            CatalogueStruct.Item found = findTargetItem(targetCatalogue, item.getChildren());
            if (found != null) {
                return found;
            }
        }
        
        return null;
    }

    public void deleteCatalogueByTaskId(String taskId) {
        catalogueRepository.deleteByTaskId(taskId);
        evictCatalogueCache(taskId);
    }

    public List<Catalogue> getCatalogueByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }

        try {
            String cached = redisUtil.get(catalogueListCacheKey(taskId));
            if (StringUtils.hasText(cached)) {
                return objectMapper.readValue(cached, new TypeReference<List<Catalogue>>() {
                });
            }
        } catch (Exception ignore) {
        }

        List<Catalogue> list = catalogueRepository.findByTaskId(taskId);
        cacheCatalogueList(taskId, list);
        return list;
    }

    /**
     * 根据taskId获取目录树形结构
     */
    public List<CatalogueListVo> getCatalogueTreeByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }

        try {
            String cached = redisUtil.get(catalogueTreeCacheKey(taskId));
            if (StringUtils.hasText(cached)) {
                return objectMapper.readValue(cached, new TypeReference<List<CatalogueListVo>>() {
                });
            }
        } catch (Exception ignore) {
        }

        List<Catalogue> catalogues = getCatalogueByTaskId(taskId);
        if (catalogues.isEmpty()) {
            return List.of();
        }

        // 找到根节点（没有parentCatalogueId的节点）
        List<CatalogueListVo> rootNodes = catalogues.stream()
                .filter(catalogue -> !StringUtils.hasText(catalogue.getParentCatalogueId()))
                .map(this::convertToCatalogueListVo)
                .collect(Collectors.toList());

        // 为每个根节点构建子树
        rootNodes.forEach(rootNode -> buildCatalogueTree(rootNode, catalogues));

        cacheCatalogueTree(taskId, rootNodes);
        return rootNodes;
    }

    /**
     * 构建目录树形结构
     */
    private void buildCatalogueTree(CatalogueListVo parentNode, List<Catalogue> allCatalogues) {
        List<CatalogueListVo> children = allCatalogues.stream()
                .filter(catalogue -> parentNode.getCatalogueId().equals(catalogue.getParentCatalogueId()))
                .map(this::convertToCatalogueListVo)
                .collect(Collectors.toList());

        if (!children.isEmpty()) {
            parentNode.setChildren(children);
            // 递归构建子节点的子树
            children.forEach(child -> buildCatalogueTree(child, allCatalogues));
        }
    }

    /**
     * 将Catalogue实体转换为CatalogueListVo
     */
    private CatalogueListVo convertToCatalogueListVo(Catalogue catalogue) {
        CatalogueListVo vo = new CatalogueListVo();
        vo.setCatalogueId(catalogue.getCatalogueId());
        vo.setParentCatalogueId(catalogue.getParentCatalogueId());
        vo.setName(catalogue.getName());
        vo.setTitle(catalogue.getTitle());
        vo.setPrompt(catalogue.getPrompt());
        vo.setDependentFile(catalogue.getDependentFile());
        vo.setContent(catalogue.getContent());
        vo.setStatus(catalogue.getStatus());
        return vo;
    }
    
    /**
     * 异步索引单个目录到Mem0记忆系统
     */
    private void indexSingleCatalogueToMemoryAsync(Catalogue catalogue) {
        if (memoryIntegrationService.isMemoryServiceAvailable()) {
            log.debug("异步索引单个目录到Mem0: catalogueName={}", catalogue.getName());
            
            // 索引单个文档
            memoryIntegrationService.indexDocumentToMemoryAsync(
                catalogue.getTaskId(),
                catalogue
            ).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.warn("索引单个目录到Mem0失败: catalogueName={}", catalogue.getName(), throwable);
                } else {
                    log.debug("索引单个目录到Mem0完成: catalogueName={}", catalogue.getName());
                }
            });
            
            // 检查是否是第一个完成的文档，如果是则触发代码文件索引
            // 使用synchronized确保只有一个线程能执行代码文件索引
            triggerCodeFileIndexingOnce(catalogue);
        }
    }
    
    // 用于确保每个任务的代码文件只被索引一次的标记
    private final Set<String> codeFilesIndexedTasks = ConcurrentHashMap.newKeySet();
    
    // 存储任务ID和项目路径的映射，避免循环依赖
    private final Map<String, String> taskProjectPaths = new ConcurrentHashMap<>();
    
    /**
     * 触发代码文件索引（每个任务只执行一次）
     */
    private void triggerCodeFileIndexingOnce(Catalogue catalogue) {
        String taskId = catalogue.getTaskId();
        if (codeFilesIndexedTasks.add(taskId)) {
            log.info("触发代码文件索引: taskId={}", taskId);
            
            // 从缓存中获取项目路径
            String projectPath = taskProjectPaths.get(taskId);
            if (projectPath != null) {
                memoryIntegrationService.indexCodeFilesToMemoryAsync(
                    taskId, 
                    projectPath
                ).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        log.warn("索引代码文件到Mem0失败: taskId={}", taskId, throwable);
                        // 如果失败，移除标记以便重试
                        codeFilesIndexedTasks.remove(taskId);
                    } else {
                        log.info("索引代码文件到Mem0完成: taskId={}", taskId);
                        // 成功后清理路径缓存
                        taskProjectPaths.remove(taskId);
                    }
                });
            } else {
                log.warn("无法获取项目路径进行代码文件索引: taskId={}", taskId);
                // 如果无法获取路径，移除标记
                codeFilesIndexedTasks.remove(taskId);
            }
        }
    }
    
    /**
     * 缓存任务的项目路径，避免循环依赖
     */
    public void cacheTaskProjectPath(String taskId, String projectPath) {
        taskProjectPaths.put(taskId, projectPath);
        log.debug("缓存任务项目路径: taskId={}, path={}", taskId, projectPath);
    }
    
    /**
     * 清理任务相关的缓存数据
     */
    public void cleanupTaskCache(String taskId) {
        taskProjectPaths.remove(taskId);
        codeFilesIndexedTasks.remove(taskId);
        log.debug("清理任务缓存: taskId={}", taskId);
    }

    public void refreshCatalogueCache(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }

        List<Catalogue> list;
        try {
            list = catalogueRepository.findByTaskId(taskId);
        } catch (Exception e) {
            return;
        }

        cacheCatalogueList(taskId, list);

        if (list == null || list.isEmpty()) {
            cacheCatalogueTree(taskId, List.of());
            return;
        }

        List<CatalogueListVo> rootNodes = list.stream()
                .filter(catalogue -> !StringUtils.hasText(catalogue.getParentCatalogueId()))
                .map(this::convertToCatalogueListVo)
                .collect(Collectors.toList());
        rootNodes.forEach(rootNode -> buildCatalogueTree(rootNode, list));
        cacheCatalogueTree(taskId, rootNodes);
    }
    
    public List<Catalogue> getIncompleteCatalogues(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }
        // status != 2 (COMPLETED 完成状态)
        return catalogueRepository.findByTaskIdAndStatusNot(taskId, CatalogueStatusEnum.COMPLETED.getCode());
    }
    
    public int retryIncompleteCatalogues(String taskId, List<String> catalogueIds) {
        if (!StringUtils.hasText(taskId)) {
            return 0;
        }
        
        // 获取未完成的目录
        List<Catalogue> incompleteCatalogues = getIncompleteCatalogues(taskId);
        if (incompleteCatalogues.isEmpty()) {
            log.info("任务 {} 没有未完成的目录需要重试", taskId);
            return 0;
        }
        
        // 如果指定了 catalogueIds，则只重试指定的目录
        if (catalogueIds != null && !catalogueIds.isEmpty()) {
            Set<String> targetIds = new LinkedHashSet<>(catalogueIds);
            incompleteCatalogues = incompleteCatalogues.stream()
                    .filter(c -> targetIds.contains(c.getCatalogueId()))
                    .collect(Collectors.toList());
        }
        
        if (incompleteCatalogues.isEmpty()) {
            log.info("任务 {} 指定的目录都已完成或不存在", taskId);
            return 0;
        }
        
        // 获取项目路径 - 优先从缓存获取，否则从数据库Task获取
        String localPath = taskProjectPaths.get(taskId);
        if (!StringUtils.hasText(localPath)) {
            Task task = taskRepository.findFirstByTaskId(taskId).orElse(null);
            if (task != null && StringUtils.hasText(task.getProjectPath())) {
                localPath = task.getProjectPath();
                taskProjectPaths.put(taskId, localPath);
            } else {
                log.warn("任务 {} 的项目路径未找到", taskId);
                throw new IllegalStateException("任务项目路径未找到，请重新生成文档或联系管理员");
            }
        }
        
        // 验证项目路径存在
        Path projectPath = Paths.get(localPath);
        if (!Files.exists(projectPath)) {
            throw new IllegalStateException("项目路径不存在: " + localPath);
        }
        
        // 获取目录结构用于构建任务
        List<Catalogue> allCatalogues = catalogueRepository.findByTaskId(taskId);
        List<String> allCatalogueNames = allCatalogues.stream()
                .map(Catalogue::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        
        int retryCount = 0;
        for (Catalogue catalogue : incompleteCatalogues) {
            try {
                // 重置目录状态为进行中
                catalogue.setStatus(CatalogueStatusEnum.IN_PROGRESS.getCode());
                catalogue.setFailReason(null);
                catalogue.setUpdateTime(LocalDateTime.now());
                catalogueRepository.save(catalogue);
                
                // 构建文档生成任务（复用现有的create方法，fileTree和catalogueStruct可为null由consumer重新构建）
                DocumentGenerationTask task = new DocumentGenerationTask();
                task.setTaskId(catalogue.getTaskId());
                task.setCatalogueId(catalogue.getCatalogueId());
                task.setCatalogueName(catalogue.getName());
                task.setPrompt(catalogue.getPrompt());
                task.setLocalPath(localPath);
                task.setRetryCount(0);
                task.setPermitRetryCount(0);
                task.setCreateTime(LocalDateTime.now());
                task.setPriority("RETRY");
                task.setAllCatalogueNames(allCatalogueNames);
                task.setUserId(catalogue.getUserId());
                
                // 发送到队列
                documentGenerationProducer.sendTask(task);
                retryCount++;
                
                log.info("重新提交目录生成任务: taskId={}, catalogueId={}, catalogueName={}", 
                        taskId, catalogue.getCatalogueId(), catalogue.getName());
            } catch (Exception e) {
                log.error("重新提交目录生成任务失败: taskId={}, catalogueId={}", 
                        taskId, catalogue.getCatalogueId(), e);
            }
        }
        
        log.info("任务 {} 重试了 {} 个未完成的目录", taskId, retryCount);
        return retryCount;
    }
}
