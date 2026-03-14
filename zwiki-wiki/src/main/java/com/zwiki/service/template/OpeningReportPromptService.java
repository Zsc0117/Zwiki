package com.zwiki.service.template;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.entity.ThesisAnalysisReport;
import com.zwiki.service.ThesisLLMService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningReportPromptService {

    private final ThesisLLMService llmService;
    private final TaskRepository taskRepository;

    private static final int MAX_SQL_CONTENT_LENGTH = 15000;

    @Value("${zwiki.thesis.opening-report-prompt-template:templates/ftl/opening_report_prompt.ftl}")
    private String promptTemplatePath;

    private String promptTemplateContent;

    @PostConstruct
    public void init() {
        loadPromptTemplate();
    }

    public String generateOpeningReportJson(String taskId, ThesisAnalysisReport report) {
        String sqlContent = searchAndLoadSqlFiles(taskId);
        String prompt = buildPrompt(report, sqlContent, null);
        return llmService.generateThesisContent(prompt);
    }

    public String generateOpeningReportJson(String taskId, ThesisAnalysisReport report, String additionalContext) {
        String sqlContent = searchAndLoadSqlFiles(taskId);
        String prompt = buildPrompt(report, sqlContent, additionalContext);
        return llmService.generateThesisContent(prompt);
    }

    /**
     * 搜索并加载项目中的SQL文件内容
     */
    private String searchAndLoadSqlFiles(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return "";
        }

        Task task = taskRepository.findFirstByTaskId(taskId).orElse(null);
        if (task == null || !StringUtils.hasText(task.getProjectPath())) {
            log.warn("无法获取任务{}的项目路径", taskId);
            return "";
        }

        String projectPath = task.getProjectPath();
        Path projectDir = Paths.get(projectPath);
        if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
            log.warn("项目路径不存在或不是目录: {}", projectPath);
            return "";
        }

        List<Path> sqlFiles = findSqlFiles(projectDir);
        if (sqlFiles.isEmpty()) {
            log.info("项目{}中未找到SQL文件", taskId);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# 项目数据库SQL文件内容（用于数据库设计分析）\n");

        int totalLength = 0;
        for (Path sqlFile : sqlFiles) {
            try {
                String content = Files.readString(sqlFile, StandardCharsets.UTF_8);
                String relativePath = projectDir.relativize(sqlFile).toString().replace('\\', '/');

                if (totalLength + content.length() > MAX_SQL_CONTENT_LENGTH) {
                    int remaining = MAX_SQL_CONTENT_LENGTH - totalLength;
                    if (remaining > 500) {
                        sb.append("\n## ").append(relativePath).append("\n");
                        sb.append("```sql\n");
                        sb.append(content, 0, Math.min(content.length(), remaining - 100));
                        sb.append("\n... (内容已截断)\n```\n");
                    }
                    sb.append("\n（更多SQL文件已省略，以控制提示词长度）");
                    break;
                }

                sb.append("\n## ").append(relativePath).append("\n");
                sb.append("```sql\n");
                sb.append(content);
                sb.append("\n```\n");
                totalLength += content.length();

            } catch (IOException e) {
                log.warn("读取SQL文件失败: {}", sqlFile, e);
            }
        }

        log.info("为任务{}加载了{}个SQL文件，总长度{}", taskId, sqlFiles.size(), totalLength);
        return sb.toString();
    }

    /**
     * 递归查找项目中的SQL文件
     */
    private List<Path> findSqlFiles(Path projectDir) {
        List<Path> sqlFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectDir, 10)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains(".git"))
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(sqlFiles::add);
        } catch (IOException e) {
            log.warn("搜索SQL文件失败: {}", projectDir, e);
        }
        return sqlFiles;
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
                log.info("开题报告 Prompt 模板加载成功: {}", promptTemplatePath);
            }
        } catch (Exception e) {
            log.warn("加载开题报告 prompt 模板失败: {}", e.getMessage());
            promptTemplateContent = "请根据项目说明生成开题报告的结构化 JSON 字段。";
        }
    }

    private String buildPrompt(ThesisAnalysisReport report, String sqlContent, String additionalContext) {
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
        variables.put("sqlContent", safeGet(sqlContent));
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
