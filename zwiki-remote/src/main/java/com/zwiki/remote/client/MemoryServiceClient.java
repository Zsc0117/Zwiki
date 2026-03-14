package com.zwiki.remote.client;

import com.zwiki.domain.dto.BatchDocumentRequest;
import com.zwiki.domain.dto.CodeFileRequest;
import com.zwiki.domain.dto.CodeReviewContextRequest;
import com.zwiki.domain.dto.ContentTypeSearchRequest;
import com.zwiki.domain.dto.DocumentRequest;
import com.zwiki.domain.dto.MemorySearchResultDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Memory服务Feign客户端 - 统一契约
 * 供Wiki和Review模块调用Memory服务
 *
 * @author AI Assistant
 */
@FeignClient(
    name = "memory-service",
    url = "${memory.service.url:http://localhost:8993}",
    fallback = MemoryServiceClientFallback.class
)
public interface MemoryServiceClient {

    /**
     * 添加单个文档到记忆
     */
    @PostMapping("/api/memory/documents")
    void addDocument(@RequestBody DocumentRequest request);

    /**
     * 批量添加文档到记忆
     */
    @PostMapping("/api/memory/documents/batch")
    void batchAddDocuments(@RequestBody BatchDocumentRequest request);

    /**
     * 添加代码文件到记忆
     */
    @PostMapping("/api/memory/code-files")
    void addCodeFile(@RequestBody CodeFileRequest request);

    /**
     * 为代码评审搜索相关上下文
     */
    @PostMapping("/api/memory/code-review/context")
    String searchContextForCodeReview(@RequestBody CodeReviewContextRequest request);

    /**
     * 按内容类型搜索
     */
    @PostMapping("/api/memory/code-review/search")
    MemorySearchResultDto.SearchResponse searchByContentType(@RequestBody ContentTypeSearchRequest request);

    /**
     * 搜索相关文档
     */
    @PostMapping("/api/memory/code-review/search/documents")
    MemorySearchResultDto.SearchResponse searchRelatedDocuments(@RequestBody ContentTypeSearchRequest request);

    /**
     * 搜索相关代码文件
     */
    @PostMapping("/api/memory/code-review/search/code-files")
    MemorySearchResultDto.SearchResponse searchRelatedCodeFiles(@RequestBody ContentTypeSearchRequest request);

    /**
     * 健康检查
     */
    @GetMapping("/api/health")
    Map<String, Object> healthCheck();
}
