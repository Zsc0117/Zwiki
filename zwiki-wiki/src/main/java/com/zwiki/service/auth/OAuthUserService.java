package com.zwiki.service.auth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.dao.ZwikiOAuthAccountMapper;
import com.zwiki.repository.dao.ZwikiUserRepository;
import com.zwiki.repository.entity.ZwikiOAuthAccount;
import com.zwiki.repository.entity.ZwikiUser;
import com.zwiki.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author pai
 * @description: OAuth用户服务
 * @date 2026/1/20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String OAUTH_BIND_MODE = "OAUTH_BIND_MODE";
    public static final String OAUTH_BIND_USER_ID = "OAUTH_BIND_USER_ID";

    private static final String BIND_REDIS_PREFIX = "oauth:bind:";

    private final ZwikiOAuthAccountMapper oauthAccountMapper;
    private final ZwikiUserRepository zwikiUserRepository;
    private final ObjectMapper objectMapper;
    private final TokenCryptoService tokenCryptoService;
    private final RedisUtil redisUtil;

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = delegate.loadUser(userRequest);

        String provider = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attrs = oAuth2User.getAttributes();

        String providerUserId = attrs.get("id") != null ? String.valueOf(attrs.get("id")) : null;
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new OAuth2AuthenticationException(provider + " OAuth登录失败：缺少用户id字段");
        }

        String login = attrs.get("login") != null ? String.valueOf(attrs.get("login")) : null;
        String name = attrs.get("name") != null ? String.valueOf(attrs.get("name")) : null;
        String avatarUrl = attrs.get("avatar_url") != null ? String.valueOf(attrs.get("avatar_url")) : null;
        String email = attrs.get("email") != null ? String.valueOf(attrs.get("email")) : null;

        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(attrs);
        } catch (Exception e) {
            rawJson = "{}";
        }

        LocalDateTime now = LocalDateTime.now();

        OAuth2AccessToken accessToken = userRequest.getAccessToken();
        String accessTokenValue = accessToken != null ? accessToken.getTokenValue() : null;
        String storedAccessToken = tokenCryptoService.encryptIfEnabled(accessTokenValue);
        String tokenScopes = (accessToken != null && accessToken.getScopes() != null && !accessToken.getScopes().isEmpty())
                ? String.join(",", accessToken.getScopes())
                : null;
        LocalDateTime tokenExpiresAt = (accessToken != null && accessToken.getExpiresAt() != null)
                ? LocalDateTime.ofInstant(accessToken.getExpiresAt(), ZoneId.systemDefault())
                : null;

        // Bind mode is explicitly marked by bind-start endpoint through short-lived cookies.
        String bindUserId = resolveBindUserIdFromCookie();
        boolean bindMode = bindUserId != null && !bindUserId.isBlank();
        if (bindMode) {
            log.info("检测到绑定模式: bindUserId={}", bindUserId);
        }

        if (bindMode && bindUserId != null) {
            return handleBindMode(provider, providerUserId, bindUserId, login, name, avatarUrl, email,
                    storedAccessToken, tokenScopes, tokenExpiresAt, rawJson, now, attrs);
        }

        return handleLoginMode(provider, providerUserId, login, name, avatarUrl, email,
                storedAccessToken, tokenScopes, tokenExpiresAt, rawJson, now, attrs);
    }

    private OAuth2User handleBindMode(String provider, String providerUserId, String bindUserId,
                                       String login, String name, String avatarUrl, String email,
                                       String storedAccessToken, String tokenScopes, LocalDateTime tokenExpiresAt,
                                       String rawJson, LocalDateTime now, Map<String, Object> attrs) {
        // Check if this provider account is already bound to another user
        ZwikiOAuthAccount existing = oauthAccountMapper
                .findFirstByProviderAndProviderUserId(provider, providerUserId)
                .orElse(null);

        if (existing != null && !existing.getUserId().equals(bindUserId)) {
            throw new OAuth2AuthenticationException("该" + provider + "账号已被其他用户绑定");
        }

        if (existing != null && existing.getUserId().equals(bindUserId)) {
            // Already bound to this user, just update
            existing.setLastLoginTime(now);
            existing.setLogin(login);
            existing.setName(name);
            existing.setAvatarUrl(avatarUrl);
            existing.setEmail(email);
            existing.setAccessToken(storedAccessToken);
            existing.setTokenScopes(tokenScopes);
            existing.setTokenExpiresAt(tokenExpiresAt);
            existing.setRawJson(rawJson);
            oauthAccountMapper.save(existing);
            log.info("{}账号已绑定，更新信息: userId={}, login={}", provider, bindUserId, login);
        } else {
            // Check if user already has an account with this provider
            ZwikiOAuthAccount existingForUser = oauthAccountMapper
                    .findFirstByUserIdAndProvider(bindUserId, provider)
                    .orElse(null);
            if (existingForUser != null) {
                // Update existing binding
                existingForUser.setProviderUserId(providerUserId);
                existingForUser.setLastLoginTime(now);
                existingForUser.setLogin(login);
                existingForUser.setName(name);
                existingForUser.setAvatarUrl(avatarUrl);
                existingForUser.setEmail(email);
                existingForUser.setAccessToken(storedAccessToken);
                existingForUser.setTokenScopes(tokenScopes);
                existingForUser.setTokenExpiresAt(tokenExpiresAt);
                existingForUser.setRawJson(rawJson);
                oauthAccountMapper.save(existingForUser);
            } else {
                // Create new binding
                ZwikiOAuthAccount account = ZwikiOAuthAccount.builder()
                        .userId(bindUserId)
                        .provider(provider)
                        .providerUserId(providerUserId)
                        .login(login)
                        .name(name)
                        .avatarUrl(avatarUrl)
                        .email(email)
                        .accessToken(storedAccessToken)
                        .tokenScopes(tokenScopes)
                        .tokenExpiresAt(tokenExpiresAt)
                        .rawJson(rawJson)
                        .lastLoginTime(now)
                        .build();
                oauthAccountMapper.save(account);
            }
            log.info("绑定{}账号: userId={}, login={}", provider, bindUserId, login);
        }

        // Return OAuth2User for the bind user (keep the current session user)
        ZwikiUser user = zwikiUserRepository.findFirstByUserId(bindUserId).orElse(null);
        if (user == null) {
            throw new OAuth2AuthenticationException("绑定失败：用户不存在");
        }

        return buildOAuth2User(user, provider, attrs, true);
    }

    private OAuth2User handleLoginMode(String provider, String providerUserId,
                                        String login, String name, String avatarUrl, String email,
                                        String storedAccessToken, String tokenScopes, LocalDateTime tokenExpiresAt,
                                        String rawJson, LocalDateTime now, Map<String, Object> attrs) {
        ZwikiOAuthAccount account = oauthAccountMapper
                .findFirstByProviderAndProviderUserId(provider, providerUserId)
                .orElse(null);

        String providerLabel = "github".equals(provider) ? "GitHub" : ("gitee".equals(provider) ? "Gitee" : provider);
        ZwikiUser user;

        if (account == null) {
            // First-time login with this provider — create new user
            String newUserId = UUID.randomUUID().toString().replace("-", "");
            user = ZwikiUser.builder()
                    .userId(newUserId)
                    .displayName(name != null && !name.isBlank() ? name : (login != null ? login : providerLabel + "用户"))
                    .avatarUrl(avatarUrl)
                    .email(email)
                    .status("active")
                    .lastLoginTime(now)
                    .build();
            zwikiUserRepository.save(user);

            account = ZwikiOAuthAccount.builder()
                    .userId(newUserId)
                    .provider(provider)
                    .providerUserId(providerUserId)
                    .login(login)
                    .name(name)
                    .avatarUrl(avatarUrl)
                    .email(email)
                    .accessToken(storedAccessToken)
                    .tokenScopes(tokenScopes)
                    .tokenExpiresAt(tokenExpiresAt)
                    .rawJson(rawJson)
                    .lastLoginTime(now)
                    .build();
            oauthAccountMapper.save(account);

            log.info("{}首次登录创建用户: userId={}, login={}", providerLabel, newUserId, login);
        } else {
            user = zwikiUserRepository.findFirstByUserId(account.getUserId()).orElse(null);
            if (user == null) {
                user = ZwikiUser.builder()
                        .userId(account.getUserId())
                        .displayName(name != null && !name.isBlank() ? name : (login != null ? login : providerLabel + "用户"))
                        .avatarUrl(avatarUrl)
                        .email(email)
                        .status("active")
                        .lastLoginTime(now)
                        .build();
                zwikiUserRepository.save(user);
            } else {
                user.setLastLoginTime(now);
                if (name != null && !name.isBlank()) {
                    user.setDisplayName(name);
                } else if (login != null && !login.isBlank() && (user.getDisplayName() == null || user.getDisplayName().isBlank())) {
                    user.setDisplayName(login);
                }
                if (avatarUrl != null && !avatarUrl.isBlank()) {
                    user.setAvatarUrl(avatarUrl);
                }
                if (email != null && !email.isBlank()) {
                    user.setEmail(email);
                }
                zwikiUserRepository.save(user);
            }

            account.setLastLoginTime(now);
            account.setLogin(login);
            account.setName(name);
            account.setAvatarUrl(avatarUrl);
            account.setEmail(email);
            account.setAccessToken(storedAccessToken);
            account.setTokenScopes(tokenScopes);
            account.setTokenExpiresAt(tokenExpiresAt);
            account.setRawJson(rawJson);
            oauthAccountMapper.save(account);
        }

        return buildOAuth2User(user, provider, attrs, false);
    }

    private String resolveBindUserIdFromCookie() {
        try {
            HttpCookieOAuth2AuthorizationRequestRepository.BindContext bindContext =
                    HttpCookieOAuth2AuthorizationRequestRepository.consumeBindContext();
            if (bindContext != null) {
                log.info("[resolveBindUserIdFromCookie] 从ThreadLocal读取绑定上下文: bindUserId={}, bindKey={}",
                        bindContext.getBindUserId(), bindContext.getBindKey());
                String bindUserIdFromRedis = resolveBindUserIdByBindKey(bindContext.getBindKey());
                if (StringUtils.hasText(bindUserIdFromRedis)) {
                    return bindUserIdFromRedis;
                }
                if (StringUtils.hasText(bindContext.getBindUserId())) {
                    return bindContext.getBindUserId();
                }
            }

            ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (requestAttributes == null) {
                log.warn("[resolveBindUserIdFromCookie] RequestContextHolder.getRequestAttributes() 返回 null，且未命中绑定上下文");
                return null;
            }
            HttpServletRequest request = requestAttributes.getRequest();
            
            // Log all cookies for debugging
            if (request.getCookies() != null) {
                log.info("[resolveBindUserIdFromCookie] 请求中共有 {} 个Cookie", request.getCookies().length);
                for (Cookie c : request.getCookies()) {
                    if (c.getName().startsWith("ZWIKI") || c.getName().equals("satoken")) {
                        log.info("[resolveBindUserIdFromCookie] Cookie: {}={}", c.getName(), 
                                c.getValue() != null && c.getValue().length() > 20 
                                    ? c.getValue().substring(0, 20) + "..." : c.getValue());
                    }
                }
            } else {
                log.warn("[resolveBindUserIdFromCookie] 请求中没有Cookie");
            }
            
            // Priority 1: Try to get bindKey from cookie or OAuth request, then lookup in Redis
            String bindKey = HttpCookieOAuth2AuthorizationRequestRepository.getBindKey(request);
            if (bindKey == null || bindKey.isBlank()) {
                bindKey = HttpCookieOAuth2AuthorizationRequestRepository.getBindKeyFromAuthorizationRequest(request);
            }
            log.info("[resolveBindUserIdFromCookie] bindKey: {}", bindKey);

            String bindUserIdFromRedis = resolveBindUserIdByBindKey(bindKey);
            if (StringUtils.hasText(bindUserIdFromRedis)) {
                return bindUserIdFromRedis;
            }
            
            // Priority 2: Try direct cookie (fallback)
            String bindUserId = HttpCookieOAuth2AuthorizationRequestRepository.getBindUserId(request);
            log.info("[resolveBindUserIdFromCookie] 从直接Cookie读取bindUserId: {}", bindUserId);
            if (StringUtils.hasText(bindUserId)) {
                return bindUserId;
            }
            
            // Priority 3: Try OAuth request attributes (fallback)
            String bindUserIdFromRequest = HttpCookieOAuth2AuthorizationRequestRepository.getBindUserIdFromAuthorizationRequest(request);
            log.info("[resolveBindUserIdFromCookie] 从OAuth请求属性读取bindUserId: {}", bindUserIdFromRequest);
            return bindUserIdFromRequest;
        } catch (Exception e) {
            log.warn("Failed to resolve bind user id from cookie", e);
            return null;
        } finally {
            HttpCookieOAuth2AuthorizationRequestRepository.clearBindContext();
        }
    }

    private String resolveBindUserIdByBindKey(String bindKey) {
        if (!StringUtils.hasText(bindKey)) {
            return null;
        }
        String redisKey = BIND_REDIS_PREFIX + bindKey;
        String bindUserIdFromRedis = redisUtil.get(redisKey);
        log.info("[resolveBindUserIdFromCookie] 从Redis读取bindUserId: key={}, value={}", redisKey, bindUserIdFromRedis);
        if (StringUtils.hasText(bindUserIdFromRedis)) {
            // one-time use, avoid replay
            redisUtil.delete(redisKey);
            return bindUserIdFromRedis;
        }
        return null;
    }

    private OAuth2User buildOAuth2User(ZwikiUser user, String provider, Map<String, Object> attrs, boolean bindMode) {
        Map<String, Object> merged = new HashMap<>(attrs);
        merged.put("zwikiUserId", user.getUserId());
        merged.put("zwikiProvider", provider);
        merged.put("zwikiBindMode", bindMode);

        String role = user.getRole();
        if (role == null || role.isBlank()) {
            role = "USER";
        }
        merged.put("zwikiRole", role);

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if ("ADMIN".equalsIgnoreCase(role)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        return new DefaultOAuth2User(
                authorities,
                merged,
                "zwikiUserId"
        );
    }
}
