package com.zwiki.config;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * GitHub 客户端配置类
 * 
 * 负责初始化github-api客户端，并从配置文件中读取认证Token等信息
 */
@Configuration
public class GitHubClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(GitHubClientConfig.class);

    @Value("${app.github.token:}")
    private String githubToken;

    @Value("${app.github.webhook.secret:}")
    private String webhookSecret;

    /**
     * 配置 GitHub 客户端
     * 仅当 app.github.token 属性存在且不为空时创建
     * 
     * @return GitHub 客户端实例
     * @throws IOException 如果配置失败
     */
    @Bean
    @ConditionalOnProperty(name = "app.github.token", matchIfMissing = false)
    public GitHub gitHubClient() throws IOException {
        if (githubToken == null || githubToken.trim().isEmpty()) {
            logger.warn("GitHub token is not configured. GitHub integration will be disabled.");
            return null;
        }
        
        logger.info("Initializing GitHub client with token.");
        return new GitHubBuilder()
                .withOAuthToken(githubToken)
                .build();
    }

    /**
     * 获取 Webhook 密钥
     * 
     * @return Webhook 密钥
     */
    public String getWebhookSecret() {
        return webhookSecret;
    }
} 
