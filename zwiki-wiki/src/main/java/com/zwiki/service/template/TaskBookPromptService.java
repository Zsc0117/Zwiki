package com.zwiki.service.template;

import com.zwiki.repository.entity.ThesisAnalysisReport;
import com.zwiki.service.ThesisLLMService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBookPromptService {

    private final ThesisLLMService llmService;

    @Value("${zwiki.thesis.task-book-prompt-template:templates/ftl/task_book_prompt.ftl}")
    private String promptTemplatePath;

    private String promptTemplateContent;

    @PostConstruct
    public void init() {
        loadPromptTemplate();
    }

    public String generateTaskBookJson(String taskId, ThesisAnalysisReport report) {
        String prompt = buildPrompt(report, null);
        return llmService.generateThesisContent(prompt);
    }

    public String generateTaskBookJson(String taskId, ThesisAnalysisReport report, String additionalContext) {
        String prompt = buildPrompt(report, additionalContext);
        return llmService.generateThesisContent(prompt);
    }

    private void loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(promptTemplatePath);
            try (InputStream is = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                promptTemplateContent = sb.toString();
                log.info("任务书 Prompt 模板加载成功: {}", promptTemplatePath);
            }
        } catch (Exception e) {
            log.warn("加载任务书 prompt 模板失败: {}", e.getMessage());
            promptTemplateContent = "请根据项目说明生成任务书的结构化 JSON 字段。";
        }
    }

    private String buildPrompt(ThesisAnalysisReport report, String additionalContext) {
        LocalDate now = LocalDate.now();
        int currentYear = now.getYear();
        int currentMonth = now.getMonthValue();
        int startYear;
        int endYear;

        if (currentMonth >= 6) {
            startYear = currentYear;
            endYear = currentYear + 1;
        } else {
            startYear = currentYear - 1;
            endYear = currentYear;
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("projectName", report != null && report.getProjectName() != null ? report.getProjectName() : "项目");
        variables.put("projectOverview", safeGet(report != null ? report.getProjectOverview() : null));
        variables.put("techStack", safeGet(report != null ? report.getTechStack() : null));
        variables.put("coreModules", safeGet(report != null ? report.getCoreModulesAnalysis() : null));
        variables.put("comprehensiveReport", safeGet(report != null ? report.getComprehensiveReport() : null));
        variables.put("startYear", String.valueOf(startYear));
        variables.put("endYear", String.valueOf(endYear));

        String prompt = renderPromptTemplate(variables);
        if (additionalContext != null && !additionalContext.isBlank()) {
            prompt = prompt + "\n\n# 补充参考资料（来自项目记忆检索）\n" + additionalContext;
        }
        return prompt;
    }

    private String renderPromptTemplate(Map<String, Object> variables) {
        String result = promptTemplateContent;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private String safeGet(String value) {
        return value != null ? value : "";
    }
}
