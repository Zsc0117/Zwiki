package com.zwiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.domain.dto.PullRequestEventDTO;
import com.zwiki.domain.dto.ReviewTaskDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class GithubWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(GithubWebhookService.class);

    @Autowired
    private CodeReviewService codeReviewService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitHubConfigLookupService configLookupService;

    @Value("${app.github.token:}")
    private String globalGithubToken;

    @Async("reviewTaskExecutor")
    public void handlePullRequestEvent(String payload) {
        try {
            PullRequestEventDTO event = objectMapper.readValue(payload, PullRequestEventDTO.class);
            
            if (!shouldProcessEvent(event)) {
                logger.info("Skipping event: action={}", event.action());
                return;
            }

            String repoFullName = event.repository().fullName();

            // 查找用户配置的token
            String token = null;
            GitHubConfigLookupService.RepoReviewConfig config = configLookupService.findConfigByRepo(repoFullName);
            if (config != null && config.hasGithubToken()) {
                token = config.githubToken();
                logger.info("Using per-user token for repo={}", repoFullName);
            } else if (globalGithubToken != null && !globalGithubToken.isBlank()) {
                token = globalGithubToken;
                logger.info("Using global token for repo={}", repoFullName);
            } else {
                logger.warn("No GitHub token available for repo={}, review may fail", repoFullName);
            }
            
            ReviewTaskDTO task = buildReviewTask(event, token);
            logger.info("Processing PR review: repo={}, pr={}", task.repositoryFullName(), task.prNumber());
            
            codeReviewService.performReview(task);

            // 更新审查统计
            configLookupService.incrementReviewCount(repoFullName);
            
        } catch (Exception e) {
            logger.error("Error processing PR event", e);
        }
    }

    private boolean shouldProcessEvent(PullRequestEventDTO event) {
        if (event == null || event.pullRequest() == null) return false;
        String action = event.action();
        return "opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action);
    }

    private ReviewTaskDTO buildReviewTask(PullRequestEventDTO event, String githubToken) {
        return ReviewTaskDTO.builder()
            .repositoryFullName(event.repository().fullName())
            .prNumber(event.pullRequest().number())
            .prTitle(event.pullRequest().title())
            .prAuthor(event.pullRequest().user().login())
            .headSha(event.pullRequest().head().sha())
            .baseSha(event.pullRequest().base().sha())
            .diffUrl(event.pullRequest().diffUrl())
            .cloneUrl(event.repository().cloneUrl())
            .githubToken(githubToken)
            .build();
    }
}
