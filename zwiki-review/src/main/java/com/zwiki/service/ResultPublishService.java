package com.zwiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.service.adapter.github.GitHubAdapter;
import com.zwiki.domain.dto.ReviewCommentDTO;
import com.zwiki.domain.dto.ReviewResultDTO;
import com.zwiki.domain.dto.ReviewTaskDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Result Publish Service Implementation
 */
@Service
public class ResultPublishService {

    private static final Logger logger = LoggerFactory.getLogger(ResultPublishService.class);

    @Autowired(required = false)
    private GitHubAdapter gitHubAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public void publishReviewResult(ReviewTaskDTO task, ReviewResultDTO result) {
        saveReviewHistory(task, result);
        if (gitHubAdapter == null) {
            logger.warn("GitHubAdapter is not available. GitHub integration is disabled. Skipping publish for repo={}, pr={}",
                task.repositoryFullName(), task.prNumber());
            return;
        }
        
        try {
            logger.info("Publishing review result: repo={}, pr={}, comments={}", 
                task.repositoryFullName(), task.prNumber(), 
                result.comments() != null ? result.comments().size() : 0);

            if (result.summary() != null && !result.summary().trim().isEmpty()) {
                String summaryComment = buildSummaryComment(result);
                gitHubAdapter.publishGeneralComment(
                    task.repositoryFullName(), 
                    task.prNumber(), 
                    summaryComment,
                    task.githubToken()
                );
            }

            if (result.comments() != null && !result.comments().isEmpty()) {
                publishLineComments(task, result.comments());
            }

            logger.info("Review result published: repo={}, pr={}", 
                task.repositoryFullName(), task.prNumber());

        } catch (Exception e) {
            logger.error("Error publishing review result: repo={}, pr={}", 
                task.repositoryFullName(), task.prNumber(), e);
        }
    }

    private void publishLineComments(ReviewTaskDTO task, List<ReviewCommentDTO> comments) {
        int successCount = 0;
        int errorCount = 0;

        for (ReviewCommentDTO comment : comments) {
            try {
                String formattedComment = formatComment(comment);
                boolean isValidLineComment = isValidLineComment(comment);
                
                if (isValidLineComment) {
                    ReviewCommentDTO formattedCommentDto = ReviewCommentDTO.builder()
                        .filePath(comment.filePath())
                        .lineNumber(comment.lineNumber())
                        .comment(formattedComment)
                        .severity(comment.severity())
                        .build();
                    
                    gitHubAdapter.publishLineComment(
                        task.repositoryFullName(),
                        task.prNumber(),
                        formattedCommentDto,
                        task.githubToken()
                    );
                    successCount++;
                } else {
                    gitHubAdapter.publishGeneralComment(
                        task.repositoryFullName(),
                        task.prNumber(),
                        formattedComment,
                        task.githubToken()
                    );
                }
            } catch (Exception e) {
                errorCount++;
                logger.warn("Failed to publish comment: file={}, line={}", 
                    comment.filePath(), comment.lineNumber(), e);
            }
        }

        logger.info("Line comments published: success={}, errors={}", successCount, errorCount);
    }

    private String buildSummaryComment(ReviewResultDTO result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Code Review Summary\n\n");
        sb.append("**Rating:** ").append(result.overallRating()).append("\n\n");
        sb.append(result.summary()).append("\n\n");
        
        if (result.comments() != null && !result.comments().isEmpty()) {
            long errorCount = result.comments().stream()
                .filter(c -> "error".equals(c.severity()))
                .count();
            long warningCount = result.comments().stream()
                .filter(c -> "warning".equals(c.severity()))
                .count();
            long infoCount = result.comments().stream()
                .filter(c -> "info".equals(c.severity()))
                .count();
            
            sb.append("### Statistics\n");
            sb.append("- Errors: ").append(errorCount).append("\n");
            sb.append("- Warnings: ").append(warningCount).append("\n");
            sb.append("- Info: ").append(infoCount).append("\n");
        }
        
        return sb.toString();
    }

    private String formatComment(ReviewCommentDTO comment) {
        String severityEmoji = switch (comment.severity()) {
            case "error" -> "🔴";
            case "warning" -> "🟡";
            default -> "🔵";
        };
        return severityEmoji + " " + comment.comment();
    }

    private boolean isValidLineComment(ReviewCommentDTO comment) {
        return comment.filePath() != null && 
               !comment.filePath().isEmpty() && 
               !"general".equals(comment.filePath()) &&
               comment.lineNumber() != null && 
               comment.lineNumber() > 0;
    }

    private void saveReviewHistory(ReviewTaskDTO task, ReviewResultDTO result) {
        try {
            long errorCount = 0, warningCount = 0, infoCount = 0;
            int commentCount = 0;
            if (result.comments() != null) {
                commentCount = result.comments().size();
                errorCount = result.comments().stream().filter(c -> "error".equals(c.severity())).count();
                warningCount = result.comments().stream().filter(c -> "warning".equals(c.severity())).count();
                infoCount = result.comments().stream().filter(c -> "info".equals(c.severity())).count();
            }

            String detailJson = null;
            try {
                detailJson = objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                logger.warn("Failed to serialize review detail to JSON", e);
            }

            jdbcTemplate.update(
                "INSERT INTO zwiki_review_history (repo_full_name, pr_number, overall_rating, summary, " +
                "comment_count, error_count, warning_count, info_count, review_detail) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                task.repositoryFullName(),
                task.prNumber(),
                result.overallRating(),
                result.summary(),
                commentCount,
                errorCount,
                warningCount,
                infoCount,
                detailJson
            );
            logger.info("Review history saved: repo={}, pr={}", task.repositoryFullName(), task.prNumber());
        } catch (Exception e) {
            logger.error("Failed to save review history: repo={}, pr={}", task.repositoryFullName(), task.prNumber(), e);
        }
    }
}
