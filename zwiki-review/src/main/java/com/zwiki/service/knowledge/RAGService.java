package com.zwiki.service.knowledge;

import com.zwiki.service.ReviewMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RAGService {

    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ReviewMemoryService reviewMemoryService;

    /**
     * 为代码审查检索相关上下文（编码标准、项目规范、历史审查经验）
     *
     * @param diffContent   diff内容或代码片段
     * @param repoFullName  仓库全名 (owner/repo)
     * @return 格式化的上下文信息
     */
    public String retrieveContext(String diffContent, String repoFullName) {
        logger.info("Retrieving RAG context for repo: {}", repoFullName);

        StringBuilder context = new StringBuilder();

        // 1. 通过Memory服务检索代码审查上下文
        try {
            if (reviewMemoryService.isMemoryServiceAvailable()) {
                String[] changedFiles = extractChangedFiles(diffContent);
                String memoryContext = reviewMemoryService.searchContextForCodeReview(
                        repoFullName,
                        truncate(diffContent, 3000),
                        "",
                        "",
                        Arrays.asList(changedFiles)
                );
                if (memoryContext != null && !memoryContext.trim().isEmpty()) {
                    context.append("=== 项目上下文 ===\n").append(memoryContext).append("\n\n");
                    logger.debug("Memory context retrieved: {} chars", memoryContext.length());
                }

                // 2. 检索相关文档（编码规范等）
                List<ReviewMemoryService.SearchResult> docs = reviewMemoryService
                        .searchRelatedDocuments(repoFullName, "coding standards best practices", 3);
                if (!docs.isEmpty()) {
                    context.append("=== 编码规范参考 ===\n");
                    for (ReviewMemoryService.SearchResult doc : docs) {
                        context.append("- ").append(doc.getName() != null ? doc.getName() : "文档")
                                .append(": ").append(truncate(doc.getContent(), 500)).append("\n");
                    }
                    context.append("\n");
                }

                // 3. 检索相关代码文件（理解项目结构）
                List<ReviewMemoryService.SearchResult> codeFiles = reviewMemoryService
                        .searchRelatedCodeFiles(repoFullName, buildCodeQuery(changedFiles), 3);
                if (!codeFiles.isEmpty()) {
                    context.append("=== 相关代码文件 ===\n");
                    for (ReviewMemoryService.SearchResult file : codeFiles) {
                        context.append("- ").append(file.getName() != null ? file.getName() : "代码文件")
                                .append(": ").append(truncate(file.getContent(), 300)).append("\n");
                    }
                    context.append("\n");
                }
            } else {
                logger.debug("Memory service unavailable, skipping RAG context retrieval");
            }
        } catch (Exception e) {
            logger.warn("RAG context retrieval partially failed for repo={}: {}", repoFullName, e.getMessage());
        }

        String result = context.toString().trim();
        logger.info("RAG context retrieved for repo={}: {} chars", repoFullName, result.length());
        return result;
    }

    /**
     * 查询编码风格指南
     */
    public String queryStyleGuide(String query) {
        try {
            return chatClient.prompt()
                .user("Provide coding style guidance for: " + query)
                .call()
                .content();
        } catch (Exception e) {
            logger.error("Failed to query style guide", e);
            return "";
        }
    }

    private String[] extractChangedFiles(String diffContent) {
        if (diffContent == null) return new String[0];
        return diffContent.lines()
                .filter(line -> line.startsWith("+++ b/"))
                .map(line -> line.substring(6))
                .toArray(String[]::new);
    }

    private String buildCodeQuery(String[] changedFiles) {
        if (changedFiles == null || changedFiles.length == 0) return "project structure";
        return Arrays.stream(changedFiles)
                .limit(5)
                .collect(Collectors.joining(" "));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
