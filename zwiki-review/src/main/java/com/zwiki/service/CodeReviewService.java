package com.zwiki.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.zwiki.service.adapter.github.GitHubAdapter;
import com.zwiki.service.adapter.llm.LlmAdapter;
import com.zwiki.domain.dto.ReviewCommentDTO;
import com.zwiki.domain.dto.ReviewResultDTO;
import com.zwiki.domain.dto.ReviewTaskDTO;
import com.zwiki.service.knowledge.RAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
/**
 * Code Review Service Implementation
 */
@Service
public class CodeReviewService {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewService.class);
    
    @Value("${review.use-graph-workflow:true}")
    private boolean useGraphWorkflow;

    @Autowired(required = false)
    private GitHubAdapter gitHubAdapter;

    @Autowired(required = false)
    private CompiledGraph compiledCodeReviewGraph;

    @Autowired
    private LlmAdapter llmAdapter;

    @Autowired
    private ResultPublishService resultPublishService;
    
    @Autowired
    private RAGService ragService;

    public void performReview(ReviewTaskDTO task) {
        logger.info("Starting code review for {}, using graph workflow: {}", 
            task.repositoryFullName(), useGraphWorkflow);
        
        try {
            if (useGraphWorkflow && compiledCodeReviewGraph != null) {
                performGraphBasedReview(task);
            } else {
                performTraditionalReview(task);
            }
        } catch (Exception e) {
            logger.error("Error during code review: repo={}, pr={}", 
                task.repositoryFullName(), task.prNumber(), e);
        }
    }
    
    private void performGraphBasedReview(ReviewTaskDTO task) {
        logger.info("Using multi-agent graph workflow for review: repo={}, pr={}", 
            task.repositoryFullName(), task.prNumber());
        
        try {
            String diffContent = gitHubAdapter.getPullRequestDiff(
                task.repositoryFullName(), task.prNumber(), task.githubToken());
            
            if (diffContent == null || diffContent.trim().isEmpty()) {
                logger.warn("Empty diff content for PR: {}", task.prNumber());
                return;
            }
            
            String[] changedFiles = extractChangedFiles(diffContent);
            
            Map<String, Object> initialState = new HashMap<>();
            initialState.put("diff_content", diffContent);
            initialState.put("changed_files", changedFiles);
            initialState.put("repo_name", task.repositoryFullName());
            initialState.put("pr_number", task.prNumber());
            initialState.put("task", task);
            
            OverAllState finalState = compiledCodeReviewGraph.invoke(initialState).get();
            
            @SuppressWarnings("unchecked")
            List<ReviewCommentDTO> allComments = (List<ReviewCommentDTO>) finalState.value("final_comments").orElse(List.of());
            String summary = (String) finalState.value("summary").orElse("Review completed");
            String riskLevel = (String) finalState.value("overall_risk_level").orElse("LOW");
            
            ReviewResultDTO result = new ReviewResultDTO(allComments, summary, riskLevel);
            
            resultPublishService.publishReviewResult(task, result);
            
            logger.info("Graph-based review completed: repo={}, pr={}, comments={}, risk={}", 
                task.repositoryFullName(), task.prNumber(), allComments.size(), riskLevel);
                
        } catch (Exception e) {
            logger.error("Graph-based review failed, falling back to traditional: repo={}, pr={}", 
                task.repositoryFullName(), task.prNumber(), e);
            performTraditionalReview(task);
        }
    }
    
    private void performTraditionalReview(ReviewTaskDTO task) {
        logger.info("Using traditional review workflow: repo={}, pr={}", 
            task.repositoryFullName(), task.prNumber());
        
        try {
            String diffContent = gitHubAdapter.getPullRequestDiff(
                task.repositoryFullName(), task.prNumber(), task.githubToken());
            
            if (diffContent == null || diffContent.trim().isEmpty()) {
                logger.warn("Empty diff content for PR: {}", task.prNumber());
                return;
            }
            
            // RAG上下文检索
            String contextInfo = "";
            try {
                contextInfo = ragService.retrieveContext(diffContent, task.repositoryFullName());
                logger.info("RAG context retrieved for traditional review: {} chars", contextInfo.length());
            } catch (Exception e) {
                logger.warn("RAG context retrieval failed, proceeding without context: {}", e.getMessage());
            }
            
            ReviewResultDTO result = llmAdapter.getReviewComments(diffContent, contextInfo, task.repositoryFullName());
            
            resultPublishService.publishReviewResult(task, result);
            
            logger.info("Traditional review completed: repo={}, pr={}, comments={}", 
                task.repositoryFullName(), task.prNumber(), 
                result.comments() != null ? result.comments().size() : 0);
                
        } catch (Exception e) {
            logger.error("Traditional review failed: repo={}, pr={}", 
                task.repositoryFullName(), task.prNumber(), e);
        }
    }
    
    private String[] extractChangedFiles(String diffContent) {
        return diffContent.lines()
            .filter(line -> line.startsWith("+++ b/"))
            .map(line -> line.substring(6))
            .toArray(String[]::new);
    }
}
