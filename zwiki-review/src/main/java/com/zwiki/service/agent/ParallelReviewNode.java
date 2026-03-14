package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs StyleConventionAgent, LogicContextAgent, and SecurityScanAgent in parallel,
 * merging all their state outputs into a single result map.
 */
@Component
public class ParallelReviewNode implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ParallelReviewNode.class);

    private final StyleConventionAgent styleAgent;
    private final LogicContextAgent logicAgent;
    private final SecurityScanAgent securityAgent;

    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ParallelReviewNode(StyleConventionAgent styleAgent,
                              LogicContextAgent logicAgent,
                              SecurityScanAgent securityAgent) {
        this.styleAgent = styleAgent;
        this.logicAgent = logicAgent;
        this.securityAgent = securityAgent;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("ParallelReviewNode: launching style, logic, security agents in parallel");
        long start = System.currentTimeMillis();

        CompletableFuture<Map<String, Object>> styleFuture =
                CompletableFuture.supplyAsync(() -> safeApply("style", styleAgent, state), executor);

        CompletableFuture<Map<String, Object>> logicFuture =
                CompletableFuture.supplyAsync(() -> safeApply("logic", logicAgent, state), executor);

        CompletableFuture<Map<String, Object>> securityFuture =
                CompletableFuture.supplyAsync(() -> safeApply("security", securityAgent, state), executor);

        CompletableFuture.allOf(styleFuture, logicFuture, securityFuture).join();

        Map<String, Object> merged = new HashMap<>();
        merged.put("parallel_review_started", true);
        merged.putAll(styleFuture.get());
        merged.putAll(logicFuture.get());
        merged.putAll(securityFuture.get());

        long elapsed = System.currentTimeMillis() - start;
        logger.info("ParallelReviewNode completed in {}ms", elapsed);

        return merged;
    }

    private Map<String, Object> safeApply(String name, NodeAction agent, OverAllState state) {
        try {
            return agent.apply(state);
        } catch (Exception e) {
            logger.error("Agent '{}' failed during parallel review", name, e);
            return Map.of();
        }
    }
}
