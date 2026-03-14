package com.zwiki.service.template;

import com.zwiki.repository.entity.ThesisAnalysisReport;
import com.zwiki.service.ThesisLLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 智能论文填充服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligentThesisFillerService {

    private final ThesisLLMService llmService;

    public String intelligentFillContent(String placeholder, String context, ThesisAnalysisReport analysisReport) {
        log.info("智能填充占位符: {}", placeholder);
        try {
            String prompt = buildIntelligentFillPrompt(placeholder, context, analysisReport);
            return llmService.generateCodeSummary(prompt);
        } catch (Exception e) {
            log.error("智能填充失败: {}", placeholder, e);
            return "[内容生成失败]";
        }
    }

    public String optimizeContentBasedOnFeedback(String originalContent, String userFeedback, ThesisAnalysisReport report) {
        log.info("根据用户反馈优化内容");
        try {
            String prompt = buildOptimizationPrompt(originalContent, userFeedback, report);
            return llmService.generateCodeSummary(prompt);
        } catch (Exception e) {
            log.error("优化内容失败", e);
            return originalContent;
        }
    }

    public Map<String, String> generateAllDiagramDescriptions(
            Map<String, String> availableDiagrams,
            ThesisAnalysisReport analysisReport) {
        Map<String, String> descriptions = new HashMap<>();
        for (Map.Entry<String, String> entry : availableDiagrams.entrySet()) {
            String diagramType = entry.getKey();
            String description = generateDiagramDescription(diagramType, "", analysisReport);
            descriptions.put(diagramType, description);
        }
        return descriptions;
    }

    public String generateDiagramDescription(
            String diagramType,
            String context,
            ThesisAnalysisReport analysisReport) {
        try {
            String prompt = buildDiagramDescriptionPrompt(diagramType, context, analysisReport);
            return llmService.generateCodeSummary(prompt);
        } catch (Exception e) {
            log.error("生成图表说明失败", e);
            return "该图展示了系统的相关设计内容。";
        }
    }

    private String buildIntelligentFillPrompt(
            String placeholder,
            String context,
            ThesisAnalysisReport report) {
        return String.format("""
                你正在帮助填充一篇毕业论文模板。

                ## 当前位置
                占位符：%s
                上下文：
                %s

                ## 项目分析信息
                项目概览：%s

                核心模块：%s

                技术栈：%s

                ## 任务
                请根据以上信息，为占位符生成合适的内容。要求：
                1. 内容要与项目实际情况相符
                2. 语言要专业、学术化
                3. 结构要清晰、有逻辑
                4. 长度要适中（200-500字）
                5. 要自然地承接上下文

                请直接输出填充内容，不要额外说明。
                """,
                placeholder,
                safeGet(context),
                safeGet(report.getProjectOverview()),
                safeGet(report.getCoreModulesAnalysis()),
                safeGet(report.getTechStack())
        );
    }

    private String buildOptimizationPrompt(String originalContent, String userFeedback, ThesisAnalysisReport report) {
        return String.format("""
                你是一位专业的论文写作助手，请根据用户反馈优化论文内容。

                ## 原始内容
                %s

                ## 用户反馈
                %s

                ## 项目背景
                %s

                请输出优化后的内容，要求：
                1. 保持学术风格
                2. 结合反馈具体优化
                3. 保持逻辑连贯
                4. 不要输出额外说明
                """,
                safeGet(originalContent),
                safeGet(userFeedback),
                safeGet(report.getProjectOverview())
        );
    }

    private String buildDiagramDescriptionPrompt(
            String diagramType,
            String context,
            ThesisAnalysisReport report) {
        return String.format("""
                请为毕业论文中的%s生成专业的说明文字。

                ## 图表类型
                %s

                ## 上下文
                %s

                ## 项目信息
                %s

                ## 要求
                1. 说明文字要专业、学术化
                2. 要说明图表展示的主要内容
                3. 要指出图表的重要性和意义
                4. 字数控制在100-200字
                5. 语言要流畅自然

                请直接输出说明文字，不要额外说明。
                """,
                diagramType,
                diagramType,
                safeGet(context),
                safeGet(report.getProjectOverview())
        );
    }

    private String safeGet(String value) {
        return value != null ? value : "";
    }
}
