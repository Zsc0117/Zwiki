package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ParallelReviewStarter implements NodeAction {
    
    private static final Logger logger = LoggerFactory.getLogger(ParallelReviewStarter.class);
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("ParallelReviewStarter: initiating parallel review agents");
        
        Map<String, Object> result = new HashMap<>();
        result.put("parallel_review_started", true);
        result.put("review_agents", new String[]{"style", "logic", "security"});
        
        return result;
    }
}
