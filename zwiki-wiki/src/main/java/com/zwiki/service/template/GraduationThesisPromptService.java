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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 毕业论文 Prompt 服务
 * 加载江西农业大学毕业论文格式模板，渲染 prompt，调用 LLM 生成论文内容
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraduationThesisPromptService {

    private final ThesisLLMService llmService;

    @Value("${zwiki.thesis.prompt-template:templates/ftl/graduation_thesis_prompt.ftl}")
    private String promptTemplatePath;

    private String promptTemplateContent;

    @PostConstruct
    public void init() {
        loadPromptTemplate();
    }

    @FunctionalInterface
    public interface ChapterProgressListener {
        void onProgress(int chapterIndex, int totalChapters, ChapterType chapterType);
    }

    public enum ChapterType {
        ABSTRACT_CN("中文摘要"),
        ABSTRACT_EN("英文摘要"),
        CHAPTER_1("第1章 绪论"),
        CHAPTER_2("第2章 可行性分析"),
        CHAPTER_3("第3章 系统需求分析"),
        CHAPTER_4("第4章 概要设计"),
        CHAPTER_5("第5章 详细设计"),
        CHAPTER_6("第6章 系统功能介绍"),
        CHAPTER_7("第7章 测试"),
        CHAPTER_8("第8章 总结与展望"),
        REFERENCES("参考文献"),
        ACKNOWLEDGEMENTS("致谢"),
        FULL("全文");

        private final String title;

        ChapterType(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    public String generateGraduationThesis(
            String taskId,
            ThesisAnalysisReport analysisReport,
            Map<String, String> thesisInfo) {
        return generateGraduationThesis(taskId, analysisReport, thesisInfo, null, null);
    }

    public String generateGraduationThesis(
            String taskId,
            ThesisAnalysisReport analysisReport,
            Map<String, String> thesisInfo,
            String additionalContext) {
        return generateGraduationThesis(taskId, analysisReport, thesisInfo, additionalContext, null);
    }

    public String generateGraduationThesis(
            String taskId,
            ThesisAnalysisReport analysisReport,
            Map<String, String> thesisInfo,
            ChapterProgressListener progressListener) {
        return generateGraduationThesis(taskId, analysisReport, thesisInfo, null, progressListener);
    }

    public String generateGraduationThesis(
            String taskId,
            ThesisAnalysisReport analysisReport,
            Map<String, String> thesisInfo,
            String additionalContext,
            ChapterProgressListener progressListener) {
        log.info("开始生成毕业论文, 任务ID: {}", taskId);

        String prompt = buildThesisPrompt(analysisReport, thesisInfo);
        if (additionalContext != null && !additionalContext.trim().isEmpty()) {
            prompt = prompt + "\n\n# 补充参考资料（来自项目记忆检索）\n" + additionalContext;
        }

        String result = generateThesisBySections(prompt, progressListener);
        if (result == null || result.trim().isEmpty()) {
            throw new RuntimeException("论文内容生成失败：LLM返回空内容");
        }

        result = ensureThesisComplete(prompt, result);
        return result;
    }

    private static class ThesisSection {
        private final ChapterType type;
        private final String requiredStartHeading;
        private final String stopBeforeHeading;
        private final int maxRetries;

        private ThesisSection(ChapterType type, String requiredStartHeading, String stopBeforeHeading, int maxRetries) {
            this.type = type;
            this.requiredStartHeading = requiredStartHeading;
            this.stopBeforeHeading = stopBeforeHeading;
            this.maxRetries = maxRetries;
        }
    }

    private String generateThesisBySections(String fullPrompt, ChapterProgressListener progressListener) {
        List<ThesisSection> sections = buildDefaultSections();
        int total = sections.size();

        String basePrompt = buildSectionBasePrompt(fullPrompt);
        StringBuilder assembled = new StringBuilder();

        for (int i = 0; i < sections.size(); i++) {
            ThesisSection section = sections.get(i);
            if (progressListener != null) {
                progressListener.onProgress(i, total, section.type);
            }

            String sectionText = generateOneSection(basePrompt, assembled.toString(), section);
            if (sectionText != null && !sectionText.isBlank()) {
                if (assembled.length() > 0 && !assembled.toString().endsWith("\n")) {
                    assembled.append("\n");
                }
                if (assembled.length() > 0) {
                    assembled.append("\n");
                }
                assembled.append(sectionText.trim());
            }

            if (progressListener != null) {
                progressListener.onProgress(i + 1, total, section.type);
            }
        }

        return assembled.toString();
    }

    private List<ThesisSection> buildDefaultSections() {
        List<ThesisSection> sections = new ArrayList<>();
        sections.add(new ThesisSection(ChapterType.ABSTRACT_CN, "# 摘要", "# Abstract", 2));
        sections.add(new ThesisSection(ChapterType.ABSTRACT_EN, "# Abstract", "# 1 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_1, "# 1 绪论", "# 2 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_2, "# 2 可行性分析", "# 3 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_3, "# 3 系统需求分析", "# 4 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_4, "# 4 概要设计", "# 5 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_5, "# 5 详细设计", "# 6 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_6, "# 6 系统功能介绍", "# 7 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_7, "# 7 测试", "# 8 ", 2));
        sections.add(new ThesisSection(ChapterType.CHAPTER_8, "# 8 总结与展望", "# 参考文献", 2));
        sections.add(new ThesisSection(ChapterType.REFERENCES, "# 参考文献", "# 致谢", 2));
        sections.add(new ThesisSection(ChapterType.ACKNOWLEDGEMENTS, "# 致谢", null, 2));
        return sections;
    }

    private String generateOneSection(String basePrompt, String alreadyGenerated, ThesisSection section) {
        String prompt = buildSectionPrompt(basePrompt, alreadyGenerated, section);

        String last = null;
        for (int attempt = 0; attempt <= section.maxRetries; attempt++) {
            String out = llmService.generateThesisContent(prompt);
            out = normalizeMarkdown(out);
            out = extractSectionOnly(out, section);
            last = out;

            if (isSectionValid(out, section)) {
                return out;
            }

            prompt = buildSectionFixPrompt(basePrompt, alreadyGenerated, section, out);
        }

        return last;
    }

    private String buildSectionPrompt(String basePrompt, String alreadyGenerated, ThesisSection section) {
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt);
        sb.append("\n\n# 分段生成模式\n");
        sb.append("你将分多次生成论文正文，以避免上下文/长度限制导致内容缺失。\n");
        sb.append("本次只生成以下部分：").append(section.type.getTitle()).append("。\n");
        sb.append("硬性要求：\n");
        sb.append("1. 只输出本部分内容，不要输出其它章节，不要输出解释。\n");
        sb.append("2. 输出必须以 \"").append(section.requiredStartHeading).append("\" 开头。\n");
        if (section.stopBeforeHeading != null && !section.stopBeforeHeading.isBlank()) {
            sb.append("3. 输出必须在出现 \"").append(section.stopBeforeHeading).append("\" 之前结束，不要包含后续章节。\n");
        }
        sb.append("4. 严格遵循模板中的标题编号与层级格式（# / ## / ###）。\n");

        if (alreadyGenerated != null && !alreadyGenerated.isBlank()) {
            sb.append("\n已生成内容末尾片段（用于衔接，不要重复输出）：\n");
            sb.append(buildTail(alreadyGenerated));
        }

        return sb.toString();
    }

    private String buildSectionFixPrompt(String basePrompt, String alreadyGenerated, ThesisSection section, String badOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt);
        sb.append("\n\n# 纠错重写\n");
        sb.append("你上一轮输出未满足硬性格式要求。请重写本次部分，严格遵守以下规则：\n");
        sb.append("1. 只输出本部分内容，不要输出其它章节，不要输出解释。\n");
        sb.append("2. 必须以 \"").append(section.requiredStartHeading).append("\" 开头。\n");
        if (section.stopBeforeHeading != null && !section.stopBeforeHeading.isBlank()) {
            sb.append("3. 必须在出现 \"").append(section.stopBeforeHeading).append("\" 之前结束，不要包含后续章节。\n");
        }
        sb.append("4. 严格使用Markdown标题与编号格式。\n");

        if (alreadyGenerated != null && !alreadyGenerated.isBlank()) {
            sb.append("\n已生成内容末尾片段（用于衔接，不要重复输出）：\n");
            sb.append(buildTail(alreadyGenerated));
        }

        if (badOutput != null && !badOutput.isBlank()) {
            sb.append("\n你上一轮错误输出片段（用于定位问题）：\n");
            sb.append(buildTail(badOutput));
        }

        return sb.toString();
    }

    private String buildTail(String text) {
        String t = text.trim();
        if (t.length() <= 2000) {
            return t;
        }
        return t.substring(t.length() - 2000);
    }

    private boolean isSectionValid(String text, ThesisSection section) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith(section.requiredStartHeading)) {
            return false;
        }
        if (section.stopBeforeHeading != null && !section.stopBeforeHeading.isBlank()) {
            int stopAt = indexOfIgnoreCase(trimmed, section.stopBeforeHeading);
            if (stopAt >= 0) {
                return false;
            }
        }
        if (section.type == ChapterType.REFERENCES) {
            int refCount = countReferenceItems(trimmed);
            return refCount >= 15;
        }
        return true;
    }

    private int countReferenceItems(String content) {
        int count = 0;
        String[] lines = content.split("\n");
        for (String line : lines) {
            String s = line.trim();
            if (s.matches("^\\[\\d+\\].*")) {
                count++;
            }
        }
        return count;
    }

    private String extractSectionOnly(String text, ThesisSection section) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int startAt = indexOfIgnoreCase(t, section.requiredStartHeading);
        if (startAt > 0) {
            t = t.substring(startAt).trim();
        }

        if (section.stopBeforeHeading != null && !section.stopBeforeHeading.isBlank()) {
            int stopAt = indexOfIgnoreCase(t, section.stopBeforeHeading);
            if (stopAt > 0) {
                t = t.substring(0, stopAt).trim();
            }
        }

        return t;
    }

    private int indexOfIgnoreCase(String text, String needle) {
        if (text == null || needle == null) {
            return -1;
        }
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private String normalizeMarkdown(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        normalized = normalized.replaceAll("^```markdown\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
        return normalized;
    }

    private String buildSectionBasePrompt(String fullPrompt) {
        if (fullPrompt == null || fullPrompt.isBlank()) {
            return fullPrompt;
        }

        String prompt = fullPrompt;
        String header = extractPrefix(prompt, "---", 2);
        String rules = extractBetween(prompt, "# 重要说明", "# 十二、论文章节结构模板");
        String structure = extractBetween(prompt, "# 十二、论文章节结构模板", "# 十三、生成要求");
        String requirements = extractFrom(prompt, "# 十三、生成要求");

        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) {
            sb.append(header.trim()).append("\n");
        }
        if (rules != null && !rules.isBlank()) {
            sb.append("\n").append(rules.trim()).append("\n");
        }
        if (structure != null && !structure.isBlank()) {
            sb.append("\n").append(structure.trim()).append("\n");
        }
        if (requirements != null && !requirements.isBlank()) {
            sb.append("\n").append(requirements.trim()).append("\n");
        }

        String compact = sb.toString().trim();
        return compact.isBlank() ? fullPrompt : compact;
    }

    private String extractBetween(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) {
            return "";
        }
        int e = text.indexOf(end, s + start.length());
        if (e < 0) {
            return text.substring(s);
        }
        return text.substring(s, e);
    }

    private String extractFrom(String text, String start) {
        int s = text.indexOf(start);
        if (s < 0) {
            return "";
        }
        return text.substring(s);
    }

    private String extractPrefix(String text, String separator, int occurrence) {
        if (text == null) {
            return "";
        }
        int idx = -1;
        int from = 0;
        for (int i = 0; i < occurrence; i++) {
            idx = text.indexOf(separator, from);
            if (idx < 0) {
                return text;
            }
            from = idx + separator.length();
        }
        return text.substring(0, from);
    }

    private String ensureThesisComplete(String originalPrompt, String initialContent) {
        String content = initialContent != null ? initialContent.trim() : "";
        if (content.isEmpty()) {
            return content;
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            List<String> missing = findMissingFinalSections(content);
            if (missing.isEmpty()) {
                return content;
            }

            String continuationPrompt = buildContinuationPrompt(originalPrompt, content, missing);
            String continuation = llmService.generateThesisContent(continuationPrompt);
            if (continuation == null || continuation.trim().isEmpty()) {
                return content;
            }

            String normalized = continuation.trim();
            normalized = normalized.replaceAll("^```markdown\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            content = content + "\n\n" + normalized;
        }

        return content;
    }

    private List<String> findMissingFinalSections(String content) {
        List<String> missing = new ArrayList<>();
        if (!containsHeading(content, "参考文献")) {
            missing.add("参考文献");
        }
        if (!containsHeading(content, "致谢")) {
            missing.add("致谢");
        }
        return missing;
    }

    private boolean containsHeading(String content, String title) {
        if (content == null || content.isBlank() || title == null || title.isBlank()) {
            return false;
        }
        Pattern h1 = Pattern.compile("(?m)^#\\s*" + Pattern.quote(title) + "\\s*$");
        Pattern anyLine = Pattern.compile("(?m)^" + Pattern.quote(title) + "\\s*$");
        return h1.matcher(content).find() || anyLine.matcher(content).find();
    }

    private String buildContinuationPrompt(String originalPrompt, String content, List<String> missing) {
        String tail = content.length() > 2000 ? content.substring(content.length() - 2000) : content;
        String missingText = String.join("、", missing);
        return originalPrompt
                + "\n\n# 续写补全任务\n"
                + "你上一轮生成的论文内容不完整，缺少以下章节：" + missingText + "。\n"
                + "请基于已生成内容继续写作，仅输出缺失部分（不要重复已生成内容）。\n"
                + "严格使用Markdown一级标题（# ）输出缺失章节，例如：# 参考文献、# 致谢。\n"
                + "注意：参考文献不少于15条，按GB/T 7714-2005格式；致谢为完整段落。\n\n"
                + "以下为已生成内容末尾片段（用于衔接）：\n"
                + tail;
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
                log.info("毕业论文 Prompt 模板加载成功: {}", promptTemplatePath);
            }
        } catch (Exception e) {
            log.warn("加载 prompt 模板失败，使用内置模板: {}", e.getMessage());
            promptTemplateContent = getDefaultPromptTemplate();
        }
    }

    private String buildThesisPrompt(ThesisAnalysisReport report, Map<String, String> thesisInfo) {
        String projectName = report.getProjectName() != null ? report.getProjectName() : "项目";
        String projectOverview = safeGet(report.getProjectOverview());
        String techStack = safeGet(report.getTechStack());
        String coreModules = safeGet(report.getCoreModulesAnalysis());
        String comprehensiveReport = safeGet(report.getComprehensiveReport());

        StringBuilder topicDescription = new StringBuilder();
        topicDescription.append("## 项目概述\n").append(projectOverview).append("\n\n");
        topicDescription.append("## 技术栈\n").append(techStack).append("\n\n");
        topicDescription.append("## 核心模块\n").append(coreModules).append("\n\n");
        topicDescription.append("## 综合分析\n").append(comprehensiveReport);

        Map<String, Object> variables = new HashMap<>();
        variables.put("title", thesisInfo.getOrDefault("title", projectName + "系统设计与实现"));
        variables.put("studentName", thesisInfo.getOrDefault("studentName", ""));
        variables.put("studentId", thesisInfo.getOrDefault("studentId", ""));
        variables.put("major", thesisInfo.getOrDefault("major", "计算机科学与技术"));
        variables.put("advisor", thesisInfo.getOrDefault("advisor", ""));
        variables.put("completionDate", thesisInfo.getOrDefault("completionDate",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月"))));
        variables.put("topicDescription", topicDescription.toString());

        return renderPromptTemplate(variables);
    }

    private String renderPromptTemplate(Map<String, Object> variables) {
        String result = promptTemplateContent != null ? promptTemplateContent : getDefaultPromptTemplate();
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

    private String getDefaultPromptTemplate() {
        return "请根据项目说明生成毕业论文内容，输出从摘要到致谢的完整论文正文。";
    }
}
