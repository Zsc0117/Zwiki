package com.zwiki.common.enums;

import org.springframework.util.StringUtils;

/**
 * Redis Key 枚举 - 统一管理Redis缓存Key和过期时间
 * 
 * Key命名规范: ZWIKI_{功能模块}_{具体业务}
 * 完整Key格式: ZWIKI_{枚举名}_{suffix1}_{suffix2}...
 *
 * @author zwiki
 */
public enum RedisKeyEnum {

    // ==================== 认证相关 ====================
    
    /**
     * Token与UserId映射缓存
     * 过期时间应与sa-token的timeout保持一致（86400秒 = 1天）
     */
    TOKEN_USER_CACHE("TOKEN_USER_CACHE", 86400L),

    /**
     * OAuth状态缓存（防止CSRF）: 5分钟有效
     */
    OAUTH_STATE_CACHE("OAUTH_STATE_CACHE", 5 * 60L),

    // ==================== 用户相关 ====================
    
    /**
     * 用户信息缓存: 7天有效
     */
    USER_INFO_CACHE("USER_INFO_CACHE", 7 * 24 * 60 * 60L),

    /**
     * 用户设置缓存: 1天有效
     */
    USER_SETTINGS_CACHE("USER_SETTINGS_CACHE", 24 * 60 * 60L),

    // ==================== 任务相关 ====================
    
    /**
     * 任务进度缓存: 30分钟有效
     */
    TASK_PROGRESS_CACHE("TASK_PROGRESS_CACHE", 30 * 60L),

    /**
     * 任务详情缓存: 1小时有效
     */
    TASK_DETAIL_CACHE("TASK_DETAIL_CACHE", 60 * 60L),

    /**
     * 任务列表缓存: 10分钟有效
     */
    TASK_LIST_CACHE("TASK_LIST_CACHE", 10 * 60L),

    // ==================== 文档相关 ====================
    
    /**
     * 目录列表缓存: 1小时有效
     */
    CATALOGUE_LIST_CACHE("CATALOGUE_LIST_CACHE", 60 * 60L),

    /**
     * 文档内容缓存: 30分钟有效
     */
    DOCUMENT_CONTENT_CACHE("DOCUMENT_CONTENT_CACHE", 30 * 60L),

    // ==================== LLM相关 ====================
    
    /**
     * LLM配额警告缓存: 1小时有效
     * 用于防止重复发送配额警告通知
     * 格式: ZWIKI_LLM_QUOTA_WARNING_CACHE_{userId}_{modelName}_{threshold}
     */
    LLM_QUOTA_WARNING_CACHE("LLM_QUOTA_WARNING_CACHE", 60 * 60L),

    /**
     * LLM模型健康状态缓存: 5分钟有效
     * 格式: ZWIKI_LLM_MODEL_HEALTH_CACHE_{appName}
     */
    LLM_MODEL_HEALTH_CACHE("LLM_MODEL_HEALTH_CACHE", 5 * 60L),

    /**
     * LLM模型不健康状态 (Hash结构)
     * 存储模型的不健康状态，key为模型名，value为恢复时间戳
     * 格式: ZWIKI_LLM_MODEL_UNHEALTHY_{appName}
     * 永不过期（由业务逻辑控制模型恢复）
     */
    LLM_MODEL_UNHEALTHY("LLM_MODEL_UNHEALTHY", -1L),

    /**
     * LLM模型统计信息 (Hash结构)
     * 存储每日的调用次数、错误次数、token消耗等
     * 格式: ZWIKI_LLM_MODEL_STATS_{appName}_{date}
     * 7天有效
     */
    LLM_MODEL_STATS("LLM_MODEL_STATS", 7 * 24 * 60 * 60L),

    /**
     * LLM模型最后使用时间 (Hash结构)
     * 存储每个模型的最后使用时间戳
     * 格式: ZWIKI_LLM_MODEL_LAST_USED_{appName}
     * 7天有效
     */
    LLM_MODEL_LAST_USED("LLM_MODEL_LAST_USED", 7 * 24 * 60 * 60L),

    // ==================== 通知相关 ====================
    
    /**
     * 通知未读数缓存: 永不过期
     */
    NOTIFICATION_UNREAD_COUNT_CACHE("NOTIFICATION_UNREAD_COUNT_CACHE", -1L),

