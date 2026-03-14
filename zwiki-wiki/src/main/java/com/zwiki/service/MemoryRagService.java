package com.zwiki.service;

import com.zwiki.config.MemoryRagProperties;
import com.zwiki.domain.dto.ContentTypeSearchRequest;
import com.zwiki.domain.dto.MemorySearchResultDto;
import com.zwiki.remote.client.MemoryServiceClient;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRagService {

    private final MemoryServiceClient memoryServiceClient;
    private final MemoryRagProperties properties;
    private final TaskRepository taskRepository;

    private static final int MAX_FILE_CONTENT_CHARS = 3000;
    private static final int MAX_FILES_TO_READ = 6;

    public String buildRagContextForTask(String taskId, String query, String userId) {
        return buildRagContextForTask(taskId, query, userId, null);
    }

    public String buildRagContextForTask(String taskId, String query, String userId, String projectRoot) {
        if (!properties.isEnabled()) {
            log.debug("RAG is disabled (zwiki.rag.enabled=false), skipping Mem0 context retrieval");
            return "";
        }
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(query)) {
            return "";
        }

        Task task = taskRepository.findFirstByTaskId(taskId.trim()).orElse(null);
        String repositoryId = null;
        if (task != null && StringUtils.hasText(task.getProjectName())) {
            repositoryId = task.getProjectName();
        }
        if (!StringUtils.hasText(repositoryId)) {
            repositoryId = taskId.trim();
        }

        return buildRagContext(repositoryId, query, userId, projectRoot);
    }

    public String buildRagContext(String repositoryId, String query, String userId) {
        return buildRagContext(repositoryId, query, userId, null);
    }

    public String buildRagContext(String repositoryId, String query, String userId, String projectRoot) {
        if (!properties.isEnabled()) {
            return "";
        }
        if (!StringUtils.hasText(repositoryId) || !StringUtils.hasText(query)) {
            return "";
        }

        String tenantUserId = resolveTenantUserId(repositoryId, userId);

        try {
            int topK = Math.max(properties.getTopK(), 0);
            if (topK == 0) {
                return "";
            }

            List<MemorySearchResultDto> all = new ArrayList<>();

            if (properties.isIncludeDocuments()) {
                ContentTypeSearchRequest req = new ContentTypeSearchRequest(repositoryId, tenantUserId, query, "document", topK);
                MemorySearchResultDto.SearchResponse resp = memoryServiceClient.searchRelatedDocuments(req);
                if (resp != null && resp.getResults() != null) {
                    all.addAll(resp.getResults());
                }
            }

            if (properties.isIncludeCodeFiles()) {
                ContentTypeSearchRequest req = new ContentTypeSearchRequest(repositoryId, tenantUserId, query, "code_file", topK);
              MemorySearchResultDto.SearchResponse resp = memoryServiceClient.searchRelatedCodeFiles(req);
                if (resp != null && resp.getResults() != null) {
                    all.addAll(resp.getResults());
                }
            }

            if (all.isEmpty()) {
                return "";
            }

            String formatted = formatEnriched(all, repositoryId, projectRoot);
            return truncate(formatted, properties.getMaxChars());
        } catch (Exception e) {
            log.warn("RAG检索失败，已降级: repo={}, err={}", repositoryId, e.getMessage());
            return "";
        }
    }

    public String injectContextIntoUserPrompt(String userQuery, String ragContext) {
        if (!StringUtils.hasText(ragContext)) {
            return userQuery;
        }
        return userQuery + "\n\n" +
                "【已知资料（来自项目文档/代码检索，含实际文件内容）】\n" + ragContext + "\n\n" +
                "请优先基于【已知资料】中的实际代码内容回答问题，引用具体文件路径和关键代码。" +
                "如已知资料不足以完整回答，可使用工具进一步搜索和读取相关文件。";
    }

    private String resolveTenantUserId(String repositoryId, String userId) {
        String mode = properties.getIsolationMode();
        if (!StringUtils.hasText(mode)) {
            mode = "PROJECT";
        }
        if ("USER".equalsIgnoreCase(mode)) {
            if (StringUtils.hasText(userId)) {
                return userId;
            }
        }
        return repositoryId;
    }

    private String formatEnriched(List<MemorySearchResultDto> results, String repositoryId, String projectRoot) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        Set<String> readFilePaths = new LinkedHashSet<>();

        for (MemorySearchResultDto r : results) {
            if (r == null || !StringUtils.hasText(r.getContent())) {
                continue;
            }
            String type = StringUtils.hasText(r.getType()) ? r.getType() : "unknown";
            String name = StringUtils.hasText(r.getName()) ? r.getName() : "";
            String filePath = extractFilePath(r.getMetadata());

            sb.append("[").append(idx++).append("] ");
            sb.append("type=").append(type);
            if (StringUtils.hasText(name)) {
                sb.append(", name=").append(name);
            }
            if (StringUtils.hasText(filePath)) {
                sb.append(", path=").append(filePath);
            }
            if (r.getScore() != null) {
                sb.append(String.format(", score=%.3f", r.getScore()));
            }
            sb.append("\n");
            sb.append("摘要: ").append(r.getContent());
            sb.append("\n");

            if (StringUtils.hasText(filePath) && StringUtils.hasText(projectRoot)
                    && readFilePaths.size() < MAX_FILES_TO_READ) {
                String fileContent = readFileContent(projectRoot, repositoryId, filePath);
                if (StringUtils.hasText(fileContent)) {
                    readFilePaths.add(filePath);
                    sb.append("--- 文件内容 ---\n");
                    sb.append(fileContent);
                    sb.append("\n--- 文件内容结束 ---\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String extractFilePath(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object fp = metadata.get("file_path");
        if (fp != null && StringUtils.hasText(fp.toString())) {
            return fp.toString();
        }
        Object fn = metadata.get("file_name");
        if (fn != null && StringUtils.hasText(fn.toString())) {
            return fn.toString();
        }
        return null;
    }

    private String readFileContent(String projectRoot, String repositoryId, String filePath) {
        try {
            Path resolved = resolveFilePath(projectRoot, repositoryId, filePath);
            if (resolved == null || !Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                log.debug("RAG file not found: projectRoot={}, filePath={}", projectRoot, filePath);
                return null;
            }

            long fileSize = Files.size(resolved);
            if (fileSize > 500_000) {
                log.debug("RAG file too large ({}B), skipping: {}", fileSize, filePath);
                return null;
            }

            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_CONTENT_CHARS) {
                content = content.substring(0, MAX_FILE_CONTENT_CHARS) + "\n... (文件内容已截断，共" + content.length() + "字符)";
            }
            log.debug("RAG read file success: path={}, length={}", filePath, content.length());
            return content;
        } catch (IOException e) {
            log.debug("RAG read file failed: filePath={}, error={}", filePath, e.getMessage());
            return null;
        }
    }

    private Path resolveFilePath(String projectRoot, String repositoryId, String filePath) {
        Path rootPath = Paths.get(projectRoot);

        // filePath 可能是 "RepoName/src/main/..." 或 "src/main/..." 格式
        // 尝试多种组合
        Path candidate;

        // 1. 直接拼接 projectRoot + filePath
        candidate = rootPath.resolve(filePath);
        if (Files.exists(candidate)) {
            return candidate;
        }

        // 2. 如果 filePath 以 repositoryId 开头，去掉前缀再拼接
        if (StringUtils.hasText(repositoryId) && filePath.startsWith(repositoryId + "/")) {
            String relativePath = filePath.substring(repositoryId.length() + 1);
            candidate = rootPath.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        // 3. 如果 filePath 以 repositoryId 开头（不区分大小写）
        if (StringUtils.hasText(repositoryId)) {
            String lowerFilePath = filePath.toLowerCase();
            String lowerRepoId = repositoryId.toLowerCase();
            if (lowerFilePath.startsWith(lowerRepoId + "/")) {
                String relativePath = filePath.substring(repositoryId.length() + 1);
                candidate = rootPath.resolve(relativePath);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }

        // 4. 尝试在项目根目录的父目录下查找（filePath 包含项目文件夹名）
        candidate = rootPath.getParent() != null ? rootPath.getParent().resolve(filePath) : null;
        if (candidate != null && Files.exists(candidate)) {
            return candidate;
        }

        return null;
    }

    private String truncate(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
