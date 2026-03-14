package com.zwiki.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.zwiki.service.agent.*;
import com.zwiki.service.graph.ReviewDecisionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 代码审查多Agent Graph工作流配置
 *
 * 工作流:
 *   START → coordinator → triage → [decisionRouter]
 *     ├─ "proceed" → parallel_review (style+logic+security并行) → report → END
 *     ├─ "reject"  → report → END
 *     └─ "skip"    → report → END
 */
@Configuration
public class CodeReviewWorkflowConfig {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewWorkflowConfig.class);

    @Bean
    public CompiledGraph compiledCodeReviewGraph(
            ChatModel chatModel,
            ReviewCoordinatorAgent coordinatorAgent,
            TriageAgent triageAgent,
            ParallelReviewNode parallelReviewNode,
            ReportSynthesizerAgent reportAgent,
            ReviewDecisionRouter decisionRouter) {

        try {
            StateGraph graph = new StateGraph("codeReviewGraph",
                    new KeyStrategyFactoryBuilder()
                            .addStrategy("diff_content", KeyStrategy.REPLACE)
                            .addStrategy("changed_files", KeyStrategy.REPLACE)
                            .addStrategy("repo_name", KeyStrategy.REPLACE)
                            .addStrategy("pr_number", KeyStrategy.REPLACE)
                            .addStrategy("task", KeyStrategy.REPLACE)
                            .addStrategy("diff_stats", KeyStrategy.REPLACE)
                            .addStrategy("coordinator_status", KeyStrategy.REPLACE)
                            .addStrategy("triage_decision", KeyStrategy.REPLACE)
                            .addStrategy("needs_detailed_review", KeyStrategy.REPLACE)
                            .addStrategy("change_types", KeyStrategy.REPLACE)
                            .addStrategy("parallel_review_started", KeyStrategy.REPLACE)
                            .addStrategy("review_agents", KeyStrategy.REPLACE)
                            .addStrategy("style_issues", KeyStrategy.REPLACE)
                            .addStrategy("style_issue_count", KeyStrategy.REPLACE)
                            .addStrategy("logic_issues", KeyStrategy.REPLACE)
                            .addStrategy("logic_issue_count", KeyStrategy.REPLACE)
                            .addStrategy("security_issues", KeyStrategy.REPLACE)
                            .addStrategy("security_risk_level", KeyStrategy.REPLACE)
                            .addStrategy("critical_count", KeyStrategy.REPLACE)
                            .addStrategy("high_count", KeyStrategy.REPLACE)
                            .addStrategy("medium_count", KeyStrategy.REPLACE)
                            .addStrategy("final_comments", KeyStrategy.REPLACE)
                            .addStrategy("summary", KeyStrategy.REPLACE)
                            .addStrategy("overall_risk_level", KeyStrategy.REPLACE)
                            .addStrategy("total_issues", KeyStrategy.REPLACE)
                            .build());

            graph.addNode("coordinator", AsyncNodeAction.node_async(coordinatorAgent));
            graph.addNode("triage", AsyncNodeAction.node_async(triageAgent));
            graph.addNode("parallel_review", AsyncNodeAction.node_async(parallelReviewNode));
            graph.addNode("report", AsyncNodeAction.node_async(reportAgent));

            graph.addEdge(StateGraph.START, "coordinator");
            graph.addEdge("coordinator", "triage");

            graph.addConditionalEdges("triage",
                    AsyncEdgeAction.edge_async(decisionRouter),
                    Map.of(
                            "proceed", "parallel_review",
                            "reject", "report",
                            "skip", "report"
                    ));

            graph.addEdge("parallel_review", "report");
            graph.addEdge("report", StateGraph.END);

            CompiledGraph compiled = graph.compile();
            logger.info("Code review multi-agent graph workflow compiled successfully");
            return compiled;

        } catch (Exception e) {
            logger.error("Failed to compile code review graph workflow, falling back to traditional mode", e);
            return null;
        }
    }
}
