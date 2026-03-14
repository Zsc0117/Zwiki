package com.zwiki.remote.client;

import com.zwiki.domain.dto.BatchDocumentRequest;
import com.zwiki.domain.dto.CodeFileRequest;
import com.zwiki.domain.dto.CodeReviewContextRequest;
import com.zwiki.domain.dto.ContentTypeSearchRequest;
import com.zwiki.domain.dto.DocumentRequest;
import com.zwiki.domain.dto.MemorySearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory服务Feign客户端熔断降级处理 - 统一实现
 *
 * @author AI Assistant
 */
@Component
public class MemoryServiceClientFallback implements MemoryServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(MemoryServiceClientFallback.class);

    @Override
    public void addDocument(DocumentRequest request) {
        logger.warn("Memory服务不可用，跳过文档记忆索引: repositoryId={}, documentName={}",
            request.getRepositoryId(), request.getDocumentName());
    }

    @Override
    public void batchAddDocuments(BatchDocumentRequest request) {
        logger.warn("Memory服务不可用，跳过批量文档记忆索引: repositoryId={}, documentCount={}",
            request.getRepositoryId(), request.getDocuments() != null ? request.getDocuments().size() : 0);
    }

    @Override
    public void addCodeFile(CodeFileRequest request) {
        logger.warn("Memory服务不可用，跳过代码文件记忆索引: repositoryId={}, fileName={}",
            request.getRepositoryId(), request.getFileName());
    }

    @Override
    public String searchContextForCodeReview(CodeReviewContextRequest request) {
        logger.warn("Memory服务不可用，跳过代码评审上下文搜索");
        return "";
    }

    @Override
    public MemorySearchResultDto.SearchResponse searchByContentType(ContentTypeSearchRequest request) {
        logger.warn("Memory服务不可用，跳过内容检索: repositoryId={}, contentType={}",
                request != null ? request.getRepositoryId() : null,
                request != null ? request.getContentType() : null);
        return new MemorySearchResultDto.SearchResponse(Collections.emptyList(), 0);
    }

    @Override
    public MemorySearchResultDto.SearchResponse searchRelatedDocuments(ContentTypeSearchRequest request) {
        logger.warn("Memory服务不可用，跳过文档检索: repositoryId={}", request != null ? request.getRepositoryId() : null);
        return new MemorySearchResultDto.SearchResponse(Collections.emptyList(), 0);
    }

    @Override
    public MemorySearchResultDto.SearchResponse searchRelatedCodeFiles(ContentTypeSearchRequest request) {
        logger.warn("Memory服务不可用，跳过代码文件检索: repositoryId={}", request != null ? request.getRepositoryId() : null);
        return new MemorySearchResultDto.SearchResponse(Collections.emptyList(), 0);
    }

    @Override
    public Map<String, Object> healthCheck() {
        logger.warn("Memory服务健康检查失败，服务不可用");
        Map<String, Object> fallbackResponse = new HashMap<>();
        fallbackResponse.put("status", "DOWN");
        fallbackResponse.put("message", "Memory Service Unavailable");
        return fallbackResponse;
    }
}
