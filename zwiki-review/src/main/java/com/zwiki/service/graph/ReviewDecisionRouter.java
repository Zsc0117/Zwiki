package com.zwiki.service.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReviewDecisionRouter implements EdgeAction {

    private static final Logger logger = LoggerFactory.getLogger(ReviewDecisionRouter.class);

    @Override
    public String apply(OverAllState state) {
        String triageDecision = (String) state.value("triage_decision").orElse("proceed");
        Boolean needsDetailedReview = (Boolean) state.value("needs_detailed_review").orElse(true);
        
        logger.info("ReviewDecisionRouter: decision={}, needsReview={}", triageDecision, needsDetailedReview);
        
        if ("too_large".equals(triageDecision) || "too_many_files".equals(triageDecision)) {
            return "reject";
        }
        
        if (!needsDetailedReview) {
            return "skip";
        }
        
        return "proceed";
    }
}
