package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ReviewCoordinatorAgent implements NodeAction {
    
    private static final Logger logger = LoggerFactory.getLogger(ReviewCoordinatorAgent.class);
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("ReviewCoordinatorAgent started");
        
        String diffContent = (String) state.value("diff_content").orElse("");
        String[] changedFiles = (String[]) state.value("changed_files").orElse(new String[0]);
        
        Map<String, Object> result = new HashMap<>();
        
        Map<String, Object> diffStats = new HashMap<>();
        diffStats.put("total_changes", (long) diffContent.lines().count());
        diffStats.put("files_changed", (long) changedFiles.length);
        diffStats.put("additions", diffContent.lines().filter(l -> l.startsWith("+")).count());
        diffStats.put("deletions", diffContent.lines().filter(l -> l.startsWith("-")).count());
        
        result.put("diff_stats", diffStats);
        result.put("coordinator_status", "initialized");
        
        logger.info("ReviewCoordinatorAgent completed: files={}, changes={}", 
            changedFiles.length, diffStats.get("total_changes"));
        
        return result;
    }
}
