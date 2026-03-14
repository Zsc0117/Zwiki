package com.zwiki.service;
import com.zwiki.llm.service.TokenUsageRecorder;
import com.zwiki.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageRecorderService implements TokenUsageRecorder {

    private final LlmModelService llmModelService;

    private String resolveUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }

        String resolved = AuthUtil.getCurrentUserId();
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }

        return null;
    }

    @Override
    public void recordTokenUsage(Long keyId, String modelName, String userId, long inputTokens, long outputTokens, long totalTokens) {
        String resolvedUserId = resolveUserId(userId);
        try {
            llmModelService.recordTokenUsage(keyId, modelName, resolvedUserId, inputTokens, outputTokens, totalTokens);
        } catch (Exception e) {
            log.error("Failed to record token usage to database: model={}, keyId={}, userId={}, resolvedUserId={}",
                    modelName, keyId, userId, resolvedUserId, e);
        }
    }

    @Override
    public void recordError(Long keyId, String modelName, String userId) {
        String resolvedUserId = resolveUserId(userId);
        try {
            llmModelService.recordError(keyId, modelName, resolvedUserId);
        } catch (Exception e) {
            log.error("Failed to record error to database: model={}, keyId={}, userId={}, resolvedUserId={}",
                    modelName, keyId, userId, resolvedUserId, e);
        }
    }

    @Override
    public void disableModel(Long keyId, String modelName) {
        try {
            llmModelService.autoDisableModel(keyId, modelName);
        } catch (Exception e) {
            log.error("Failed to auto-disable model in database: model={}, keyId={}", modelName, keyId, e);
        }
    }
}
