package com.zwiki.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis工具类
 * 封装常用的Redis操作
 *
 * @author zwiki
 */
@Slf4j
@Component
public class RedisUtil {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== String 操作 ====================

    /**
     * get取值
     */
    public String get(final String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.error("Redis connection failed! key:{}, error:{}", key, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Redis get error! key:{}", key, e);
            return null;
        }
    }

    /**
     * 批量Get
     */
    public List<String> mget(final List<String> keys) {
        try {
            return redisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            log.error("Redis mget error! keys:{}", keys, e);
            return new ArrayList<>();
        }
    }

    /**
     * 带有过期时间的set方法
     *
     * @param key        键
     * @param value      值
     * @param expireTime 过期时间，单位为秒。如果大于0，则设置键值对的过期时间；如果小于等于0，则不设置过期时间
     * @return 操作结果
     */
    public boolean set(final String key, final String value, long expireTime) {
        boolean result = false;
        try {
            ValueOperations<String, String> operations = redisTemplate.opsForValue();
            if (expireTime > 0) {
                operations.set(key, value, expireTime, TimeUnit.SECONDS);
            } else {
                operations.set(key, value);
            }
            result = true;
        } catch (Exception e) {
            log.error("Redis set error! key:{}, value:{}", key, value, e);
        }
        return result;
    }

    /**
     * set传值（不带过期时间）
     */
    public boolean set(final String key, String value) {
        boolean result = false;
        try {
            redisTemplate.opsForValue().set(key, value);
            result = true;
        } catch (Exception e) {
            log.error("Redis set error! key:{}, value:{}", key, value, e);
        }
        return result;
    }

