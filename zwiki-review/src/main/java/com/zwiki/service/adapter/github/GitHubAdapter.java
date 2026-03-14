package com.zwiki.service.adapter.github;

import com.zwiki.domain.dto.ReviewCommentDTO;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * GitHub 适配器
 * 
 * 支持两种模式:
 * 1. 全局token模式: 使用GitHubClientConfig中配置的客户端实例
 * 2. 用户token模式: 使用per-request传入的token创建临时客户端
 */
@Component
public class GitHubAdapter {

    private static final Logger logger = LoggerFactory.getLogger(GitHubAdapter.class);

    @Autowired(required = false)
    private GitHub github;

    /**
     * 根据token获取或创建GitHub客户端
     */
    private GitHub resolveClient(String token) throws IOException {
        if (token != null && !token.isBlank()) {
            return new GitHubBuilder().withOAuthToken(token).build();
        }
        if (github != null) {
            return github;
        }
        throw new IOException("No GitHub client available: neither user token nor global token configured");
    }

    /**
     * 鑾峰彇Pull Request鐨刣iff鍐呭
     * 
     * @param repoFullName 浠撳簱鍏ㄥ悕 (owner/repo)
     * @param prNumber PR缂栧彿
     * @return diff鍐呭
     */
    public String getPullRequestDiff(String repoFullName, Integer prNumber) {
        return getPullRequestDiff(repoFullName, prNumber, null);
    }

    public String getPullRequestDiff(String repoFullName, Integer prNumber, String token) {
        try {
            GitHub client = resolveClient(token);
            GHRepository repository = client.getRepository(repoFullName);
            GHPullRequest pullRequest = repository.getPullRequest(prNumber);
            
            URL diffUrl = new URL(pullRequest.getDiffUrl().toString());
            
            try (Scanner scanner = new Scanner(diffUrl.openStream(), StandardCharsets.UTF_8)) {
                StringBuilder diffContent = new StringBuilder();
                while (scanner.hasNextLine()) {
                    diffContent.append(scanner.nextLine()).append("\n");
                }
                return diffContent.toString();
            }
            
        } catch (IOException e) {
            logger.error("获取PR diff时发生错误: repo={}, pr={}", repoFullName, prNumber, e);
            return null;
        }
    }

    /**
     * 鍙戝竷琛岀骇璇勮鍒癙R
     * 
     * @param repoFullName 浠撳簱鍏ㄥ悕
     * @param prNumber PR缂栧彿
     * @param comment 璇勮淇℃伅
     */
    public void publishLineComment(String repoFullName, Integer prNumber, ReviewCommentDTO comment) {
        publishLineComment(repoFullName, prNumber, comment, null);
    }

    public void publishLineComment(String repoFullName, Integer prNumber, ReviewCommentDTO comment, String token) {
        try {
            GitHub client = resolveClient(token);
            GHRepository repository = client.getRepository(repoFullName);
            GHPullRequest pullRequest = repository.getPullRequest(prNumber);
            
            // 鑾峰彇PR鐨勬渶鏂癱ommit
            String latestCommitSha = pullRequest.getHead().getSha();
            
            // 鍒涘缓琛岀骇璇勮")
            pullRequest.createReviewComment(
                comment.comment(),
                latestCommitSha,
                comment.filePath(),
                comment.lineNumber()
            );
            
            logger.debug("宸插彂甯冭绾ц瘎 file={}, line={}", comment.filePath(), comment.lineNumber());
            
        } catch (IOException e) {
            logger.error("鍙戝竷琛岀骇璇勮鏃跺彂鐢熼敊 repo={}, pr={}, file={}, line={}", 
                repoFullName, prNumber, comment.filePath(), comment.lineNumber(), e);
        }
    }

    /**
     * 鍙戝竷鎬讳綋璇勮鍒癙R
     * 
     * @param repoFullName 浠撳簱鍏ㄥ悕
     * @param prNumber PR缂栧彿
     * @param comment 璇勮鍐呭")
     */
    public void publishGeneralComment(String repoFullName, Integer prNumber, String comment) {
        publishGeneralComment(repoFullName, prNumber, comment, null);
    }

    public void publishGeneralComment(String repoFullName, Integer prNumber, String comment, String token) {
        try {
            GitHub client = resolveClient(token);
            GHRepository repository = client.getRepository(repoFullName);
            GHPullRequest pullRequest = repository.getPullRequest(prNumber);
            
            pullRequest.comment(comment);
            
            logger.debug("宸插彂甯冩€讳綋璇勮鍒癙R: repo={}, pr={}", repoFullName, prNumber);
            
        } catch (IOException e) {
            logger.error("鍙戝竷鎬讳綋璇勮鏃跺彂鐢熼敊 repo={}, pr={}", repoFullName, prNumber, e);
        }
    }

    /**
     * 鑾峰彇浠撳簱淇℃伅
     * 
     * @param repoFullName 浠撳簱鍏ㄥ悕
     * @return 浠撳簱淇℃伅")
     */
    public GHRepository getRepository(String repoFullName) {
        return getRepository(repoFullName, null);
    }

    public GHRepository getRepository(String repoFullName, String token) {
        try {
            return resolveClient(token).getRepository(repoFullName);
        } catch (IOException e) {
            logger.error("鑾峰彇浠撳簱淇℃伅鏃跺彂鐢熼敊 repo={}", repoFullName, e);
            return null;
        }
    }

    /**
     * 妫€鏌ヤ粨搴撴槸鍚﹀瓨鍦ㄤ笖鍙     * 
     * @param repoFullName 浠撳簱鍏ㄥ悕")
     * @return true濡傛灉鍙     */
    public boolean isRepositoryAccessible(String repoFullName) {
        return isRepositoryAccessible(repoFullName, null);
    }

    public boolean isRepositoryAccessible(String repoFullName, String token) {
        try {
            GHRepository repo = resolveClient(token).getRepository(repoFullName);
            return repo != null;
        } catch (IOException e) {
            logger.warn("浠撳簱涓嶅彲璁块棶: repo={}, error={}", repoFullName, e.getMessage());
            return false;
        }
    }
} 
