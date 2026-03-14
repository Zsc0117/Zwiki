package com.zwiki.service.auth;

import com.zwiki.repository.dao.ZwikiOAuthAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * @author pai
 * @description: GitHub访问令牌服务
 * @date 2026/1/21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubAccessTokenService {

    private static final String PROVIDER = "github";

    private final ZwikiOAuthAccountMapper oauthAccountMapper;
    private final TokenCryptoService tokenCryptoService;

    public String getAccessTokenByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        return oauthAccountMapper.findFirstByUserIdAndProvider(userId, PROVIDER)
                .map(a -> {
                    LocalDateTime expiresAt = a.getTokenExpiresAt();
                    if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
                        // GitHub OAuth token 通常不支持 refresh_token，且 expires_at 字段在不同环境下可能不可靠。
                        // 这里不做硬拦截，交由 GitHub API 的 401/403 来判定 token 是否仍可用。
                        log.warn("[github-token] token_expires_at is before now, will still try GitHub API: userId={}, expiresAt={}", userId, expiresAt);
                    }

                    String scopes = a.getTokenScopes();
                    if (StringUtils.hasText(scopes)) {
                        boolean hasRepo = scopes.contains("repo") || scopes.contains("public_repo");
                        if (!hasRepo) {
                            // 不在这里硬拦截：以 GitHub API 的 403/401 作为最终依据。
                            // 该字段可能为空/不完整，或历史 token 没更新导致误判。
                            log.warn("[github-token] tokenScopes missing repo scope, will still try GitHub API: userId={}, scopes={}", userId, scopes);
                        }
                    } else {
                        log.info("[github-token] tokenScopes empty, will rely on GitHub API response: userId={}", userId);
                    }

                    String stored = a.getAccessToken();
                    if (!StringUtils.hasText(stored)) {
                        log.warn("[github-token] access_token empty in db: userId={}", userId);
                        return null;
                    }
                    String plain = tokenCryptoService.decryptIfEncrypted(stored);
                    if (!StringUtils.hasText(plain)) {
                        boolean storedEncrypted = stored.startsWith("enc:v1:");
                        log.warn("[github-token] access_token decrypt failed: userId={}, cryptoEnabled={}, storedEncrypted={}",
                                userId, tokenCryptoService.isEnabled(), storedEncrypted);
                    }
                    return StringUtils.hasText(plain) ? plain : null;
                })
                .orElse(null);
    }
}
