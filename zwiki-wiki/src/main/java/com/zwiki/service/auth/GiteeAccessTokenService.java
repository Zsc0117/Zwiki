package com.zwiki.service.auth;

import com.zwiki.repository.dao.ZwikiOAuthAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Gitee访问令牌服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiteeAccessTokenService {

    private static final String PROVIDER = "gitee";

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
                        log.warn("[gitee-token] token_expires_at is before now, will still try Gitee API: userId={}, expiresAt={}", userId, expiresAt);
                    }

                    String stored = a.getAccessToken();
                    if (!StringUtils.hasText(stored)) {
                        log.warn("[gitee-token] access_token empty in db: userId={}", userId);
                        return null;
                    }
                    String plain = tokenCryptoService.decryptIfEncrypted(stored);
                    if (!StringUtils.hasText(plain)) {
                        boolean storedEncrypted = stored.startsWith("enc:v1:");
                        log.warn("[gitee-token] access_token decrypt failed: userId={}, cryptoEnabled={}, storedEncrypted={}",
                                userId, tokenCryptoService.isEnabled(), storedEncrypted);
                    }
                    return StringUtils.hasText(plain) ? plain : null;
                })
                .orElse(null);
    }
}
