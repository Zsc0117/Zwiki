package com.zwiki.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 论文生成所需的分析上下文（由项目文档汇总而成）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThesisAnalysisReport {
    private String taskId;
    private String projectName;
    private String projectOverview;
    private String coreModulesAnalysis;
    private String dependencyAnalysis;
    private String designPatterns;
    private String qualityAnalysis;
    private String businessFlows;
    private String techStack;
    private String comprehensiveReport;
    private String deepReadingReport;
}
