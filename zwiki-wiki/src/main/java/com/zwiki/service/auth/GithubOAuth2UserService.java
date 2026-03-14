package com.zwiki.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.entity.ZwikiOAuthAccount;
import com.zwiki.repository.entity.ZwikiUser;
import com.zwiki.repository.dao.ZwikiOAuthAccountMapper;
import com.zwiki.repository.dao.ZwikiUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author pai
 * @description: GitHub OAuth2用户服务
 * @date 2026/1/20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final String PROVIDER = "github";

    private final ZwikiOAuthAccountMapper oauthAccountMapper;
    private final ZwikiUserRepository userMapper;
    private final ObjectMapper objectMapper;
    private final TokenCryptoService tokenCryptoService;

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = delegate.loadUser(userRequest);

        Map<String, Object> attrs = oAuth2User.getAttributes();
        String providerUserId = attrs.get("id") != null ? String.valueOf(attrs.get("id")) : null;
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new OAuth2AuthenticationException("GitHub OAuth登录失败：缺少用户id字段");
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

        ZwikiOAuthAccount account = oauthAccountMapper
                .findFirstByProviderAndProviderUserId(PROVIDER, providerUserId)
                .orElse(null);

        ZwikiUser user;
        if (account == null) {
            String newUserId = UUID.randomUUID().toString().replace("-", "");
            user = ZwikiUser.builder()
                    .userId(newUserId)
                    .displayName(name != null && !name.isBlank() ? name : (login != null ? login : "GitHub用户"))
                    .avatarUrl(avatarUrl)
                    .email(email)
                    .status("active")
                    .lastLoginTime(now)
                    .build();
            userMapper.save(user);

            account = ZwikiOAuthAccount.builder()
                    .userId(newUserId)
                    .provider(PROVIDER)
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

            log.info("GitHub首次登录创建用户: userId={}, login={}", newUserId, login);
        } else {
            user = userMapper.findFirstByUserId(account.getUserId()).orElse(null);
            if (user == null) {
                user = ZwikiUser.builder()
                        .userId(account.getUserId())
                        .displayName(name != null && !name.isBlank() ? name : (login != null ? login : "GitHub用户"))
                        .avatarUrl(avatarUrl)
                        .email(email)
                        .status("active")
                        .lastLoginTime(now)
                        .build();
                userMapper.save(user);
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
                userMapper.save(user);
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

        Map<String, Object> merged = new HashMap<>(attrs);
        merged.put("zwikiUserId", user.getUserId());

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
