package com.zwiki.memory.queue.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.memory.queue.model.MemoryIndexTask;
import com.zwiki.memory.queue.producer.MemoryIndexProducer;
import com.zwiki.memory.service.DocumentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class MemoryIndexConsumer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexConsumer.class);
    private static final String LOG_PREFIX = "[RedisQueue]";

    private final DocumentMemoryService documentMemoryService;
    private final MemoryIndexProducer producer;
    private final Semaphore concurrencyLimiter;

    @Value("${project.memory.redis.consumer.max-concurrency}")
    private int maxConcurrency;

    @Value("${project.memory.redis.consumer.process-interval}")
    private long processIntervalMs;

    @Value("${project.memory.redis.consumer.max-retry}")
    private int maxRetry;

    @Value("${project.memory.redis.consumer.retry-delay:1000}")
    private long retryDelayMs;

    private final ObjectMapper objectMapper;

    public MemoryIndexConsumer(DocumentMemoryService documentMemoryService,
                               MemoryIndexProducer producer,
                               ObjectMapper objectMapper) {
        this.documentMemoryService = documentMemoryService;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = new Semaphore(2);
    }

    @PostConstruct
    public void initConcurrencyLimiter() {
        concurrencyLimiter.drainPermits();
        concurrencyLimiter.release(maxConcurrency);
        log.info("{} MemoryIndexConsumer initialized with maxConcurrency={}, processInterval={}ms, maxRetry={}",
                LOG_PREFIX, maxConcurrency, processIntervalMs, maxRetry);
    }

    public void handleMemIndexMessage(String message) {
        MemoryIndexTask task = parseTask(message, "主队列");
        if (task == null) {
            return;
        }
        log.info("{} 接收到索引任务: type={}, repo={}", LOG_PREFIX, task.getType(), task.getRepositoryId());
        processTask(task, false);
    }

    public void handleRetryMessage(String message) {
        MemoryIndexTask task = parseTask(message, "重试队列");
        if (task == null) {
            return;
        }
        log.info("{} 接收到重试索引任务: type={}, repo={}, retryCount={}",
                LOG_PREFIX, task.getType(), task.getRepositoryId(), task.getRetryCount());
        delayRetry(task);
        processTask(task, true);
    }

    private void delayRetry(MemoryIndexTask task) {
        if (retryDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.max(processIntervalMs, retryDelayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 重试任务延迟被中断: repo={}, type={}", LOG_PREFIX, task.getRepositoryId(), task.getType());
        }
    }

    private MemoryIndexTask parseTask(String message, String queueName) {
        try {
            return objectMapper.readValue(message, MemoryIndexTask.class);
        } catch (Exception e) {
            log.error("{} 解析{}消息失败: payload={}", LOG_PREFIX, queueName, message, e);
            return null;
        }
    }

    private void processTask(MemoryIndexTask task, boolean isRetry) {
        boolean acquired = false;
        try {
            acquired = concurrencyLimiter.tryAcquire(10, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("{} 无法获取并发许可，任务重新进入队列: repo={}, type={}", LOG_PREFIX, task.getRepositoryId(), task.getType());
                producer.sendToRetry(task);
                return;
            }

            if (processIntervalMs > 0) {
                Thread.sleep(processIntervalMs);
            }

            validate(task);

            switch (task.getType()) {
                case "document" -> documentMemoryService
                        .addDocumentMemoryAsync(task.getRepositoryId(), task.getUserId(), task.getDocumentName(),
                                task.getDocumentContent(), task.getDocumentUrl(), task.getMetadata())
                        .join();
                case "code_file" -> documentMemoryService
                        .addCodeFileMemoryAsync(task.getRepositoryId(), task.getUserId(), task.getFileName(), task.getFilePath(),
                                task.getFileContent(), task.getFileType())
                        .join();
                default -> throw new IllegalArgumentException("未知的任务类型: " + task.getType());
            }

            log.info("{} 索引任务完成: type={}, repo={}", LOG_PREFIX, task.getType(), task.getRepositoryId());
        } catch (Exception e) {
            log.error("{} 索引任务失败: type={}, repo={}, error={}", LOG_PREFIX, task.getType(), task.getRepositoryId(), e.getMessage(), e);
            handleFailure(task, e, isRetry);
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
        }
    }

    private void validate(MemoryIndexTask task) {
        if (task == null) throw new IllegalArgumentException("任务为空");
        if (task.getType() == null) throw new IllegalArgumentException("任务类型为空");
        if (!Objects.equals(task.getType(), "document") && !Objects.equals(task.getType(), "code_file"))
            throw new IllegalArgumentException("非法任务类型: " + task.getType());
        if (task.getRepositoryId() == null || task.getRepositoryId().isEmpty())
            throw new IllegalArgumentException("repositoryId 不能为空");

        if (Objects.equals(task.getType(), "document")) {
            if (task.getDocumentName() == null || task.getDocumentContent() == null)
                throw new IllegalArgumentException("文档任务参数不完整");
        } else {
            if (task.getFileName() == null || task.getFileContent() == null)
                throw new IllegalArgumentException("代码文件任务参数不完整");
        }
    }

    private void handleFailure(MemoryIndexTask task, Exception error, boolean isRetry) {
        Integer retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
        retry++;
        task.setRetryCount(retry);

        if (retry > maxRetry) {
            log.warn("{} 任务超过最大重试次数，进入DLQ: repo={}, type={}, retryCount={}", LOG_PREFIX, task.getRepositoryId(), task.getType(), retry);
            producer.sendToDlq(task, error);
        } else {
            log.info("{} 任务失败，发送到重试队列: repo={}, type={}, retryCount={}", LOG_PREFIX, task.getRepositoryId(), task.getType(), retry);
            producer.sendToRetry(task);
        }
    }
}


