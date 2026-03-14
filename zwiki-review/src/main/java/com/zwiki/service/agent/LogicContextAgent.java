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
public class LogicContextAgent implements NodeAction {
    
    private static final Logger logger = LoggerFactory.getLogger(LogicContextAgent.class);
    
    @Autowired
    private ChatClient chatClient;
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("LogicContextAgent started");
        
        String diffContent = (String) state.value("diff_content").orElse("");
        String[] changedFiles = (String[]) state.value("changed_files").orElse(new String[0]);
        
        List<ReviewCommentDTO> logicIssues = new ArrayList<>();
        
        Map<String, List<String>> fileChanges = parseDiffByFile(diffContent);
        
        for (Map.Entry<String, List<String>> entry : fileChanges.entrySet()) {
            String filePath = entry.getKey();
            List<String> changes = entry.getValue();
            
            logicIssues.addAll(checkLogicIssues(filePath, changes));
        }
        
        logicIssues.addAll(performLlmLogicAnalysis(diffContent));
        
        Map<String, Object> result = new HashMap<>();
        result.put("logic_issues", logicIssues);
        result.put("logic_issue_count", logicIssues.size());
        
        logger.info("LogicContextAgent completed: found {} issues", logicIssues.size());
        return result;
    }
    
    private Map<String, List<String>> parseDiffByFile(String diffContent) {
        Map<String, List<String>> result = new HashMap<>();
        String currentFile = null;
        List<String> currentChanges = new ArrayList<>();
        int lineNumber = 0;
        
        for (String line : diffContent.split("\n")) {
            if (line.startsWith("+++ b/")) {
                if (currentFile != null && !currentChanges.isEmpty()) {
                    result.put(currentFile, new ArrayList<>(currentChanges));
                }
                currentFile = line.substring(6);
                currentChanges = new ArrayList<>();
            } else if (line.startsWith("@@")) {
                String[] parts = line.split(" ");
                if (parts.length >= 3 && parts[2].startsWith("+")) {
                    String[] rangeParts = parts[2].substring(1).split(",");
                    lineNumber = Integer.parseInt(rangeParts[0]);
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                currentChanges.add(lineNumber + ":" + line.substring(1));
                lineNumber++;
            } else if (!line.startsWith("-")) {
                lineNumber++;
            }
        }
        
        if (currentFile != null && !currentChanges.isEmpty()) {
            result.put(currentFile, currentChanges);
        }
        return result;
    }
    
    private List<ReviewCommentDTO> checkLogicIssues(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            if (code.contains("== null") && code.contains("&&")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Consider using Optional or Objects.requireNonNull for null checks")
                    .severity("info")
                    .build());
            }
            
            if (code.matches(".*catch\\s*\\(.*\\)\\s*\\{\\s*\\}.*")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Empty catch block - exceptions should be logged or handled")
                    .severity("warning")
                    .build());
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> performLlmLogicAnalysis(String diffContent) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        try {
            String codeSnippet = diffContent.lines()
                .filter(line -> line.startsWith("+") && !line.startsWith("+++"))
                .limit(80)
                .reduce("", (a, b) -> a + "\n" + b.substring(1));
            
            if (codeSnippet.trim().isEmpty()) return issues;
            
            String response = chatClient.prompt()
                .user("Analyze this code for logic issues:\n" + codeSnippet + "\nList issues or say 'No issues'")
                .call()
                .content();
            
            if (!response.contains("No issues") && !response.trim().isEmpty()) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath("general")
                    .lineNumber(0)
                    .comment("LLM Logic Analysis: " + response.substring(0, Math.min(response.length(), 300)))
                    .severity("info")
                    .build());
            }
        } catch (Exception e) {
            logger.error("LLM logic analysis failed", e);
        }
        
        return issues;
    }
}
