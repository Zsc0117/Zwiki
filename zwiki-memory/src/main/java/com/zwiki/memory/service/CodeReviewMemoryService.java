package com.zwiki.memory.service;

import com.zwiki.memory.mem0.MemZeroServerRequest;
import com.zwiki.memory.mem0.MemZeroServerResp;
import com.zwiki.memory.mem0.MemZeroServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 代码评审记忆服务
 * 专门处理Review模块的RAG搜索功能
 * 
 * @author AI Assistant
 */
@Service
public class CodeReviewMemoryService {
    
    private static final Logger logger = LoggerFactory.getLogger(CodeReviewMemoryService.class);
    
    @Autowired
    private MemZeroServiceClient memZeroServiceClient;
    
    /**
     * 为代码评审搜索相关上下文
     * 
     * @param repositoryId 仓库ID
     * @param diffContent PR的diff内容
     * @param prTitle PR标题
     * @param prDescription PR描述
     * @param changedFiles 变更的文件列表
     * @return 格式化的上下文信息
     */
    public String searchContextForCodeReview(String repositoryId,
                                           String diffContent,
                                           String prTitle,
                                           String prDescription,
                                           List<String> changedFiles) {
        
        logger.info("开始为代码评审搜索上下文: repositoryId={}", repositoryId);
        
        try {
            // 构建搜索查询
            String searchQuery = buildSearchQuery(diffContent, prTitle, prDescription, changedFiles);
            
            // 执行搜索
            List<MemorySearchResult> searchResults = performContextSearch(repositoryId, searchQuery);
            
            // 格式化搜索结果
            String formattedContext = formatContextForReview(searchResults);
            
            logger.info("代码评审上下文搜索完成: repositoryId={}, resultCount={}", 
                repositoryId, searchResults.size());
            
            return formattedContext;
            
        } catch (Exception e) {
            logger.error("搜索代码评审上下文失败: repositoryId={}", repositoryId, e);
            return "无法获取相关上下文信息，将基于diff内容进行评审。";
        }
    }
    
    /**
     * 搜索特定类型的内容
     * 
     * @param repositoryId 仓库ID
     * @param query 搜索查询
     * @param contentType 内容类型过滤 (document, code_file)
     * @param limit 返回结果数量限制
     * @return 搜索结果列表
     */
    public List<MemorySearchResult> searchByContentType(String repositoryId,
                                                       String query,
                                                       String contentType,
                                                       int limit) {
        return searchByContentType(repositoryId, repositoryId, query, contentType, limit);
    }

    public List<MemorySearchResult> searchByContentType(String repositoryId,
                                                       String userId,
                                                       String query,
                                                       String contentType,
                                                       int limit) {

        String tenantUserId = StringUtils.hasText(userId) ? userId : repositoryId;
        logger.debug("按内容类型搜索: repositoryId={}, userId={}, contentType={}, query={}",
                repositoryId, tenantUserId, contentType, query);

        try {
            // 构建搜索请求
            Map<String, Object> filters = new HashMap<>();
            filters.put("repository_id", repositoryId);
            if (StringUtils.hasText(contentType)) {
                filters.put("type", contentType);
            }

            MemZeroServerRequest.SearchRequest searchRequest = MemZeroServerRequest.SearchRequest.builder()
                    .query(query)
                    .userId(tenantUserId)
                    .filters(filters)
                    .build();

            // 执行搜索
            MemZeroServerResp response = memZeroServiceClient.searchMemories(searchRequest);

            // 转换为结果对象
            List<MemorySearchResult> results = convertToSearchResults(response);

            // 限制返回数量
            if (limit > 0 && results.size() > limit) {
                results = results.subList(0, limit);
            }

            logger.debug("搜索完成: repositoryId={}, userId={}, resultCount={}", repositoryId, tenantUserId, results.size());

            return results;

        } catch (Exception e) {
            logger.error("按内容类型搜索失败: repositoryId={}, userId={}, contentType={}", repositoryId, tenantUserId, contentType, e);
            return List.of();
        }
    }
    
    /**
     * 搜索相关的文档内容
     */
    public List<MemorySearchResult> searchRelatedDocuments(String repositoryId, String query, int limit) {
        return searchByContentType(repositoryId, query, "document", limit);
    }

    public List<MemorySearchResult> searchRelatedDocuments(String repositoryId, String userId, String query, int limit) {
        return searchByContentType(repositoryId, userId, query, "document", limit);
    }
    
    /**
     * 搜索相关的代码文件
     */
    public List<MemorySearchResult> searchRelatedCodeFiles(String repositoryId, String query, int limit) {
        return searchByContentType(repositoryId, query, "code_file", limit);
    }

    public List<MemorySearchResult> searchRelatedCodeFiles(String repositoryId, String userId, String query, int limit) {
        return searchByContentType(repositoryId, userId, query, "code_file", limit);
    }
    
