package com.zwiki.service;

import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.util.RedisUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 任务排队服务
 * 使用Redis ZSet追踪任务排队顺序，计算排队位置和预计等待时间
 *
 * @author zwiki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueueService {

    private final RedisUtil redisUtil;

    private static final int AVG_DURATION_SAMPLE_SIZE = 50;
    private static final int DEFAULT_DURATION_SECONDS = 600; // 10分钟

    /**
     * 任务入队
     */
    public void enqueueTask(String taskId) {
        try {
            String queueKey = RedisKeyEnum.TASK_QUEUE_ZSET.getKey();
            String timeKey = RedisKeyEnum.TASK_ENQUEUE_TIME.getKey();
            long now = System.currentTimeMillis();
            redisUtil.zadd(queueKey, taskId, now);
            redisUtil.hset(timeKey, taskId, String.valueOf(now));
            log.info("任务入队: taskId={}, timestamp={}", taskId, now);
        } catch (Exception e) {
            log.error("任务入队失败: taskId={}", taskId, e);
        }
    }

    /**
     * 任务开始处理（从等待队列移到处理中集合）
     */
    public void markTaskStarted(String taskId) {
        try {
            String queueKey = RedisKeyEnum.TASK_QUEUE_ZSET.getKey();
            String processingKey = RedisKeyEnum.TASK_PROCESSING_SET.getKey();
            redisUtil.zremove(queueKey, taskId);
            redisUtil.sadd(processingKey, taskId);
            log.info("任务开始处理: taskId={}", taskId);
        } catch (Exception e) {
            log.error("标记任务开始失败: taskId={}", taskId, e);
        }
    }

    /**
     * 任务完成/失败（从处理中移除，记录耗时）
     */
    public void markTaskFinished(String taskId) {
        try {
            String processingKey = RedisKeyEnum.TASK_PROCESSING_SET.getKey();
            String timeKey = RedisKeyEnum.TASK_ENQUEUE_TIME.getKey();
            String queueKey = RedisKeyEnum.TASK_QUEUE_ZSET.getKey();

            // 从处理中集合移除
            redisUtil.sremove(processingKey, taskId);
            // 也确保从等待队列移除（防御性）
            redisUtil.zremove(queueKey, taskId);

            // 计算并记录耗时
            String enqueueTimeStr = redisUtil.hget(timeKey, taskId);
            if (enqueueTimeStr != null) {
                long enqueueTime = Long.parseLong(enqueueTimeStr);
                long durationSeconds = (System.currentTimeMillis() - enqueueTime) / 1000;
                recordTaskDuration(durationSeconds);
                redisUtil.hdel(timeKey, taskId);
            }

            log.info("任务完成出队: taskId={}", taskId);
        } catch (Exception e) {
            log.error("标记任务完成失败: taskId={}", taskId, e);
        }
    }

    /**
     * 获取排队快照（排队位置、前方数量、预计等待时间）
     */
    public QueueSnapshot getQueueSnapshot(String taskId) {
        try {
            String queueKey = RedisKeyEnum.TASK_QUEUE_ZSET.getKey();

            // 获取任务在ZSet中的排名（0-based）
            Long rank = redisUtil.getRedisTemplate().opsForZSet().rank(queueKey, taskId);
            if (rank == null) {
                // 不在等待队列中（可能已开始处理）
                return new QueueSnapshot(0, 0, 0);
            }

            int position = rank.intValue() + 1; // 1-based
            int aheadCount = rank.intValue();
            long processingCount = getProcessingCount();
            int maxConcurrency = Math.max(2, (int) processingCount); // 至少假设2并发

            int avgDuration = getAverageDurationSeconds();
            // 预计等待 = 前方任务数 * 平均耗时 / 并发数
            int estimatedWaitSeconds = (int) ((long) aheadCount * avgDuration / Math.max(maxConcurrency, 1));
            int estimatedWaitMinutes = Math.max(1, (estimatedWaitSeconds + 59) / 60);

            return new QueueSnapshot(position, aheadCount, estimatedWaitMinutes);
        } catch (Exception e) {
            log.error("获取排队快照失败: taskId={}", taskId, e);
            return new QueueSnapshot(0, 0, 0);
        }
    }

    /**
     * 获取等待队列中的所有任务ID
     */
    public Set<String> getWaitingTaskIds() {
        try {
            String queueKey = RedisKeyEnum.TASK_QUEUE_ZSET.getKey();
            Long size = redisUtil.zsize(queueKey);
            if (size == null || size == 0) {
                return Set.of();
            }
            return redisUtil.zrange(queueKey, 0, size);
        } catch (Exception e) {
            log.error("获取等待任务列表失败", e);
            return Set.of();
        }
    }

    /**
     * 获取任务入队时间（毫秒时间戳）
     */
    public Long getEnqueueTime(String taskId) {
        try {
            String timeKey = RedisKeyEnum.TASK_ENQUEUE_TIME.getKey();
            String timeStr = redisUtil.hget(timeKey, taskId);
            return timeStr != null ? Long.parseLong(timeStr) : null;
        } catch (Exception e) {
            log.error("获取入队时间失败: taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 获取当前处理中的任务数
     */
    public long getProcessingCount() {
        try {
            String processingKey = RedisKeyEnum.TASK_PROCESSING_SET.getKey();
            Long size = redisUtil.ssize(processingKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取等待队列长度
     */
    public long getWaitingCount() {
        try {
            String queueKey = RedisKeyEnum.TASK_QUEUE_ZSET.getKey();
            Long size = redisUtil.zsize(queueKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 记录任务耗时
     */
    private void recordTaskDuration(long durationSeconds) {
        try {
            String durationKey = RedisKeyEnum.TASK_DURATION_LIST.getKey();
            redisUtil.lpush(durationKey, String.valueOf(durationSeconds));
            // 保留最近N条记录
            redisUtil.getRedisTemplate().opsForList().trim(durationKey, 0, AVG_DURATION_SAMPLE_SIZE - 1);
            redisUtil.expire(durationKey, RedisKeyEnum.TASK_DURATION_LIST.getExpireTime());
        } catch (Exception e) {
            log.warn("记录任务耗时失败: duration={}s", durationSeconds, e);
        }
    }

    /**
     * 获取平均任务耗时（秒）
     */
    private int getAverageDurationSeconds() {
        try {
            String durationKey = RedisKeyEnum.TASK_DURATION_LIST.getKey();
            java.util.List<String> durations = redisUtil.lrange(durationKey, 0, AVG_DURATION_SAMPLE_SIZE - 1);
            if (durations == null || durations.isEmpty()) {
                return DEFAULT_DURATION_SECONDS;
            }
            long total = 0;
            int count = 0;
            for (String d : durations) {
                try {
                    total += Long.parseLong(d);
                    count++;
                } catch (NumberFormatException ignore) {
                }
            }
            return count > 0 ? (int) (total / count) : DEFAULT_DURATION_SECONDS;
        } catch (Exception e) {
            return DEFAULT_DURATION_SECONDS;
        }
    }

    @Data
    public static class QueueSnapshot {
        private final int position;
        private final int aheadCount;
        private final int estimatedWaitMinutes;

        public QueueSnapshot(int position, int aheadCount, int estimatedWaitMinutes) {
            this.position = position;
            this.aheadCount = aheadCount;
            this.estimatedWaitMinutes = estimatedWaitMinutes;
        }
    }
}
