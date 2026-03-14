package com.zwiki.service.github;

import com.zwiki.service.auth.GithubAccessTokenService;
import com.zwiki.service.auth.TokenCryptoService;
import com.zwiki.domain.dto.GitHubReviewConfigDto;
import com.zwiki.repository.dao.GitHubReviewConfigRepository;
import com.zwiki.repository.entity.GitHubReviewConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @author pai
 * @description: GitHub代码审查配置服务
 * @date 2026/2/1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubReviewConfigService {

    private final GitHubReviewConfigRepository configRepository;
    private final GithubAccessTokenService accessTokenService;
    private final TokenCryptoService tokenCryptoService;

    @Value("${zwiki.review.webhook-base-url:}")
    private String webhookBaseUrl;

    public List<GitHubReviewConfigDto> listByUser(String userId) {
        return configRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public GitHubReviewConfigDto getById(String userId, Long id) {
        return configRepository.findByIdAndUserId(id, userId)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public GitHubReviewConfigDto create(String userId, GitHubReviewConfigDto dto) {
        if (!StringUtils.hasText(dto.getRepoFullName())) {
            throw new IllegalArgumentException("仓库名称不能为空");
        }

        if (configRepository.existsByUserIdAndRepoFullName(userId, dto.getRepoFullName())) {
            throw new IllegalArgumentException("该仓库已配置审查: " + dto.getRepoFullName());
        }

        GitHubReviewConfig config = GitHubReviewConfig.builder()
                .userId(userId)
                .repoFullName(dto.getRepoFullName().trim())
                .webhookSecret(dto.getWebhookSecret() != null ? dto.getWebhookSecret().trim() : "")
                .customPat(encryptPat(dto.getCustomPat()))
                .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                .build();

        config = configRepository.save(config);
        log.info("GitHub review config created: userId={}, repo={}", userId, config.getRepoFullName());
        return toDto(config);
    }

    @Transactional
    public GitHubReviewConfigDto update(String userId, Long id, GitHubReviewConfigDto dto) {
        GitHubReviewConfig config = configRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在"));

        if (dto.getWebhookSecret() != null) {
            config.setWebhookSecret(dto.getWebhookSecret().trim());
        }
        if (dto.getCustomPat() != null) {
            // 空字符串表示清除自定义PAT
            config.setCustomPat(dto.getCustomPat().isEmpty() ? null : encryptPat(dto.getCustomPat()));
        }
        if (dto.getEnabled() != null) {
            config.setEnabled(dto.getEnabled());
        }

        config = configRepository.save(config);
        log.info("GitHub review config updated: userId={}, repo={}", userId, config.getRepoFullName());
        return toDto(config);
    }

    @Transactional
    public void delete(String userId, Long id) {
        configRepository.deleteByIdAndUserId(id, userId);
        log.info("GitHub review config deleted: userId={}, id={}", userId, id);
    }

    /**
     * 验证用户是否有有效的GitHub token（OAuth或自定义PAT）
     */
    public boolean hasValidToken(String userId) {
        String token = accessTokenService.getAccessTokenByUserId(userId);
        return StringUtils.hasText(token);
    }

    private String encryptPat(String pat) {
        if (!StringUtils.hasText(pat)) {
            return null;
        }
        return tokenCryptoService.encryptIfEnabled(pat.trim());
    }

    private GitHubReviewConfigDto toDto(GitHubReviewConfig config) {
        String webhookUrl = StringUtils.hasText(webhookBaseUrl)
                ? webhookBaseUrl + "/api/webhook/github"
                : "<服务器地址>:8992/api/webhook/github";

        return GitHubReviewConfigDto.builder()
                .id(config.getId())
                .repoFullName(config.getRepoFullName())
                .webhookSecret(config.getWebhookSecret())
                .customPat(null) // 不返回PAT明文
                .enabled(config.getEnabled())
                .lastReviewAt(config.getLastReviewAt())
                .reviewCount(config.getReviewCount())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .webhookUrl(webhookUrl)
                .usingCustomPat(StringUtils.hasText(config.getCustomPat()))
                .build();
    }
}
