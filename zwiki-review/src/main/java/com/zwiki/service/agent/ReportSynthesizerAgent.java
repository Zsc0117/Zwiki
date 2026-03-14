package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwiki.domain.dto.ReviewCommentDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReportSynthesizerAgent implements NodeAction {
    
    private static final Logger logger = LoggerFactory.getLogger(ReportSynthesizerAgent.class);
    
    @Autowired
    private ChatClient chatClient;
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("ReportSynthesizerAgent started");
        
        @SuppressWarnings("unchecked")
        List<ReviewCommentDTO> styleIssues = (List<ReviewCommentDTO>) state.value("style_issues").orElse(new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<ReviewCommentDTO> logicIssues = (List<ReviewCommentDTO>) state.value("logic_issues").orElse(new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<ReviewCommentDTO> securityIssues = (List<ReviewCommentDTO>) state.value("security_issues").orElse(new ArrayList<>());
        
        String securityRiskLevel = (String) state.value("security_risk_level").orElse("LOW");
        
        List<ReviewCommentDTO> allComments = new ArrayList<>();
        allComments.addAll(securityIssues);
        allComments.addAll(logicIssues);
        allComments.addAll(styleIssues);
        
        String overallRiskLevel = calculateOverallRiskLevel(securityRiskLevel, allComments);
        String summary = generateSummary(allComments, overallRiskLevel);
        
        Map<String, Object> result = new HashMap<>();
        result.put("final_comments", allComments);
        result.put("summary", summary);
        result.put("overall_risk_level", overallRiskLevel);
        result.put("total_issues", allComments.size());
        
        logger.info("ReportSynthesizerAgent completed: total={}, risk={}", allComments.size(), overallRiskLevel);
        
        return result;
    }
    
    private String calculateOverallRiskLevel(String securityRiskLevel, List<ReviewCommentDTO> allComments) {
        if ("CRITICAL".equals(securityRiskLevel)) return "CRITICAL";
        if ("HIGH".equals(securityRiskLevel)) return "HIGH";
        
        long errorCount = allComments.stream().filter(c -> "error".equals(c.severity())).count();
        long warningCount = allComments.stream().filter(c -> "warning".equals(c.severity())).count();
        
        if (errorCount >= 5) return "HIGH";
        if (errorCount >= 2 || warningCount >= 10) return "MEDIUM";
        if (errorCount > 0 || warningCount > 0) return "LOW";
        return "SAFE";
    }
    
    private String generateSummary(List<ReviewCommentDTO> allComments, String riskLevel) {
        long errorCount = allComments.stream().filter(c -> "error".equals(c.severity())).count();
        long warningCount = allComments.stream().filter(c -> "warning".equals(c.severity())).count();
        long infoCount = allComments.stream().filter(c -> "info".equals(c.severity())).count();
        
        return String.format(
            "Code review completed. Risk level: %s. Found %d errors, %d warnings, %d info items. Total: %d issues.",
            riskLevel, errorCount, warningCount, infoCount, allComments.size()
        );
    }
}
