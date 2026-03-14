package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TriageAgent implements NodeAction {
    
    private static final Logger logger = LoggerFactory.getLogger(TriageAgent.class);
    
    @Autowired
    private ChatClient chatClient;
    
    private static final int MAX_DIFF_LINES = 1000;
    private static final int MAX_FILES_CHANGED = 20;
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("TriageAgent started");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> diffStats = (Map<String, Object>) state.value("diff_stats").orElse(new HashMap<>());
        String diffContent = (String) state.value("diff_content").orElse("");
        
        Map<String, Object> result = new HashMap<>();
        
        long totalChanges = (long) diffStats.getOrDefault("total_changes", 0L);
        long filesChanged = (long) diffStats.getOrDefault("files_changed", 0L);
        
        if (totalChanges > MAX_DIFF_LINES) {
            result.put("triage_decision", "too_large");
            result.put("needs_detailed_review", false);
            return result;
        }
        
        if (filesChanged > MAX_FILES_CHANGED) {
            result.put("triage_decision", "too_many_files");
            result.put("needs_detailed_review", false);
            return result;
        }
        
        result.put("triage_decision", "proceed");
        result.put("needs_detailed_review", true);
        result.put("change_types", analyzeChangeTypes(diffContent));
        
        logger.info("TriageAgent completed: decision={}", result.get("triage_decision"));
        return result;
    }
    
    private String analyzeChangeTypes(String diffContent) {
        if (diffContent.contains(".md") || diffContent.contains("README")) return "documentation";
        if (diffContent.contains(".yml") || diffContent.contains(".xml")) return "configuration";
        return "code";
    }
}
