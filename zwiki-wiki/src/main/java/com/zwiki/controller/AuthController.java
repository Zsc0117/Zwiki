package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.service.auth.HttpCookieOAuth2AuthorizationRequestRepository;
import com.zwiki.service.auth.SaTokenUserContext;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.ZwikiOAuthAccount;
import com.zwiki.repository.entity.ZwikiUser;
import com.zwiki.repository.dao.ZwikiOAuthAccountMapper;
import com.zwiki.repository.dao.ZwikiUserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import com.zwiki.util.RedisUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("github", "gitee");
    private static final String BIND_REDIS_PREFIX = "oauth:bind:";
    private static final long BIND_REDIS_TTL = 300; // 5 minutes

    private final ZwikiOAuthAccountMapper oauthAccountMapper;
    private final ZwikiUserRepository userMapper;
    private final RedisUtil redisUtil;

    @GetMapping("/me")
    public ResultVo<Map<String, Object>> me(HttpServletRequest request) {
        // 优先从Sa-Token上下文获取用户信息
        String userId = SaTokenUserContext.getUserId();
        if (userId != null) {
            return getUserInfoByUserId(userId, request);
        }

        // Fallback到Spring Security OAuth2
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResultVo.error(401, "未登录");
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof OAuth2User oAuth2User)) {
            return ResultVo.error(401, "未登录");
        }

        Map<String, Object> attrs = oAuth2User.getAttributes();
        Map<String, Object> resp = new HashMap<>();
        resp.put("userId", attrs.get("zwikiUserId"));
        Object provider = attrs.get("zwikiProvider");
        resp.put("provider", provider != null ? provider : "github");
        resp.put("providerUserId", attrs.get("id"));
        resp.put("login", attrs.get("login"));
        resp.put("name", attrs.get("name"));
        resp.put("avatarUrl", attrs.get("avatar_url"));
        resp.put("email", attrs.get("email"));
        Object role = attrs.get("zwikiRole");
        resp.put("role", role != null ? role : "USER");
        resp.put("isAdmin", role != null && "ADMIN".equalsIgnoreCase(String.valueOf(role).trim()));
        // 添加token信息
        String token = AuthUtil.getCurrentToken();
        if (token != null) {
            resp.put("token", token);
            resp.put("tokenName", "satoken");
        }
        return ResultVo.success(resp);
    }

    /**
     * 根据userId从数据库获取用户信息
     */
    private ResultVo<Map<String, Object>> getUserInfoByUserId(String userId, HttpServletRequest request) {
        Optional<ZwikiUser> userOpt = userMapper.findFirstByUserId(userId);
        if (userOpt.isEmpty()) {
            return ResultVo.error(401, "用户不存在");
        }
        
        ZwikiUser user = userOpt.get();
        Map<String, Object> resp = new HashMap<>();
        resp.put("userId", user.getUserId());
        resp.put("name", user.getDisplayName());
        resp.put("avatarUrl", user.getAvatarUrl());
        resp.put("email", user.getEmail());
        resp.put("role", user.getRole() != null ? user.getRole() : "USER");
        resp.put("isAdmin", "ADMIN".equalsIgnoreCase(user.getRole()));
        
        // 获取关联的OAuth账号信息
        List<ZwikiOAuthAccount> accounts = oauthAccountMapper.findAllByUserId(userId);
        if (!accounts.isEmpty()) {
            ZwikiOAuthAccount primaryAccount = accounts.get(0);
            resp.put("provider", primaryAccount.getProvider());
            resp.put("providerUserId", primaryAccount.getProviderUserId());
            resp.put("login", primaryAccount.getLogin());
        }
        
        String loginProvider = resolveLoginProviderFromCookie(request);
        if (loginProvider != null) {
            resp.put("loginProvider", loginProvider);
        }
        
        // 添加token信息
        String token = AuthUtil.getCurrentToken();
        if (token != null) {
            resp.put("token", token);
            resp.put("tokenName", "satoken");
        }
        
        return ResultVo.success(resp);
    }

    @GetMapping("/bindstart/{provider}")
    public void bindStart(@PathVariable("provider") String provider,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"msg\":\"不支持的登录方式: " + provider + "\"}");
            return;
        }

        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"未登录\"}");
            return;
        }

        // Generate unique bind key and store in Redis (more reliable than cookies through gateway)
        String bindKey = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String redisKey = BIND_REDIS_PREFIX + bindKey;
        redisUtil.set(redisKey, userId, BIND_REDIS_TTL);
        
        // Also set cookies as backup and pass bind key through OAuth flow
        HttpCookieOAuth2AuthorizationRequestRepository.saveBindMode(response, userId, bindKey);

        log.info("开始绑定{}账号: userId={}, bindKey={}, 已存储到Redis并设置Cookie", provider, userId, bindKey);
        response.sendRedirect("/oauth2/authorization/" + provider);
    }

    private String resolveLoginProviderFromCookie(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if ("ZWIKI_LOGIN_PROVIDER".equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @GetMapping("/linked-accounts")
    public ResultVo<List<Map<String, Object>>> linkedAccounts() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        List<ZwikiOAuthAccount> accounts = oauthAccountMapper.findAllByUserId(userId);
        List<Map<String, Object>> result = accounts.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("provider", a.getProvider());
            m.put("login", a.getLogin());
            m.put("name", a.getName());
            m.put("avatarUrl", a.getAvatarUrl());
            m.put("email", a.getEmail());
            m.put("lastLoginTime", a.getLastLoginTime());
            m.put("createTime", a.getCreateTime());
            return m;
        }).collect(Collectors.toList());

        return ResultVo.success(result);
    }

    @PostMapping("/unbind/{provider}")
    public ResultVo<String> unbind(@PathVariable("provider") String provider) {
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            return ResultVo.error("不支持的登录方式: " + provider);
        }

        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        long count = oauthAccountMapper.countByUserId(userId);
        if (count <= 1) {
            return ResultVo.error("至少保留一个登录方式，无法解绑");
        }

        oauthAccountMapper.deleteByUserIdAndProvider(userId, provider);
        log.info("解绑{}账号: userId={}", provider, userId);
        return ResultVo.success("解绑成功");
    }
}
