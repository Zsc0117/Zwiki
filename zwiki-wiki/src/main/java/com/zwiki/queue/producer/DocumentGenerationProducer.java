package com.zwiki.queue.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.entity.DocumentGenerationTask;
import com.zwiki.util.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @author pai
 * @description: 文档生成任务生产者
 * @date 2026/2/5
 */
@Service
@Slf4j
public class DocumentGenerationProducer {

    private static final String LOG_PREFIX = "[RedisQueue]";
    
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;
    
    @Value("${project.wiki.redis.topics.doc-generation}")
    private String docGenerationTopic;
    
    @Value("${project.wiki.redis.topics.doc-retry}")
    private String docRetryTopic;
    
    @Value("${project.wiki.redis.topics.doc-dlq}")
    private String docDlqTopic;
    
    public DocumentGenerationProducer(RedisUtil redisUtil, ObjectMapper objectMapper) {
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
        log.info("{} DocumentGenerationProducer initialized (Redis pub/sub)", LOG_PREFIX);
    }
    
    /**
     * 发送文档生成任务到主队列
     * @param task 文档生成任务
     */
    public void sendTask(DocumentGenerationTask task) {
        publishTask(docGenerationTopic, task, "主队列");
    }
    
    /**
     * 发送任务到重试队列
     * @param task 需要重试的任务
     */
    public void sendToRetryQueue(DocumentGenerationTask task) {
        publishTask(docRetryTopic, task, "重试队列");
    }
    
    /**
     * 发送任务到死信队列
     * @param task 失败的任务
     * @param error 错误信息
     */
    public void sendToDeadLetterQueue(DocumentGenerationTask task, Exception error) {
        // 记录错误信息到任务中
        task.setPriority("FAILED");
        publishTask(docDlqTopic, task, "死信队列");
    }

    private void publishTask(String topic, DocumentGenerationTask task, String queueName) {
        try {
            String payload = objectMapper.writeValueAsString(task);
            redisUtil.publish(topic, payload);
            log.info("{} 任务发送到{}成功: taskId={}, catalogueName={}, topic={}",
                LOG_PREFIX, queueName, task.getTaskId(), task.getCatalogueName(), topic);
        } catch (Exception e) {
            log.error("{} 发送任务到{}失败: taskId={}, catalogueName={}, topic={}",
                LOG_PREFIX, queueName, task.getTaskId(), task.getCatalogueName(), topic, e);
            if ("主队列".equals(queueName)) {
                throw new RuntimeException("Failed to send task to Redis", e);
            }
        }
    }
    
    /**
     * 获取主题名称
     */
    public String getDocGenerationTopic() {
        return docGenerationTopic;
    }
    
    public String getDocRetryTopic() {
        return docRetryTopic;
    }
    
    public String getDocDlqTopic() {
        return docDlqTopic;
    }
}