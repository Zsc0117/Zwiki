package com.zwiki.util;

import com.zwiki.service.auth.SaTokenUserContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;

/**
 * 认证工具类
 * 统一从SaTokenUserContext获取用户信息
 *
 * @author zwiki
 */
public class AuthUtil {

    private AuthUtil() {
        // 工具类不允许实例化
    }

    /**
     * 获取当前用户ID - 核心方法
     * 直接从SaTokenUserContext获取，这是最可靠的方式
     */
    public static String getCurrentUserId() {
        // 直接从SaTokenUserContext获取（与pai4j的SessionHelper.getCurrentUserId()一致）
        String userId = SaTokenUserContext.getCurrentUserId();
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        
        // Fallback: 如果SaTokenUserContext没有，尝试从OAuth2User获取（OAuth2登录后立即可用）
        OAuth2User user = getCurrentOAuth2User();
        if (user != null) {
            Object v = user.getAttributes().get("zwikiUserId");
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /**
     * 获取当前Token
     */
    public static String getCurrentToken() {
        return SaTokenUserContext.getToken();
    }

    /**
     * 获取当前客户端IP
     */
    public static String getCurrentClientIP() {
        return SaTokenUserContext.getCurrentClientIP();
    }

    /**
     * 获取当前客户端UserAgent
     */
    public static String getCurrentUserAgent() {
        return SaTokenUserContext.getCurrentUserAgent();
    }

    /**
     * 检查用户是否已登录
     */
    public static boolean isAuthenticated() {
        return StringUtils.hasText(getCurrentUserId());
    }

    /**
     * 获取当前OAuth2用户（用于OAuth2登录后立即获取用户信息）
     */
    public static OAuth2User getCurrentOAuth2User() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User oAuth2User) {
            return oAuth2User;
        }
        return null;
    }

    /**
     * 获取当前GitHub登录名
     */
    public static String getCurrentGithubLogin() {
        OAuth2User user = getCurrentOAuth2User();
        if (user == null) {
            return null;
        }
        Object v = user.getAttributes().get("login");
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 获取当前用户名称
     */
    public static String getCurrentUserName() {
        OAuth2User user = getCurrentOAuth2User();
        if (user == null) {
            return null;
        }
        Object v = user.getAttributes().get("name");
        if (v != null) {
            return String.valueOf(v);
        }
        // fallback to login
        v = user.getAttributes().get("login");
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 获取当前用户头像URL
     */
    public static String getCurrentUserAvatarUrl() {
        OAuth2User user = getCurrentOAuth2User();
        if (user == null) {
            return null;
        }
        Object v = user.getAttributes().get("avatar_url");
        return v != null ? String.valueOf(v) : null;
    }
}
