package com.zwiki.queue.consumer;

import com.zwiki.repository.entity.DocumentGenerationTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.queue.producer.DocumentGenerationProducer;
import com.zwiki.service.DocumentProcessingService;
import com.zwiki.service.DocumentProcessingService.TaskDeletedException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * @author pai
 * @description: 文档生成任务消费者
 * @date 2026/2/5
 */
@Component
@Slf4j
public class DocumentGenerationConsumer {

    private static final String LOG_PREFIX = "[RedisQueue]";
    
    private final DocumentProcessingService processingService;
    private final DocumentGenerationProducer producer;
    private final Semaphore concurrencyLimiter;
    
    @Value("${project.wiki.redis.consumer.max-concurrency}")
    private int maxConcurrency;
    
    @Value("${project.wiki.redis.consumer.process-interval}")
    private long processInterval;
    
    @Value("${project.wiki.redis.consumer.max-retry}")
    private int maxRetry;

    @Value("${project.wiki.redis.consumer.retry-delay:30000}")
    private long retryDelayMs;
    
    @Value("${project.wiki.redis.consumer.acquire-timeout:30000}")
    private long acquireTimeoutMs;
    
    @Value("${project.wiki.redis.consumer.max-permit-retry:10}")
    private int maxPermitRetry;

    private final ObjectMapper objectMapper;

    public DocumentGenerationConsumer(DocumentProcessingService processingService,
                                    DocumentGenerationProducer producer,
                                    ObjectMapper objectMapper) {
        this.processingService = processingService;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = new Semaphore(6); // 默认6个并发
    }
    
    @PostConstruct
    public void initConcurrencyLimiter() {
        concurrencyLimiter.drainPermits();
        concurrencyLimiter.release(maxConcurrency);
        log.info("{} DocumentGenerationConsumer initialized with maxConcurrency={}, processInterval={}ms, maxRetry={}, acquireTimeout={}ms",
                LOG_PREFIX, maxConcurrency, processInterval, maxRetry, acquireTimeoutMs);
    }
    
    /**
     * 主队列消费者
     */
    public void handleDocGenerationMessage(String message) {
        DocumentGenerationTask task = parseTask(message, "主队列");
        if (task == null) {
            return;
        }
        log.info("{} 接收到文档生成任务: taskId={}, catalogueName={}",
                LOG_PREFIX, task.getTaskId(), task.getCatalogueName());
        processTask(task, false);
    }
    
    /**
     * 重试队列消费者
     */
    public void handleDocRetryMessage(String message) {
        DocumentGenerationTask task = parseTask(message, "重试队列");
        if (task == null) {
            return;
        }
        log.info("{} 接收到重试任务: taskId={}, catalogueName={}, retryCount={}",
                LOG_PREFIX, task.getTaskId(), task.getCatalogueName(), task.getRetryCount());
        delayRetry(task);
        processTask(task, true);
    }

    public void handleDocDlqMessage(String message) {
        DocumentGenerationTask task = parseTask(message, "死信队列");
        if (task == null) {
            return;
        }

        String taskId = task.getTaskId();
        String reason = "文档生成失败";
        if (StringUtils.hasText(task.getCatalogueName())) {
            reason = reason + ": " + task.getCatalogueName();
        }

        try {
            log.warn("{} 目录进入死信队列: taskId={}, catalogueId={}, catalogueName={}, reason={}",
                    LOG_PREFIX, taskId, task.getCatalogueId(), task.getCatalogueName(), reason);

            // 标记目录为失败，并让 tryMarkTaskCompleted() 决定任务整体状态
            processingService.markCatalogueFailedAndCheckTask(task.getCatalogueId(), taskId, reason);
        } catch (Exception e) {
            log.error("{} 处理DLQ消息失败: taskId={}, error={}", LOG_PREFIX, taskId, e.getMessage(), e);
        }
    }

    private void delayRetry(DocumentGenerationTask task) {
        if (retryDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 重试任务延迟被中断: taskId={}", LOG_PREFIX, task.getTaskId());
        }
    }

