package com.zwiki.mcpserver.config;

import com.zwiki.mcpserver.repository.McpApiKeyRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API Key 认证过滤器。
 * 优先从数据库 zwiki_api_key 表验证，同时支持配置文件静态 key 作为后备。
 * 开发模式：若静态 key 为空且 DB 中也无任何 key，则跳过认证（方便本地开发）。
 * 传递方式：
 * 1. Header: Authorization: Bearer <api_key>
 * 2. URL 参数: ?Authorization=<api_key>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements Filter {

    private final McpApiKeyRepository mcpApiKeyRepository;

    private Set<String> staticKeys = Set.of();
    private volatile boolean devMode = false;

    @Value("${zwiki.mcp-server.api-keys:}")
    private String apiKeysConfig;

    @PostConstruct
    public void setup() {
        if (StringUtils.hasText(apiKeysConfig)) {
            staticKeys = Arrays.stream(apiKeysConfig.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
        }
        // 开发模式检测：静态 key 为空且 DB 中也无 key
        if (staticKeys.isEmpty()) {
            try {
                long dbCount = mcpApiKeyRepository.count();
                devMode = (dbCount == 0);
            } catch (Exception e) {
                log.warn("检测 DB API Key 数量失败，启用开发模式: {}", e.getMessage());
                devMode = true;
            }
        }
        if (devMode) {
            log.info("ZwikiAI MCP Server API Key 认证：开发模式（无静态密钥且 DB 无密钥，跳过认证）");
        } else {
            log.info("ZwikiAI MCP Server API Key 认证已启用（DB + {} 个静态密钥）", staticKeys.size());
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String path = httpReq.getRequestURI();

        // 健康检查、根路径、SSE 消息端点不需要认证
        // /sse/message 通过 sessionId 验证（SSE 连接时已认证）
        if ("/".equals(path) || "/health".equals(path) || "/actuator/health".equals(path)
                || path.startsWith("/sse/message")) {
            chain.doFilter(request, response);
            return;
        }

        // CORS 预检请求不需要认证
        if ("OPTIONS".equalsIgnoreCase(httpReq.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        log.debug("认证检查: path={}, method={}, remoteAddr={}", path, httpReq.getMethod(), httpReq.getRemoteAddr());

        // 开发模式：动态检测，一旦 DB 中有 key 就自动退出开发模式
        if (devMode) {
            try {
                if (mcpApiKeyRepository.count() > 0) {
                    devMode = false;
                    log.info("检测到 DB 中已有 API Key，退出开发模式，启用认证");
                }
            } catch (Exception ignored) {}
            if (devMode) {
                chain.doFilter(request, response);
                return;
            }
        }

        String apiKey = extractApiKey(httpReq);
        if (!StringUtils.hasText(apiKey)) {
            reject(httpResp, path, httpReq.getRemoteAddr());
            return;
        }

        // 优先检查静态 key
        if (staticKeys.contains(apiKey)) {
            chain.doFilter(request, response);
            return;
        }

        // 检查数据库
        boolean valid = mcpApiKeyRepository.existsByApiKey(apiKey);
        if (!valid) {
            reject(httpResp, path, httpReq.getRemoteAddr());
            return;
        }

        // 更新 last_used_at
        try {
            mcpApiKeyRepository.updateLastUsedAt(apiKey, LocalDateTime.now());
        } catch (Exception e) {
            log.debug("更新 API Key 使用时间失败: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse httpResp, String path, String remoteAddr) throws IOException {
        log.warn("MCP 请求认证失败: path={}, remoteAddr={}", path, remoteAddr);
        httpResp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResp.setContentType("application/json;charset=UTF-8");
        httpResp.getWriter().write("{\"error\":\"Unauthorized. Please provide a valid API key via 'Authorization: Bearer <key>' header.\"}");
    }

    private String extractApiKey(HttpServletRequest request) {
        // 方式1: Authorization header
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader)) {
            if (authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7).trim();
            }
            return authHeader.trim();
        }

        // 方式2: URL 参数
        String paramKey = request.getParameter("Authorization");
        if (StringUtils.hasText(paramKey)) {
            return paramKey.trim();
        }

        return null;
    }
}
