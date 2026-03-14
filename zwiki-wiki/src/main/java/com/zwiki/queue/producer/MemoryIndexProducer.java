package com.zwiki.queue.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.domain.dto.MemoryIndexTask;
import com.zwiki.util.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MemoryIndexProducer {

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    @Value("${project.wiki.redis.topics.mem-index}")
    private String memIndexTopic;

    public MemoryIndexProducer(RedisUtil redisUtil, ObjectMapper objectMapper) {
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
    }

    public void sendDocumentTask(MemoryIndexTask task) {
        send(task);
    }

    public void sendCodeFileTask(MemoryIndexTask task) {
        send(task);
    }

    private void send(MemoryIndexTask task) {
        try {
            String key = buildKey(task);
            String payload = objectMapper.writeValueAsString(task);
            redisUtil.publish(memIndexTopic, payload);
            log.info("Memory索引任务发送成功: key={}, channel={}", key, memIndexTopic);
        } catch (Exception e) {
            log.error("发送Memory索引任务到Redis失败", e);
            throw new RuntimeException("Failed to send memory index task", e);
        }
    }

    private String buildKey(MemoryIndexTask task) {
        if (task.getTaskId() != null) return task.getTaskId();
        if (task.getRepositoryId() != null && task.getDocumentName() != null) return task.getRepositoryId() + ":" + task.getDocumentName();
        if (task.getRepositoryId() != null && task.getFileName() != null) return task.getRepositoryId() + ":" + task.getFileName();
        return String.valueOf(System.currentTimeMillis());
    }
}