    /**
     * 设置值（仅当key不存在时）- 分布式锁
     *
     * @param key      键
     * @param value    值
     * @param exptime  过期时间（秒）
     * @return 是否设置成功
     */
    public boolean setNx(final String key, final String value, final long exptime) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return false;
        }
        try {
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, exptime, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis setNx error! key:{}", key, e);
            return false;
        }
    }

    /**
     * 删除缓存
     */
    public Boolean delete(String key) {
        try {
            return redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis delete error! key:{}", key, e);
            return false;
        }
    }

    /**
     * 删除缓存（别名方法）
     */
    public void remove(String key) {
        delete(key);
    }

    /**
     * 批量删除
     */
    public Long delete(Collection<String> keys) {
        try {
            return redisTemplate.delete(keys);
        } catch (Exception e) {
            log.error("Redis batch delete error! keys:{}", keys, e);
            return 0L;
        }
    }

    /**
     * 判断Key是否存在
     */
    public Boolean hasKey(String key) {
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("Redis hasKey error! key:{}", key, e);
            return false;
        }
    }

    /**
     * 设置过期时间
     *
     * @param key        缓存Key
     * @param expireTime 过期时间（秒）
     */
    public Boolean expire(String key, long expireTime) {
        try {
            return redisTemplate.expire(key, expireTime, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis expire error! key:{}", key, e);
            return false;
        }
    }

    /**
     * 获取剩余过期时间
     *
     * @return 剩余过期时间（秒），-1表示永不过期，-2表示Key不存在
     */
    public Long getExpire(String key) {
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis getExpire error! key:{}", key, e);
            return -2L;
        }
    }

    /**
     * 自增
     */
    public Long increment(String key) {
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.error("Redis increment error! key:{}", key, e);
            return null;
        }
    }

    /**
     * 自增指定值
     */
    public Long incrementBy(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("Redis incrementBy error! key:{}", key, e);
            return null;
        }
    }

    /**
     * 自增并设置过期时间
     */
    public Long increment(String key, Long expireTime) {
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redisTemplate.expire(key, expireTime, TimeUnit.SECONDS);
            }
            return value;
        } catch (Exception e) {
            log.error("Redis increment error! key:{}", key, e);
            return null;
        }
    }

    // ==================== Hash 操作 ====================

    /**
     * hset
     */
    public boolean hset(String key, String field, String value, long... expire) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(field)) {
            return false;
        }
        try {
            redisTemplate.opsForHash().put(key, field, value);
            if (expire.length > 0 && expire[0] > 0) {
                redisTemplate.expire(key, expire[0], TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis hset error! key:{}, field:{}", key, field, e);
            return false;
        }
    }

    /**
     * hget
     */
    public String hget(String key, String field) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(field)) {
            return null;
        }
        try {
            Object value = redisTemplate.opsForHash().get(key, field);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.error("Redis hget error! key:{}, field:{}", key, field, e);
            return null;
        }
    }

    /**
     * hdel
     */
    public Long hdel(String key, Object... fields) {
        try {
            return redisTemplate.opsForHash().delete(key, fields);
        } catch (Exception e) {
            log.error("Redis hdel error! key:{}", key, e);
            return 0L;
        }
    }

    /**
     * hgetall
     */
    public Map<Object, Object> hgetAll(String key) {
        try {
            return redisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            log.error("Redis hgetAll error! key:{}", key, e);
            return new HashMap<>();
        }
    }

    // ==================== Set 操作 ====================

    /**
     * sadd
     */
    public boolean sadd(String key, String... values) {
        try {
            redisTemplate.opsForSet().add(key, values);
            return true;
        } catch (Exception e) {
            log.error("Redis sadd error! key:{}", key, e);
            return false;
        }
    }

    /**
     * smembers
     */
    public Set<String> smembers(String key) {
        try {
            return redisTemplate.opsForSet().members(key);
        } catch (Exception e) {
            log.error("Redis smembers error! key:{}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * sismember
     */
    public Boolean sIsMember(String key, String value) {
        try {
            return redisTemplate.opsForSet().isMember(key, value);
        } catch (Exception e) {
            log.error("Redis sIsMember error! key:{}", key, e);
            return false;
        }
    }

    /**
     * srem
     */
    public Long sremove(String key, Object... values) {
        try {
            return redisTemplate.opsForSet().remove(key, values);
        } catch (Exception e) {
            log.error("Redis sremove error! key:{}", key, e);
            return 0L;
        }
    }

    /**
     * scard
     */
    public Long ssize(String key) {
        try {
            return redisTemplate.opsForSet().size(key);
        } catch (Exception e) {
            log.error("Redis ssize error! key:{}", key, e);
            return 0L;
        }
    }

    // ==================== ZSet 操作 ====================

    /**
     * zadd
     */
    public boolean zadd(String key, String value, double score) {
        try {
            redisTemplate.opsForZSet().add(key, value, score);
            return true;
        } catch (Exception e) {
            log.error("Redis zadd error! key:{}, value:{}", key, value, e);
            return false;
        }
    }

    /**
     * zadd with expire
     */
    public boolean zadd(String key, String value, double score, long expireTime) {
        try {
            redisTemplate.opsForZSet().add(key, value, score);
            if (expireTime > 0) {
                expire(key, expireTime);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis zadd error! key:{}, value:{}", key, value, e);
            return false;
        }
    }

    /**
     * zrange
     */
    public Set<String> zrange(String key, long start, long end) {
        try {
            return redisTemplate.opsForZSet().range(key, start, end - 1);
        } catch (Exception e) {
            log.error("Redis zrange error! key:{}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * zreverseRange
     */
    public Set<String> zreverseRange(String key, long start, long end) {
        try {
            return redisTemplate.opsForZSet().reverseRange(key, start, end - 1);
        } catch (Exception e) {
            log.error("Redis zreverseRange error! key:{}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * zscore
     */
    public Double zscore(String key, String value) {
        try {
            return redisTemplate.opsForZSet().score(key, value);
        } catch (Exception e) {
            log.error("Redis zscore error! key:{}, value:{}", key, value, e);
            return null;
        }
    }

    /**
     * zrem
     */
    public void zremove(String key, String value) {
        try {
            redisTemplate.opsForZSet().remove(key, value);
        } catch (Exception e) {
            log.error("Redis zremove error! key:{}", key, e);
        }
    }

    /**
     * zcard
     */
    public Long zsize(String key) {
        try {
            return redisTemplate.opsForZSet().size(key);
        } catch (Exception e) {
            log.error("Redis zsize error! key:{}", key, e);
            return 0L;
        }
    }

    // ==================== List 操作 ====================

    /**
     * lpush
     */
    public Long lpush(String key, String value) {
        try {
            return redisTemplate.opsForList().leftPush(key, value);
        } catch (Exception e) {
            log.error("Redis lpush error! key:{}", key, e);
            return 0L;
        }
    }

    /**
     * rpush
     */
    public Long rpush(String key, String value) {
        try {
            return redisTemplate.opsForList().rightPush(key, value);
        } catch (Exception e) {
            log.error("Redis rpush error! key:{}", key, e);
            return 0L;
        }
    }

    /**
     * lpop
     */
    public String lpop(String key) {
        try {
            return redisTemplate.opsForList().leftPop(key);
        } catch (Exception e) {
            log.error("Redis lpop error! key:{}", key, e);
            return null;
        }
    }

    /**
     * rpop
     */
    public String rpop(String key) {
        try {
            return redisTemplate.opsForList().rightPop(key);
        } catch (Exception e) {
            log.error("Redis rpop error! key:{}", key, e);
            return null;
        }
    }

    /**
     * lrange
     */
    public List<String> lrange(String key, long start, long end) {
        try {
            return redisTemplate.opsForList().range(key, start, end);
        } catch (Exception e) {
            log.error("Redis lrange error! key:{}", key, e);
            return Collections.emptyList();
        }
    }

    /**
     * llen
     */
    public Long llen(String key) {
        try {
            return redisTemplate.opsForList().size(key);
        } catch (Exception e) {
            log.error("Redis llen error! key:{}", key, e);
            return 0L;
        }
    }

    // ==================== 发布订阅 ====================

    /**
     * 发布消息
     */
    public void publish(String channel, String message) {
        try {
            redisTemplate.convertAndSend(channel, message);
        } catch (Exception e) {
            log.error("Redis publish error! channel:{}", channel, e);
        }
    }

    // ==================== JSON 序列化辅助 ====================

    /**
     * 设置JSON对象
     */
    public <T> boolean setJson(String key, T obj, long expireTime) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            return set(key, json, expireTime);
        } catch (JsonProcessingException e) {
            log.error("Redis setJson error! key:{}", key, e);
            return false;
        }
    }

    /**
     * 获取JSON对象
     */
    public <T> T getJson(String key, Class<T> clazz) {
        try {
            String json = get(key);
            if (StringUtils.hasText(json)) {
                return objectMapper.readValue(json, clazz);
            }
            return null;
        } catch (Exception e) {
            log.error("Redis getJson error! key:{}", key, e);
            return null;
        }
    }

    /**
     * 获取原始RedisTemplate（供特殊场景使用）
     */
    public StringRedisTemplate getRedisTemplate() {
        return redisTemplate;
    }
}