    /**
     * 构建搜索查询字符串
     */
    private String buildSearchQuery(String diffContent, String prTitle, String prDescription, List<String> changedFiles) {
        StringBuilder queryBuilder = new StringBuilder();
        
        // 添加PR标题
        if (StringUtils.hasText(prTitle)) {
            queryBuilder.append(prTitle).append(" ");
        }
        
        // 添加PR描述的关键部分
        if (StringUtils.hasText(prDescription)) {
            // 只取描述的前200个字符，避免查询过长
            String shortDescription = prDescription.length() > 200 ? 
                prDescription.substring(0, 200) : prDescription;
            queryBuilder.append(shortDescription).append(" ");
        }
        
        // 添加变更文件的路径信息
        if (changedFiles != null && !changedFiles.isEmpty()) {
            String filesStr = changedFiles.stream()
                .limit(5)  // 限制文件数量
                .collect(Collectors.joining(" "));
            queryBuilder.append(filesStr).append(" ");
        }
        
        // 从diff内容中提取关键信息
        if (StringUtils.hasText(diffContent)) {
            String keyInfo = extractKeyInfoFromDiff(diffContent);
            queryBuilder.append(keyInfo);
        }
        
        String query = queryBuilder.toString().trim();
        
        // 如果查询为空，使用默认查询
        if (!StringUtils.hasText(query)) {
            query = "代码实现 功能说明";
        }
        
        logger.debug("构建的搜索查询: {}", query);
        return query;
    }
    
    /**
     * 从diff内容中提取关键信息
     */
    private String extractKeyInfoFromDiff(String diffContent) {
        if (!StringUtils.hasText(diffContent)) {
            return "";
        }
        
        StringBuilder keyInfo = new StringBuilder();
        
        // 提取添加的代码行中的关键词
        String[] lines = diffContent.split("\n");
        for (String line : lines) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                // 提取方法名、类名、变量名等
                String cleanLine = line.substring(1).trim();
                if (cleanLine.contains("class ") || cleanLine.contains("interface ") || 
                    cleanLine.contains("public ") || cleanLine.contains("private ")) {
                    keyInfo.append(cleanLine).append(" ");
                }
            }
        }
        
        // 限制长度
        String result = keyInfo.toString().trim();
        return result.length() > 300 ? result.substring(0, 300) : result;
    }
    
    /**
     * 执行上下文搜索
     */
    private List<MemorySearchResult> performContextSearch(String repositoryId, String query) {
        
        // 构建过滤器，只搜索该仓库的内容
        Map<String, Object> filters = Map.of("repository_id", repositoryId);
        
        MemZeroServerRequest.SearchRequest searchRequest = MemZeroServerRequest.SearchRequest.builder()
            .query(query)
            .userId(repositoryId)
            .filters(filters)
            .build();
        
        // 执行搜索
        MemZeroServerResp response = memZeroServiceClient.searchMemories(searchRequest);
        
        // 转换结果
        return convertToSearchResults(response);
    }
    
    /**
     * 将Mem0响应转换为搜索结果
     */
    private List<MemorySearchResult> convertToSearchResults(MemZeroServerResp response) {
        if (response == null || response.getResults() == null) {
            return List.of();
        }
        
        return response.getResults().stream()
            .map(result -> {
                MemorySearchResult searchResult = new MemorySearchResult();
                searchResult.setId(result.getId());
                searchResult.setContent(result.getMemory());
                searchResult.setScore(result.getScore());
                searchResult.setMetadata(result.getMetadata());
                
                // 从元数据中提取类型和名称
                if (result.getMetadata() != null) {
                    searchResult.setType((String) result.getMetadata().get("type"));
                    searchResult.setName(getNameFromMetadata(result.getMetadata()));
                }
                
                return searchResult;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 从元数据中获取名称
     */
    private String getNameFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) return "未知";
        
        // 尝试不同的名称字段
        Object name = metadata.get("document_name");
        if (name != null) return name.toString();
        
        name = metadata.get("file_name");
        if (name != null) return name.toString();
        
        name = metadata.get("name");
        if (name != null) return name.toString();
        
        return "未知";
    }
    
    /**
     * 为代码评审格式化上下文信息
     */
    private String formatContextForReview(List<MemorySearchResult> searchResults) {
        if (searchResults.isEmpty()) {
            return "未找到相关的项目上下文信息。";
        }
        
        StringBuilder context = new StringBuilder();
        context.append("=== 相关项目上下文信息 ===\n\n");
        
        // 按类型分组显示
        Map<String, List<MemorySearchResult>> groupedResults = searchResults.stream()
            .collect(Collectors.groupingBy(result -> 
                result.getType() != null ? result.getType() : "其他"));
        
        // 先显示文档内容
        if (groupedResults.containsKey("document")) {
            context.append("📄 **相关文档**:\n");
            groupedResults.get("document").stream()
                .limit(3)  // 限制文档数量
                .forEach(result -> {
                    context.append(String.format("- %s (相关度: %.2f)\n", result.getName(), result.getScore()));
                    context.append("  ").append(truncateContent(result.getContent(), 200)).append("\n\n");
                });
        }
        
        // 再显示代码文件
        if (groupedResults.containsKey("code_file")) {
            context.append("💻 **相关代码文件**:\n");
            groupedResults.get("code_file").stream()
                .limit(2)  // 限制代码文件数量
                .forEach(result -> {
                    context.append(String.format("- %s (相关度: %.2f)\n", result.getName(), result.getScore()));
                    context.append("  ").append(truncateContent(result.getContent(), 150)).append("\n\n");
                });
        }
        
        context.append("=== 上下文信息结束 ===\n\n");
        
        return context.toString();
    }
    
    /**
     * 截断内容到指定长度
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) return "";
        
        if (content.length() <= maxLength) {
            return content;
        }
        
        return content.substring(0, maxLength) + "...";
    }
    
    /**
     * 搜索结果内部类
     */
    public static class MemorySearchResult {
        private String id;
        private String content;
        private Double score;
        private String type;
        private String name;
        private Map<String, Object> metadata;
        
        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
} 