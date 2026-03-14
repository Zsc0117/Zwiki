package com.zwiki.service.auth;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话Helper - 完全借鉴pai4j-server的SessionHelper实现
 * 使用ThreadLocal存储当前请求的用户上下文信息
 *
 * @author zwiki
 */
public class SaTokenUserContext {

    /**
     * ThreadLocal实体类，使用ConcurrentHashMap存储多个属性
     */
    private static final ThreadLocal<ConcurrentHashMap<String, Object>> THREAD_LOCAL_HOLDER = new ThreadLocal<>();

    /**
     * 当前用户ID的Key
     */
    public static final String CURRENT_USER_ID = "CURRENT_USER_ID";

    /**
     * Token的Key
     */
    private static final String SESSION_KEY_TOKEN = "TOKEN";

    /**
     * IP的Key
     */
    private static final String SESSION_KEY_IP = "CURRENT-IP";

    /**
     * UserAgent的Key
     */
    private static final String SESSION_KEY_USER_AGENT = "User-Agent";

    /**
     * TraceId的Key
     */
    private static final String SESSION_KEY_TRACE_ID = "TRACE-ID";

    /**
     * 设置客户端用户信息（在Filter中调用）
     *
     * @param userId    用户ID
     * @param ip        客户端IP
     * @param userAgent 客户端UA
     */
    public static void setClientUserInfo(String userId, String ip, String userAgent) {
        setUserId(userId);
        setClientIP(ip);
        setUserAgent(userAgent);
    }

    /**
     * 设置登录用户ID，谨慎使用！会覆盖原来的userId
     */
    public static void setUserId(String userId) {
        if (StringUtils.hasText(userId)) {
            setAttribute(CURRENT_USER_ID, userId);
        }
    }

    /**
     * 设置Token
     */
    public static void setToken(String token) {
        if (StringUtils.hasText(token)) {
            setAttribute(SESSION_KEY_TOKEN, token);
        }
    }

    /**
     * 设置客户端IP
     */
    public static void setClientIP(String ip) {
        if (StringUtils.hasText(ip)) {
            setAttribute(SESSION_KEY_IP, ip);
        }
    }

    /**
     * 设置客户端UA信息
     */
    public static void setUserAgent(String ua) {
        if (StringUtils.hasText(ua)) {
            setAttribute(SESSION_KEY_USER_AGENT, ua);
        }
    }

    /**
     * 设置TraceId
     */
    public static void setTraceId(String traceId) {
        if (StringUtils.hasText(traceId)) {
            setAttribute(SESSION_KEY_TRACE_ID, traceId);
        }
    }

    /**
     * 获取当前登录用户ID
     * 优先从ThreadLocal获取，如果没有则从Sa-Token获取
     */
    public static String getUserId() {
        String userId = safeGetAttribute(CURRENT_USER_ID);
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        // Fallback: 从Sa-Token获取
        try {
            if (StpUtil.isLogin()) {
                Object loginId = StpUtil.getLoginId();
                return loginId != null ? String.valueOf(loginId) : null;
            }
        } catch (Exception e) {
            // 忽略Sa-Token异常
        }
        return null;
    }

    /**
     * 获取当前登录用户ID（别名方法，与pai4j保持一致）
     */
    public static String getCurrentUserId() {
        return getUserId();
    }

    /**
     * 获取当前Token
     * 优先从ThreadLocal获取，如果没有则从Sa-Token获取
     */
    public static String getToken() {
        String token = safeGetAttribute(SESSION_KEY_TOKEN);
        if (StringUtils.hasText(token)) {
            return token;
        }
        // Fallback: 从Sa-Token获取
        try {
            return StpUtil.getTokenValue();
        } catch (Exception e) {
            // 忽略Sa-Token异常
        }
        return null;
    }

    /**
     * 获取客户端IP
     */
    public static String getCurrentClientIP() {
        return safeGetAttribute(SESSION_KEY_IP);
    }

    /**
     * 获取客户端UA信息
     */
    public static String getCurrentUserAgent() {
        return safeGetAttribute(SESSION_KEY_USER_AGENT);
    }

    /**
     * 获取TraceId
     */
    public static String getTraceId() {
        return safeGetAttribute(SESSION_KEY_TRACE_ID);
    }

    /**
     * 检查用户是否已登录
     */
    public static boolean isLoggedIn() {
        return StringUtils.hasText(getUserId());
    }

    /**
     * 清除本线程保存的数据，防止内存泄漏
     */
    public static void clear() {
        THREAD_LOCAL_HOLDER.remove();
    }

    /**
     * 获取本地的session Map
     */
    private static Map<String, Object> getLocalSession() {
        if (THREAD_LOCAL_HOLDER.get() == null) {
            THREAD_LOCAL_HOLDER.set(new ConcurrentHashMap<>());
        }
        return THREAD_LOCAL_HOLDER.get();
    }

    /**
     * 设置属性
     */
    private static void setAttribute(String key, Object value) {
        getLocalSession().put(key, value);
    }

    /**
     * 获取属性
     */
    private static Object getAttribute(String key) {
        return getLocalSession().get(key);
    }

    /**
     * 安全获取属性（转为String）
     */
    public static String safeGetAttribute(String key) {
        Object valueObj = getAttribute(key);
        return valueObj == null ? null : valueObj.toString();
    }
}
