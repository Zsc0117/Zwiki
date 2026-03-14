package com.zwiki.llm.service;

public interface TokenUsageRecorder {
    
    /**
     * Record token usage for a model.
     * @param keyId LLM Key ID for precise model targeting (can be null for backward compatibility)
     * @param modelName Model name
     * @param userId User ID (can be null)
     * @param inputTokens Input tokens
     * @param outputTokens Output tokens
     * @param totalTokens Total tokens
     */
    void recordTokenUsage(Long keyId, String modelName, String userId, long inputTokens, long outputTokens, long totalTokens);
    
    /**
     * Record an error for a model.
     * @param keyId LLM Key ID for precise model targeting (can be null for backward compatibility)
     * @param modelName Model name
     * @param userId User ID (can be null)
     */
    void recordError(Long keyId, String modelName, String userId);

    /**
     * 持久化下线模型（DB enabled=false）。
     * 用于致命错误（403/429/额度耗尽）自动禁用。
     * 优先使用 keyId + modelName 精确定位，避免同名模型被误伤。
     */
    default void disableModel(Long keyId, String modelName) {
        // no-op by default; implementations with DB access should override
    }
}