    private DocumentGenerationTask parseTask(String message, String queueName) {
        try {
            return objectMapper.readValue(message, DocumentGenerationTask.class);
        } catch (Exception e) {
            log.error("{} 解析{}消息失败: payload={}", LOG_PREFIX, queueName, message, e);
            return null;
        }
    }
    
    /**
     * 处理任务的通用方法
     */
    private void processTask(DocumentGenerationTask task, boolean isRetry) {
        boolean acquired = false;
        try {
            // 获取并发控制许可（使用独立的许可重试计数器，不影响任务处理重试次数）
            acquired = concurrencyLimiter.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                task.incrementPermitRetryCount();
                if (task.exceedsMaxPermitRetries(maxPermitRetry)) {
                    log.warn("{} 无法获取并发许可且超过最大许可重试次数，发送到死信队列: taskId={}, permitRetryCount={}, maxPermitRetry={}",
                            LOG_PREFIX, task.getTaskId(), task.getPermitRetryCount(), maxPermitRetry);
                    producer.sendToDeadLetterQueue(task, new RuntimeException("无法获取并发许可，超过最大许可重试次数"));
                } else {
                    log.warn("{} 无法获取并发许可，延迟后重试: taskId={}, permitRetryCount={}/{}, availablePermits={}",
                            LOG_PREFIX, task.getTaskId(), task.getPermitRetryCount(), maxPermitRetry, concurrencyLimiter.availablePermits());
                    delayRetry(task);
                    producer.sendToRetryQueue(task);
                }
                return;
            }
            
            // 控制处理间隔
            if (processInterval > 0) {
                Thread.sleep(processInterval);
            }
            
            log.info("{} 开始处理文档生成任务: taskId={}, catalogueName={}, retryCount={}",
                    LOG_PREFIX, task.getTaskId(), task.getCatalogueName(), task.getRetryCount());
            
            // 调用处理服务
            processingService.processTask(task);

            // 事务提交后再检查一次任务终态，避免并发事务快照导致“最后一章完成但任务未收口”
            processingService.tryMarkTaskCompletedAfterCommit(task.getTaskId());
            
            log.info("{} 任务处理完成: taskId={}, catalogueName={}",
                    LOG_PREFIX, task.getTaskId(), task.getCatalogueName());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} 任务处理被中断: taskId={}", LOG_PREFIX, task.getTaskId());
        } catch (TaskDeletedException e) {
            // 任务已删除，直接忽略，不进行重试
            log.info("{} 任务已删除，跳过处理并确认消息: taskId={}, reason={}", LOG_PREFIX, task.getTaskId(), e.getMessage());
        } catch (Exception e) {
            log.error("{} 任务处理失败: taskId={}, catalogueName={}, error={}",
                    LOG_PREFIX, task.getTaskId(), task.getCatalogueName(), e.getMessage(), e);
            
            // 处理失败的任务
            handleFailedTask(task, e, isRetry);
            
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
        }
    }
    
    /**
     * 处理失败的任务
     */
    private void handleFailedTask(DocumentGenerationTask task, Exception error, boolean isRetry) {
        // 增加重试次数
        task.incrementRetryCount();
        
        // 检查是否超过最大重试次数
        if (task.exceedsMaxRetries(maxRetry)) {
            log.warn("{} 任务超过最大重试次数，发送到死信队列: taskId={}, retryCount={}, maxRetry={}",
                    LOG_PREFIX, task.getTaskId(), task.getRetryCount(), maxRetry);
            producer.sendToDeadLetterQueue(task, error);
        } else {
            log.info("{} 任务处理失败，发送到重试队列: taskId={}, retryCount={}/{}",
                    LOG_PREFIX, task.getTaskId(), task.getRetryCount(), maxRetry);
            producer.sendToRetryQueue(task);
        }
    }
    
    /**
     * 获取当前并发许可数
     */
    public int getAvailablePermits() {
        return concurrencyLimiter.availablePermits();
    }
    
    /**
     * 获取最大并发数配置
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }
}