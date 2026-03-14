package com.zwiki.service.auth;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sa-Token认证过滤器 - 完全借鉴pai4j-server的OauthFilter实现
 * 从请求中提取token并验证用户身份，设置完整的用户上下文
 *
 * @author zwiki
 */
@Slf4j
@Component
@Order(-200)  // Must run BEFORE Spring Security filters (which have order around -100)
public class SaTokenAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        log.debug("Processing request: {}", uri);

        // 使用Sa-Token获取当前登录用户ID
        String userId = null;
        String token = null;
        try {
            if (StpUtil.isLogin()) {
                Object loginId = StpUtil.getLoginId();
                userId = loginId != null ? String.valueOf(loginId) : null;
                token = StpUtil.getTokenValue();
            }
        } catch (Exception e) {
            log.debug("Sa-Token check failed: {}", e.getMessage());
        }

        // 获取客户端IP和UserAgent
        String ip = getRemoteIpByRequest(request);
        String userAgent = request.getHeader("User-Agent");

        if (StringUtils.hasText(userId)) {
            // 用户已认证，设置完整的用户上下文
            SaTokenUserContext.setClientUserInfo(userId, ip, userAgent);
            if (StringUtils.hasText(token)) {
                SaTokenUserContext.setToken(token);
            }
            
            // 设置Spring Security上下文，确保Sa-Token有效时Spring Security的.authenticated()检查通过
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userId, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Set Spring Security context for userId: {}", userId);
            }
            
            log.debug("User authenticated via Sa-Token - userId: {}, ip: {}", userId, ip);
            
            try {
                filterChain.doFilter(request, response);
            } finally {
                // 清理上下文，防止内存泄漏
                SaTokenUserContext.clear();
            }
        } else {
            // 用户未认证，继续执行（让Spring Security处理认证）
            filterChain.doFilter(request, response);
        }
    }

    /**
     * 获取客户端真实IP - 借鉴pai4j的RemoteIpUtil
     */
    private String getRemoteIpByRequest(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果有多个IP，取第一个
        if (StringUtils.hasText(ip) && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

}
