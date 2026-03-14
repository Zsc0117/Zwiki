package com.zwiki.llm.service;

import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.llm.model.ModelHealthState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ModelHealthRepository {

    private final StringRedisTemplate redisTemplate;

    @Value("${spring.application.name:zwiki}")
    private String applicationName;

    /**
     * 获取模型不健康状态的Redis Key
     * 格式: ZWIKI_LLM_MODEL_UNHEALTHY_{appName}
     */
    private String getUnhealthyKey() {
        return RedisKeyEnum.LLM_MODEL_UNHEALTHY.getKey(applicationName);
    }

    /**
     * 获取模型统计信息的Redis Key（按日期分片）
     * 格式: ZWIKI_LLM_MODEL_STATS_{appName}_{date}
     */
    private String getStatsKey() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return RedisKeyEnum.LLM_MODEL_STATS.getKey(applicationName, date);
    }

    /**
     * 获取模型最后使用时间的Redis Key
     * 格式: ZWIKI_LLM_MODEL_LAST_USED_{appName}
     */
    private String getLastUsedKey() {
        return RedisKeyEnum.LLM_MODEL_LAST_USED.getKey(applicationName);
    }

    public void markUnhealthy(String modelName, long unhealthyUntilEpochMillis) {
        try {
            redisTemplate.opsForHash().put(getUnhealthyKey(), modelName, String.valueOf(unhealthyUntilEpochMillis));
            log.info("Model {} marked unhealthy until {}", modelName, unhealthyUntilEpochMillis);
        } catch (Exception e) {
            log.error("Failed to mark model {} as unhealthy in Redis", modelName, e);
        }
    }

    public void markHealthy(String modelName) {
        try {
            redisTemplate.opsForHash().delete(getUnhealthyKey(), modelName);
            log.info("Model {} marked healthy", modelName);
        } catch (Exception e) {
            log.error("Failed to mark model {} as healthy in Redis", modelName, e);
        }
    }

    public Long getUnhealthyUntil(String modelName) {
        try {
            Object val = redisTemplate.opsForHash().get(getUnhealthyKey(), modelName);
            if (val != null) {
                return Long.parseLong(val.toString());
            }
        } catch (Exception e) {
            log.error("Failed to get unhealthy status for model {} from Redis", modelName, e);
        }
        return null;
    }

    public boolean isHealthy(String modelName) {
        Long unhealthyUntil = getUnhealthyUntil(modelName);
        if (unhealthyUntil == null) {
            return true;
        }
        return System.currentTimeMillis() > unhealthyUntil;
    }

    public void recordCall(String modelName) {
        try {
            String statsKey = getStatsKey();
            redisTemplate.opsForHash().increment(statsKey, modelName + ":calls", 1);
            // 设置stats key过期时间
            setExpireIfNeeded(statsKey, RedisKeyEnum.LLM_MODEL_STATS.getExpireTime());
            
            String lastUsedKey = getLastUsedKey();
            redisTemplate.opsForHash().put(lastUsedKey, modelName, String.valueOf(System.currentTimeMillis()));
            // 设置last_used key过期时间
            setExpireIfNeeded(lastUsedKey, RedisKeyEnum.LLM_MODEL_LAST_USED.getExpireTime());
        } catch (Exception e) {
            log.error("Failed to record call for model {} in Redis", modelName, e);
        }
    }

    public void recordError(String modelName) {
        try {
            String statsKey = getStatsKey();
            redisTemplate.opsForHash().increment(statsKey, modelName + ":errors", 1);
            setExpireIfNeeded(statsKey, RedisKeyEnum.LLM_MODEL_STATS.getExpireTime());
        } catch (Exception e) {
            log.error("Failed to record error for model {} in Redis", modelName, e);
        }
    }

    public void recordTokens(String modelName, long tokens) {
        try {
            String statsKey = getStatsKey();
            redisTemplate.opsForHash().increment(statsKey, modelName + ":tokens_actual", tokens);
            setExpireIfNeeded(statsKey, RedisKeyEnum.LLM_MODEL_STATS.getExpireTime());
        } catch (Exception e) {
            log.error("Failed to record tokens for model {} in Redis", modelName, e);
        }
    }

    public void recordDetailedTokens(String modelName, long inputTokens, long outputTokens, long totalTokens) {
        try {
            String statsKey = getStatsKey();
            redisTemplate.opsForHash().increment(statsKey, modelName + ":input_tokens", inputTokens);
            redisTemplate.opsForHash().increment(statsKey, modelName + ":output_tokens", outputTokens);
            redisTemplate.opsForHash().increment(statsKey, modelName + ":tokens_actual", totalTokens);
            setExpireIfNeeded(statsKey, RedisKeyEnum.LLM_MODEL_STATS.getExpireTime());
        } catch (Exception e) {
            log.error("Failed to record detailed tokens for model {} in Redis", modelName, e);
        }
    }

    /**
     * 设置key的过期时间（仅当key没有设置过期时间时）
     */
    private void setExpireIfNeeded(String key, long expireTimeSeconds) {
        if (expireTimeSeconds > 0) {
            try {
                Long ttl = redisTemplate.getExpire(key);
                if (ttl == null || ttl == -1) {
                    redisTemplate.expire(key, expireTimeSeconds, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.debug("Failed to set expire for key {}: {}", key, e.getMessage());
            }
        }
    }

    public Map<String, ModelHealthState> getAllModelStates(Set<String> modelNames) {
        Map<String, ModelHealthState> states = new HashMap<>();
        String statsKey = getStatsKey();
        String lastUsedKey = getLastUsedKey();
        String unhealthyKey = getUnhealthyKey();

        for (String modelName : modelNames) {
            try {
                Long unhealthyUntil = getUnhealthyUntil(modelName);
                Object callsObj = redisTemplate.opsForHash().get(statsKey, modelName + ":calls");
                Object errorsObj = redisTemplate.opsForHash().get(statsKey, modelName + ":errors");
                Object tokensObj = redisTemplate.opsForHash().get(statsKey, modelName + ":tokens_actual");
                Object lastUsedObj = redisTemplate.opsForHash().get(lastUsedKey, modelName);

                long calls = callsObj != null ? Long.parseLong(callsObj.toString()) : 0;
                long errors = errorsObj != null ? Long.parseLong(errorsObj.toString()) : 0;
                Long tokens = tokensObj != null ? Long.parseLong(tokensObj.toString()) : null;
                Long lastUsed = lastUsedObj != null ? Long.parseLong(lastUsedObj.toString()) : null;

                boolean healthy = unhealthyUntil == null || System.currentTimeMillis() > unhealthyUntil;

                states.put(modelName, ModelHealthState.builder()
                        .modelName(modelName)
                        .healthy(healthy)
                        .unhealthyUntilEpochMillis(unhealthyUntil)
                        .lastUsedEpochMillis(lastUsed)
                        .callCount(calls)
                        .errorCount(errors)
                        .tokensActual(tokens)
                        .build());
            } catch (Exception e) {
                log.error("Failed to get state for model {} from Redis", modelName, e);
                states.put(modelName, ModelHealthState.builder()
                        .modelName(modelName)
                        .healthy(true)
                        .build());
            }
        }
        return states;
    }
}
