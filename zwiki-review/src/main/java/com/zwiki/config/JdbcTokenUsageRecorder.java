package com.zwiki.config;

import com.zwiki.llm.service.TokenUsageRecorder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * JDBC-based TokenUsageRecorder for zwiki-review.
 * Updates zwiki_llm_model table directly via JdbcTemplate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcTokenUsageRecorder implements TokenUsageRecorder {

    private final JdbcTemplate jdbcTemplate;

    // Precise match by keyId + modelName (preferred)
    private static final String UPDATE_USAGE_BY_KEY_SQL =
            "UPDATE zwiki_llm_model SET " +
            "call_count = COALESCE(call_count, 0) + 1, " +
            "input_tokens = COALESCE(input_tokens, 0) + ?, " +
            "output_tokens = COALESCE(output_tokens, 0) + ?, " +
            "total_tokens = COALESCE(total_tokens, 0) + ?, " +
            "last_used_time = NOW() " +
            "WHERE llm_key_id = ? AND name = ?";

    private static final String UPDATE_USAGE_FOR_USER_SQL =
            "UPDATE zwiki_llm_model SET " +
            "call_count = COALESCE(call_count, 0) + 1, " +
            "input_tokens = COALESCE(input_tokens, 0) + ?, " +
            "output_tokens = COALESCE(output_tokens, 0) + ?, " +
            "total_tokens = COALESCE(total_tokens, 0) + ?, " +
            "last_used_time = NOW() " +
            "WHERE name = ? AND user_id = ?";

    private static final String UPDATE_USAGE_BY_NAME_SQL =
            "UPDATE zwiki_llm_model SET " +
            "call_count = COALESCE(call_count, 0) + 1, " +
            "input_tokens = COALESCE(input_tokens, 0) + ?, " +
            "output_tokens = COALESCE(output_tokens, 0) + ?, " +
            "total_tokens = COALESCE(total_tokens, 0) + ?, " +
            "last_used_time = NOW() " +
            "WHERE name = ? LIMIT 1";

    // Precise match by keyId + modelName (preferred)
    private static final String UPDATE_ERROR_BY_KEY_SQL =
            "UPDATE zwiki_llm_model SET " +
            "error_count = COALESCE(error_count, 0) + 1 " +
            "WHERE llm_key_id = ? AND name = ?";

    private static final String UPDATE_ERROR_FOR_USER_SQL =
            "UPDATE zwiki_llm_model SET " +
            "error_count = COALESCE(error_count, 0) + 1 " +
            "WHERE name = ? AND user_id = ?";

    private static final String UPDATE_ERROR_BY_NAME_SQL =
            "UPDATE zwiki_llm_model SET " +
            "error_count = COALESCE(error_count, 0) + 1 " +
            "WHERE name = ? LIMIT 1";

    @Override
    public void recordTokenUsage(Long keyId, String modelName, String userId, long inputTokens, long outputTokens, long totalTokens) {
        try {
            String resolvedUserId = resolveUserId(userId);
            int updated = 0;
            
            // Priority 1: Precise match by keyId + modelName
            if (keyId != null) {
                updated = jdbcTemplate.update(UPDATE_USAGE_BY_KEY_SQL, inputTokens, outputTokens, totalTokens, keyId, modelName);
                if (updated > 0) {
                    log.info("Token usage recorded: model={}, keyId={}, input={}, output={}, total={}",
                            modelName, keyId, inputTokens, outputTokens, totalTokens);
                    return;
                }
            }
            
            // Priority 2: Match by userId
            if (resolvedUserId != null && !resolvedUserId.isBlank()) {
                updated = jdbcTemplate.update(UPDATE_USAGE_FOR_USER_SQL, inputTokens, outputTokens, totalTokens, modelName, resolvedUserId);
            }
            
            // Fallback: match any model by name regardless of userId
            if (updated == 0) {
                updated = jdbcTemplate.update(UPDATE_USAGE_BY_NAME_SQL, inputTokens, outputTokens, totalTokens, modelName);
            }
            if (updated > 0) {
                log.info("Token usage recorded: model={}, keyId={}, userId={}, input={}, output={}, total={}",
                        modelName, keyId, resolvedUserId, inputTokens, outputTokens, totalTokens);
            } else {
                log.warn("No model found to record token usage: model={}, keyId={}, userId={}", modelName, keyId, resolvedUserId);
            }
        } catch (Exception e) {
            log.error("Failed to record token usage: model={}, keyId={}, userId={}", modelName, keyId, userId, e);
        }
    }

    @Override
    public void recordError(Long keyId, String modelName, String userId) {
        try {
            String resolvedUserId = resolveUserId(userId);
            int updated = 0;
            
            // Priority 1: Precise match by keyId + modelName
            if (keyId != null) {
                updated = jdbcTemplate.update(UPDATE_ERROR_BY_KEY_SQL, keyId, modelName);
                if (updated > 0) {
                    log.info("Error recorded: model={}, keyId={}", modelName, keyId);
                    return;
                }
            }
            
            // Priority 2: Match by userId
            if (resolvedUserId != null && !resolvedUserId.isBlank()) {
                updated = jdbcTemplate.update(UPDATE_ERROR_FOR_USER_SQL, modelName, resolvedUserId);
            }
            
            // Fallback: match any model by name regardless of userId
            if (updated == 0) {
                updated = jdbcTemplate.update(UPDATE_ERROR_BY_NAME_SQL, modelName);
            }
            if (updated > 0) {
                log.info("Error recorded: model={}, keyId={}, userId={}", modelName, keyId, resolvedUserId);
            } else {
                log.warn("No model found to record error: model={}, keyId={}, userId={}", modelName, keyId, resolvedUserId);
            }
        } catch (Exception e) {
            log.error("Failed to record error: model={}, keyId={}, userId={}", modelName, keyId, userId, e);
        }
    }

    private static final String DISABLE_BY_KEY_SQL =
            "UPDATE zwiki_llm_model SET enabled = false WHERE llm_key_id = ? AND name = ?";

    @Override
    public void disableModel(Long keyId, String modelName) {
        try {
            int updated = jdbcTemplate.update(DISABLE_BY_KEY_SQL, keyId, modelName);
            if (updated > 0) {
                log.warn("Auto-disabled model '{}' with keyId={} in DB (enabled=false), {} row(s) affected",
                        modelName, keyId, updated);
            } else {
                log.info("disableModel called for keyId={}, model='{}' but no matching rows found", keyId, modelName);
            }
        } catch (Exception e) {
            log.error("Failed to auto-disable model: model={}, keyId={}", modelName, keyId, e);
        }
    }

    private String resolveUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }

        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletRequestAttributes) {
                HttpServletRequest request = servletRequestAttributes.getRequest();
                if (request != null) {
                    String headerUserId = request.getHeader("X-User-Id");
                    if (headerUserId != null && !headerUserId.isBlank()) {
                        return headerUserId;
                    }
                    String paramUserId = request.getParameter("userId");
                    if (paramUserId != null && !paramUserId.isBlank()) {
                        return paramUserId;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
