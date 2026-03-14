package com.zwiki.service.adapter.llm;

import com.zwiki.domain.dto.ReviewResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class LlmAdapter {

    private static final Logger logger = LoggerFactory.getLogger(LlmAdapter.class);

    private final ChatClient chatClient;

    public LlmAdapter(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ReviewResultDTO getReviewComments(String diffContent, String contextInfo, String repoFullName) {
        try {
            logger.info("Starting LLM code review: repo={}", repoFullName);
            
            String prompt = buildReviewPrompt(diffContent, contextInfo, repoFullName);
            
            ReviewResultDTO result = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(ReviewResultDTO.class);
            
            logger.info("LLM review completed: repo={}, comments={}", 
                repoFullName, result != null && result.comments() != null ? result.comments().size() : 0);
            
            return result;
            
        } catch (Exception e) {
            logger.error("LLM review failed: repo={}", repoFullName, e);
            return ReviewResultDTO.builder().summary("Review failed: " + e.getMessage()).build();
        }
    }

    private String buildReviewPrompt(String diffContent, String contextInfo, String repoFullName) {
        return String.format("""
            You are a code reviewer. Review the following code changes and provide feedback.
            
            Repository: %s
            
            Context:
            %s
            
            Diff:
            %s
            
            Provide a JSON response with:
            - comments: array of {filePath, lineNumber, comment, severity}
            - summary: brief summary of the review
            - overallRating: one of "excellent", "good", "needs_improvement", "poor"
            """, repoFullName, contextInfo, diffContent.substring(0, Math.min(diffContent.length(), 8000)));
    }
}