    /**
     * WebSocket在线用户缓存: 1天有效
     */
    WS_ONLINE_USER_CACHE("WS_ONLINE_USER_CACHE", 24 * 60 * 60L),

    // ==================== 队列相关 ====================
    
    /**
     * 文档生成队列
     */
    QUEUE_DOC_GENERATION("QUEUE_DOC_GENERATION", -1L),

    /**
     * 内存索引队列
     */
    QUEUE_MEM_INDEX("QUEUE_MEM_INDEX", -1L),

    // ==================== 任务排队 ====================

    /**
     * 任务排队有序集合 (ZSet，score=入队时间戳)
     * 格式: ZWIKI_TASK_QUEUE_ZSET
     * 永不过期（由业务逻辑控制）
     */
    TASK_QUEUE_ZSET("TASK_QUEUE_ZSET", -1L),

    /**
     * 任务处理中集合 (Set)
     * 格式: ZWIKI_TASK_PROCESSING_SET
     * 永不过期
     */
    TASK_PROCESSING_SET("TASK_PROCESSING_SET", -1L),

    /**
     * 任务历史耗时列表 (List, 存最近N条完成耗时秒数)
     * 格式: ZWIKI_TASK_DURATION_LIST
     * 7天有效
     */
    TASK_DURATION_LIST("TASK_DURATION_LIST", 7 * 24 * 60 * 60L),

    /**
     * 任务入队时间缓存 (Hash, field=taskId, value=入队时间戳)
     * 格式: ZWIKI_TASK_ENQUEUE_TIME
     * 永不过期
     */
    TASK_ENQUEUE_TIME("TASK_ENQUEUE_TIME", -1L),

    /**
     * 邮件发送失败去重缓存: 1小时有效
     * 格式: ZWIKI_MAIL_FAIL_DEDUP_{userId}_{taskId}
     */
    MAIL_FAIL_DEDUP("MAIL_FAIL_DEDUP", 60 * 60L),

    /**
     * 排队超时提醒冷却缓存
     * 格式: ZWIKI_QUEUE_TIMEOUT_COOLDOWN_{taskId}
     */
    QUEUE_TIMEOUT_COOLDOWN("QUEUE_TIMEOUT_COOLDOWN", 60 * 60L),

    // ==================== 分布式锁 ====================
    
    /**
     * 任务执行锁: 10分钟有效
     */
    TASK_EXECUTE_LOCK("TASK_EXECUTE_LOCK", 10 * 60L),

    /**
     * 文档生成锁: 30分钟有效
     */
    DOC_GENERATION_LOCK("DOC_GENERATION_LOCK", 30 * 60L),

    // ==================== 限流相关 ====================
    
    /**
     * API请求限流: 1分钟有效
     */
    API_RATE_LIMIT("API_RATE_LIMIT", 60L),

    /**
     * 用户操作限流: 10秒有效
     */
    USER_OPERATION_LIMIT("USER_OPERATION_LIMIT", 10L),

    ;

    /**
     * Redis Key前缀
     */
    private static final String REDIS_KEY_PREFIX = "ZWIKI_";

    /**
     * Key名称
     */
    private final String key;

    /**
     * 有效时间，单位秒。-1表示永不过期
     */
    private final long expireTime;

    RedisKeyEnum(String key, Long expireTime) {
        this.key = key;
        this.expireTime = expireTime;
    }

    public String getKey() {
        return key;
    }

    public long getExpireTime() {
        return expireTime;
    }

    /**
     * 根据给定的后缀生成完整的Redis Key
     * 
     * 示例:
     * TOKEN_USER_CACHE.getKey("abc123") => "ZWIKI_TOKEN_USER_CACHE_abc123"
     * USER_INFO_CACHE.getKey("user1", "detail") => "ZWIKI_USER_INFO_CACHE_user1_detail"
     *
     * @param suffix 可变长参数，代表Key的后缀部分
     * @return 完整的Redis Key
     */
    public String getKey(String... suffix) {
        StringBuilder tmpSuffix = new StringBuilder();
        if (suffix != null && suffix.length > 0) {
            for (String str : suffix) {
                if (StringUtils.hasText(str)) {
                    tmpSuffix.append("_").append(str);
                }
            }
        }
        return REDIS_KEY_PREFIX + this.name() + tmpSuffix;
    }
}
