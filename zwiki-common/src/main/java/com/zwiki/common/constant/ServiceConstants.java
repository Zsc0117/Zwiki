package com.zwiki.common.constant;

/**
 * 服务常量定义
 */
public interface ServiceConstants {
    
    // 服务名称
    String GATEWAY_SERVICE = "zwiki-gateway";
    String WIKI_SERVICE = "zwiki-wiki";
    String REVIEW_SERVICE = "zwiki-review";
    String MEMORY_SERVICE = "zwiki-memory";
    
    // Queue Topics
    String TOPIC_DOC_GENERATION = "zwiki-doc-generation";
    String TOPIC_DOC_RETRY = "zwiki-doc-retry";
    String TOPIC_DOC_DLQ = "zwiki-doc-dlq";
    String TOPIC_MEMORY_INDEX = "zwiki-memory-index";
    
    // 任务状态
    String TASK_STATUS_PENDING = "pending";
    String TASK_STATUS_PROCESSING = "processing";
    String TASK_STATUS_COMPLETED = "completed";
    String TASK_STATUS_FAILED = "failed";
    
    // 源类型
    String SOURCE_TYPE_GIT = "git";
    String SOURCE_TYPE_ZIP = "zip";
}
