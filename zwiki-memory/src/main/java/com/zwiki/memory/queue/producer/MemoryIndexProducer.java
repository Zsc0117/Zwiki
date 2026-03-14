package com.zwiki.memory.queue.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.memory.queue.model.MemoryIndexTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexProducer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexProducer.class);
    private static final String LOG_PREFIX = "[RedisQueue]";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${project.memory.redis.topics.mem-retry}")
    private String retryTopic;

    @Value("${project.memory.redis.topics.mem-dlq}")
    private String dlqTopic;

    public MemoryIndexProducer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void sendToRetry(MemoryIndexTask task) {
        try {
            String key = buildKey(task);
            String payload = objectMapper.writeValueAsString(task);
            redisTemplate.convertAndSend(retryTopic, payload);
            log.info("{} 重试消息已发送: key={}, channel={}", LOG_PREFIX, key, retryTopic);
        } catch (Exception e) {
            log.error("{} 发送到重试队列失败: key={}", LOG_PREFIX, buildKey(task), e);
        }
    }

    public void sendToDlq(MemoryIndexTask task, Exception error) {
        try {
            String key = buildKey(task);
            String payload = objectMapper.writeValueAsString(task);
            redisTemplate.convertAndSend(dlqTopic, payload);
            log.warn("{} 死信消息已发送: key={}, error={}, channel={}", LOG_PREFIX, key, error.getMessage(), dlqTopic);
        } catch (Exception e) {
            log.error("{} 发送到死信队列失败: key={}", LOG_PREFIX, buildKey(task), e);
        }
    }

    private String buildKey(MemoryIndexTask task) {
        if (task.getTaskId() != null) return task.getTaskId();
        if (task.getRepositoryId() != null && task.getDocumentName() != null) return task.getRepositoryId() + ":" + task.getDocumentName();
        if (task.getRepositoryId() != null && task.getFileName() != null) return task.getRepositoryId() + ":" + task.getFileName();
        return String.valueOf(System.currentTimeMillis());
    }
}


