package com.zwiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.entity.Task;
import com.zwiki.service.notification.NotificationService;
import com.zwiki.repository.entity.ThesisFeedbackEntity;
import com.zwiki.repository.entity.ThesisVersionEntity;
import com.zwiki.repository.entity.ThesisAnalysisReport;
import com.zwiki.repository.dao.ThesisFeedbackRepository;
import com.zwiki.repository.dao.ThesisVersionRepository;
import com.zwiki.service.template.AIThesisDiagramService;
import com.zwiki.service.template.GraduationThesisPromptService;
import com.zwiki.service.template.IntelligentThesisFillerService;
import com.zwiki.service.template.OpeningReportPromptService;
import com.zwiki.service.template.TaskBookPromptService;
import com.zwiki.service.template.TemplateAnalyzerService;
import com.zwiki.service.template.ThesisDiagramManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交互式论文生成服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractiveThesisService {

    private final ThesisAnalysisReportService analysisReportService;
    private final ThesisVersionRepository thesisVersionRepository;
    private final ThesisFeedbackRepository thesisFeedbackRepository;
    private final TaskRepository taskRepository;
    private final TemplateAnalyzerService templateAnalyzerService;
    private final IntelligentThesisFillerService intelligentFillerService;
    private final GraduationThesisPromptService graduationThesisPromptService;
    private final TaskBookPromptService taskBookPromptService;
    private final OpeningReportPromptService openingReportPromptService;
    private final AIThesisDiagramService aiThesisDiagramService;
    private final ThesisDiagramManager thesisDiagramManager;
    private final ThesisProgressService thesisProgressService;
    private final MemoryRagService memoryRagService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${zwiki.workspace.base-path:}")
    private String workspaceBasePath;

    private static final String SCHOOL_LOGO_URL = "docs/assets/jxau2.png";
    private static final String SCHOOL_NAME_IMG_URL = "docs/assets/jxau1.png";

    private static final String DEFAULT_DOC_TYPE = "thesis";

    private String resolveUserId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        try {
            return taskRepository.findFirstByTaskId(taskId)
                    .map(Task::getUserId)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeDocType(String docType) {
        if (docType == null || docType.isBlank()) {
            return DEFAULT_DOC_TYPE;
        }
        return docType.trim();
    }

    private String getDocTypeDisplayName(String docType) {
        docType = normalizeDocType(docType);
        if ("task_book".equals(docType)) {
            return "任务书";
        } else if ("opening_report".equals(docType)) {
            return "开题报告";
        }
        return "毕业论文";
    }

    @Transactional
    public String generateDocumentWithDefaultPrompt(String taskId, String docType) {
        return generateDocumentWithDefaultPrompt(taskId, docType, null);
    }

    @Transactional
    public String generateDocumentWithDefaultPrompt(String taskId, String docType, Map<String, String> thesisInfo) {
        docType = normalizeDocType(docType);

        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }

        String userId = resolveUserId(taskId);
        String resourceUrl = "/thesis?taskId=" + taskId + "&docType=" + docType;

        try {
            if (thesisProgressService.getByDocType(taskId, docType).isEmpty()) {
                thesisProgressService.startByDocType(taskId, docType, "准备生成文档");
            }

            thesisProgressService.updateByDocType(taskId, docType, 10, "加载项目分析报告");
            ThesisAnalysisReport analysisReport = analysisReportService.buildReport(taskId);

            String ragQuery = (analysisReport != null && analysisReport.getProjectName() != null ? analysisReport.getProjectName() : "")
                    + " " + getDocTypeDisplayName(docType) + " 关键代码 设计文档";
            String additionalContext = memoryRagService.buildRagContextForTask(taskId, ragQuery, null);

            thesisInfo = normalizeThesisInfo(thesisInfo, analysisReport);

            thesisProgressService.updateByDocType(taskId, docType, 40, "调用AI生成文档内容");

            String content;
            if ("task_book".equals(docType)) {
                content = taskBookPromptService.generateTaskBookJson(taskId, analysisReport, additionalContext);
            } else if ("opening_report".equals(docType)) {
                content = openingReportPromptService.generateOpeningReportJson(taskId, analysisReport, additionalContext);
            } else {
                throw new IllegalArgumentException("不支持的文档类型: " + docType);
            }

            if (content == null || content.trim().isEmpty()) {
                throw new RuntimeException("文档内容生成失败：LLM返回空内容");
            }

            thesisProgressService.updateByDocType(taskId, docType, 70, "生成HTML预览");
            String htmlPreview = generateStructuredDocHtmlPreview(docType, content, analysisReport, thesisInfo);

            thesisProgressService.updateByDocType(taskId, docType, 85, "保存文档版本");
            Integer latestVersion = thesisVersionRepository.getLatestVersion(taskId, docType);
            Integer newVersion = (latestVersion != null ? latestVersion : 0) + 1;

            String markdownPath = saveMarkdownVersion(taskId, content, docType, newVersion);

            ThesisVersionEntity versionEntity = ThesisVersionEntity.builder()
                    .taskId(taskId)
                    .userId(resolveUserId(taskId))
                    .version(newVersion)
                    .thesisTitle(analysisReport != null ? analysisReport.getProjectName() : "")
                    .docType(docType)
                    .thesisInfo(objectMapper.writeValueAsString(thesisInfo))
                    .fullContent(content)
                    .htmlPreview(htmlPreview)
                    .markdownFilePath(markdownPath)
                    .status("preview")
                    .templatePath("default-" + docType + "-prompt")
                    .insertedDiagrams("{}")
                    .isCurrent(true)
                    .versionNotes("AI智能生成 - " + getDocTypeDisplayName(docType))
                    .build();

            thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType)
                    .ifPresent(old -> {
                        old.setIsCurrent(false);
                        thesisVersionRepository.save(old);
                    });

            thesisVersionRepository.save(versionEntity);
            thesisProgressService.completeByDocType(taskId, docType, "文档生成完成");

            if (userId != null && !userId.isBlank()) {
                String notifyTitle = thesisInfo != null ? thesisInfo.getOrDefault("title", "") : "";
                if (notifyTitle == null || notifyTitle.isBlank()) {
                    notifyTitle = analysisReport != null ? analysisReport.getProjectName() : "";
                }
                if (notifyTitle == null || notifyTitle.isBlank()) {
                    notifyTitle = getDocTypeDisplayName(docType);
                }
                notificationService.sendSystemNotification(
                        userId,
                        notifyTitle,
                        "✅ " + getDocTypeDisplayName(docType) + "生成完成",
                        resourceUrl,
                        taskId
                );
            }
            return versionEntity.getId().toString();
        } catch (IllegalArgumentException e) {
            thesisProgressService.failByDocType(taskId, docType, "生成失败: " + e.getMessage());
            if (userId != null && !userId.isBlank()) {
                notificationService.sendSystemNotification(
                        userId,
                        getDocTypeDisplayName(docType) + "生成失败",
                        "❌ " + getDocTypeDisplayName(docType) + "生成失败: " + e.getMessage(),
                        resourceUrl,
                        taskId
                );
            }
            throw e;
        } catch (Exception e) {
            thesisProgressService.failByDocType(taskId, docType, "生成失败: " + e.getMessage());
            if (userId != null && !userId.isBlank()) {
                notificationService.sendSystemNotification(
                        userId,
                        getDocTypeDisplayName(docType) + "生成失败",
                        "❌ " + getDocTypeDisplayName(docType) + "生成失败: " + e.getMessage(),
                        resourceUrl,
                        taskId
                );
            }
            throw new RuntimeException("生成文档失败: " + e.getMessage(), e);
        }
    }

    private String generateStructuredDocHtmlPreview(String docType, String content, ThesisAnalysisReport analysisReport, Map<String, String> thesisInfo) {
        Map<String, Object> data = parseJsonObject(content);
        if ("task_book".equals(docType)) {
            return buildTaskBookHtmlPreview(thesisInfo, data);
        }
        if ("opening_report".equals(docType)) {
            return buildOpeningReportHtmlPreview(data);
        }
        return "";
    }

    private Map<String, Object> parseJsonObject(String raw) {
        if (raw == null) {
            return new HashMap<>();
        }
        String text = raw.trim();
        text = stripCodeFence(text);

        Map<String, Object> direct = tryParseJsonObject(text);
        if (direct != null) {
            return direct;
        }

        String extracted = extractFirstJsonObject(text);
        if (extracted != null) {
            Map<String, Object> parsed = tryParseJsonObject(extracted);
            if (parsed != null) {
                return parsed;
            }
        }

        Map<String, Object> fallback = new HashMap<>();
        fallback.put("raw", raw);
        return fallback;
    }

    private String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        if (firstNewline > 0) {
            t = t.substring(firstNewline + 1);
        }
        int fenceEnd = t.lastIndexOf("```");
        if (fenceEnd >= 0) {
            t = t.substring(0, fenceEnd);
        }
        return t.trim();
    }

    private Map<String, Object> tryParseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignore) {
            return null;
        }
    }

    private String extractFirstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1).trim();
                }
            }
        }
        return null;
    }

    private String buildTaskBookHtmlPreview(Map<String, String> thesisInfo, Map<String, Object> data) {
        String title = thesisInfo != null ? thesisInfo.getOrDefault("title", "") : "";
        String studentName = thesisInfo != null ? thesisInfo.getOrDefault("studentName", "") : "";
        String studentId = thesisInfo != null ? thesisInfo.getOrDefault("studentId", "") : "";
        String major = thesisInfo != null ? thesisInfo.getOrDefault("major", "") : "";
        String className = thesisInfo != null ? thesisInfo.getOrDefault("className", "") : "";
        String majorClass = (major + (className == null || className.isBlank() ? "" : " " + className)).trim();
        String contentRequirements = String.valueOf(data.getOrDefault("contentRequirements", ""));
        String references = String.valueOf(data.getOrDefault("references", ""));
        String schedule = String.valueOf(data.getOrDefault("schedule", ""));

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'SimSun', serif; font-size: 14px; line-height: 1.6; max-width: 794px; margin: 0 auto; padding: 40px 0; background: #ffffff; }");
        html.append(".page { padding: 20px 50px 40px 50px; }");
        html.append("h1 { text-align: center; font-size: 24px; font-weight: bold; margin: 0 0 30px 0; font-family: 'SimHei', sans-serif; color: #333; letter-spacing: 2px; }");
        html.append("table { width: 100%; border-collapse: collapse; table-layout: fixed; }");
        html.append("td { border: 1px solid #333; padding: 12px 10px; vertical-align: top; font-size: 14px; word-break: break-all; overflow-wrap: break-word; }");
        html.append(".label { text-align: center; color: #000000; font-weight: normal; background: #fafafa; }");
        html.append(".value { text-align: left; }");
        html.append(".h-title { height: 40px; }");
        html.append(".h-info { height: 36px; }");
        html.append(".h-content { min-height: 180px; height: auto; }");
        html.append(".h-ref { min-height: 160px; height: auto; }");
        html.append(".h-schedule { min-height: 180px; height: auto; }");
        html.append(".h-sign { height: 70px; }");
        html.append(".section-header { color: #000000; font-weight: bold; margin-bottom: 10px; display: block; }");
        html.append(".content-text { white-space: pre-wrap; line-height: 1.8; }");
        html.append("</style></head><body><div class='content'>");
        html.append("<div class='page'>");
        html.append("<h1>江西农业大学本科毕业论文（设计）任务书</h1>");
        html.append("<table>");
        html.append("<colgroup>");
        html.append("<col style='width:14%' /><col style='width:20%' /><col style='width:10%' /><col style='width:14%' /><col style='width:14%' /><col style='width:28%' />");
        html.append("</colgroup>");

        html.append("<tr class='h-title'>");
        html.append("<td class='label'>课题名称</td>");
        html.append("<td colspan='5' class='value'>").append(escapeHtml(title)).append("</td>");
        html.append("</tr>");

        html.append("<tr class='h-info'>");
        html.append("<td class='label'>学生姓名</td>");
        html.append("<td class='value'>").append(escapeHtml(studentName)).append("</td>");
        html.append("<td class='label'>学号</td>");
        html.append("<td class='value'>").append(escapeHtml(studentId)).append("</td>");
        html.append("<td class='label'>专业、班级</td>");
        html.append("<td class='value'>").append(escapeHtml(majorClass)).append("</td>");
        html.append("</tr>");

        html.append("<tr class='h-content'><td colspan='6' class='value'>");
        html.append("<span class='section-header'>毕业论文（设计）内容要求：</span>");
        html.append("<div class='content-text'>").append(escapeHtmlAndStripMarkdown(contentRequirements)).append("</div>");
        html.append("</td></tr>");

        html.append("<tr class='h-ref'><td colspan='6' class='value'>");
        html.append("<span class='section-header'>主要参考资料：</span>");
        html.append("<div class='content-text'>").append(escapeHtmlAndStripMarkdown(references)).append("</div>");
        html.append("</td></tr>");

        html.append("<tr class='h-schedule'><td colspan='6' class='value'>");
        html.append("<span class='section-header'>毕业论文（设计）进度安排：</span>");
        html.append("<div class='content-text'>").append(escapeHtmlAndStripMarkdown(schedule)).append("</div>");
        html.append("</td></tr>");

        html.append("<tr class='h-sign'>");
        html.append("<td colspan='3' class='value' style='vertical-align: middle; text-align: left; padding-left: 20px;'><span style='color: #000000;'>指导教师签名：</span></td>");
        html.append("<td colspan='3' class='value' style='vertical-align: middle; text-align: left; padding-left: 20px;'><span style='color: #000000;'>学生签名：</span></td>");
        html.append("</tr>");

        html.append("</table>");
        html.append("</div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private boolean isAsciiTableLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("┌") || t.startsWith("│") || t.startsWith("├") || t.startsWith("└");
    }

    private String renderAsciiBoxTableToHtml(String asciiTable) {
        if (asciiTable == null || asciiTable.isBlank()) {
            return "";
        }
        List<List<String>> rows = new ArrayList<>();
        String[] lines = asciiTable.split("\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String t = line.trim();
            if (!t.startsWith("│")) {
                continue;
            }
            String[] parts = t.split("│");
            List<String> cells = new ArrayList<>();
            for (String part : parts) {
                String cell = part.trim();
                if (cell.isEmpty()) {
                    continue;
                }
                cells.add(cell);
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<tr>");
            List<String> row = rows.get(r);
            for (String cell : row) {
                String cellHtml = escapeHtmlAndStripMarkdown(cell).replace("\n", "<br/>");
                if (r == 0) {
                    sb.append("<th>").append(cellHtml).append("</th>");
                } else {
                    sb.append("<td>").append(cellHtml).append("</td>");
                }
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildOpeningReportHtmlPreview(Map<String, Object> data) {
        String purposeAndMeaning = String.valueOf(data.getOrDefault("purposeAndMeaning", ""));
        String planAndContent = String.valueOf(data.getOrDefault("planAndContent", ""));
        String methodsAndApproach = String.valueOf(data.getOrDefault("methodsAndApproach", ""));
        String implementationPlan = String.valueOf(data.getOrDefault("implementationPlan", ""));

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'SimSun', serif; font-size: 14px; line-height: 1.6; max-width: 794px; margin: 0 auto; padding: 40px 0; background: #ffffff; }");
        html.append(".page { padding: 20px 50px 40px 50px; }");
        html.append("h1 { text-align: center; font-size: 24px; font-weight: bold; margin: 0 0 30px 0; font-family: 'SimHei', sans-serif; color: #333; letter-spacing: 2px; }");
        html.append(".box { border: 1px solid #333; }");
        html.append(".section { border-bottom: 1px solid #333; padding: 20px 18px; min-height: 180px; height: auto; box-sizing: border-box; }");
        html.append(".section:last-child { border-bottom: none; }");
        html.append(".sec-title { color: #000000; font-weight: bold; margin-bottom: 12px; font-size: 14px; }");
        html.append(".sec-content { white-space: pre-wrap; word-break: break-all; overflow-wrap: break-word; line-height: 1.8; }");
        html.append("</style></head><body><div class='content'>");
        html.append("<div class='page'>");
        html.append("<h1>江西农业大学本科毕业论文（设计）开题报告</h1>");
        html.append("<div class='box'>");

        html.append("<div class='section'><div class='sec-title'>一、课题研究的目的和意义</div>")
                .append("<div class='sec-content'>").append(escapeHtmlAndStripMarkdown(purposeAndMeaning)).append("</div></div>");
        html.append("<div class='section'><div class='sec-title'>二、课题研究方案和主要内容</div>")
                .append("<div class='sec-content'>").append(escapeHtmlAndStripMarkdown(planAndContent)).append("</div></div>");
        html.append("<div class='section'><div class='sec-title'>三、课题研究方法及技术途径</div>")
                .append("<div class='sec-content'>").append(escapeHtmlAndStripMarkdown(methodsAndApproach)).append("</div></div>");
        html.append("<div class='section'><div class='sec-title'>四、实施计划</div>")
                .append("<div class='sec-content'>").append(escapeHtmlAndStripMarkdown(implementationPlan)).append("</div></div>");

        html.append("</div>");
        html.append("</div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    @Transactional
    public String generateThesisWithDefaultPrompt(String taskId, Map<String, String> thesisInfo) {
        return generateThesisWithDefaultPrompt(taskId, DEFAULT_DOC_TYPE, thesisInfo);
    }

    @Transactional
    public String generateThesisWithDefaultPrompt(String taskId, String docType, Map<String, String> thesisInfo) {
        docType = normalizeDocType(docType);
        if (!DEFAULT_DOC_TYPE.equals(docType)) {
            return generateDocumentWithDefaultPrompt(taskId, docType, thesisInfo);
        }
        log.info("使用默认 prompt 模板生成毕业论文, taskId={}", taskId);

        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }

        String userId = resolveUserId(taskId);
        String resourceUrl = "/thesis?taskId=" + taskId + "&docType=" + docType;

        try {
            if (thesisProgressService.getByDocType(taskId, docType).isEmpty()) {
                thesisProgressService.startByDocType(taskId, docType, "准备生成论文");
            }

            thesisProgressService.updateByDocType(taskId, docType, 5, "加载项目分析报告");
            ThesisAnalysisReport analysisReport = analysisReportService.buildReport(taskId);

            thesisInfo = normalizeThesisInfo(thesisInfo, analysisReport);

            thesisProgressService.updateByDocType(taskId, docType, 15, "生成论文图表");
            Map<String, String> availableDiagrams = generateAllDiagramsForThesis(taskId);
            thesisProgressService.updateByDocType(taskId, docType, 30, "图表生成完成");

            thesisProgressService.updateByDocType(taskId, docType, 35, "开始生成论文正文");
            String finalDocType = docType;
            String ragQuery = (analysisReport != null && analysisReport.getProjectName() != null ? analysisReport.getProjectName() : "")
                    + " 毕业论文 关键代码 设计文档";
            String additionalContext = memoryRagService.buildRagContextForTask(taskId, ragQuery, null);

            String thesisContent = graduationThesisPromptService.generateGraduationThesis(
                    taskId, analysisReport, thesisInfo, additionalContext,
                    (chapterIndex, totalChapters, chapterType) -> {
                        int mapped = 35 + (int) (((chapterIndex) * 50.0) / Math.max(totalChapters, 1));
                        thesisProgressService.updateByDocType(taskId, finalDocType, Math.min(mapped, 85), "生成章节: " + chapterType.getTitle());
                    });
            thesisProgressService.updateByDocType(taskId, docType, 85, "论文正文生成完成");

            if (thesisContent == null || thesisContent.trim().isEmpty()) {
                throw new RuntimeException("论文内容生成失败：LLM返回空内容");
            }

            if (thesisContent.trim().length() < 2000) {
                throw new RuntimeException("论文内容生成失败：生成内容过短（" + thesisContent.trim().length() + "字符）");
            }

            thesisProgressService.updateByDocType(taskId, docType, 88, "插入论文图表");
            thesisContent = thesisDiagramManager.intelligentlyInsertDiagrams(thesisContent, availableDiagrams);

            thesisProgressService.updateByDocType(taskId, docType, 90, "生成论文预览");
            String htmlPreview = convertMarkdownToHtmlPreview(thesisContent, availableDiagrams, thesisInfo);

            thesisProgressService.updateByDocType(taskId, docType, 95, "保存论文版本");
            Integer latestVersion = thesisVersionRepository.getLatestVersion(taskId, docType);
            Integer newVersion = (latestVersion != null ? latestVersion : 0) + 1;

            String markdownPath = saveMarkdownVersion(taskId, thesisContent, docType, newVersion);

            ThesisVersionEntity versionEntity = ThesisVersionEntity.builder()
                    .taskId(taskId)
                    .userId(resolveUserId(taskId))
                    .version(newVersion)
                    .thesisTitle(thesisInfo.get("title"))
                    .docType(docType)
                    .thesisInfo(objectMapper.writeValueAsString(thesisInfo))
                    .fullContent(thesisContent)
                    .htmlPreview(htmlPreview)
                    .markdownFilePath(markdownPath)
                    .status("preview")
                    .templatePath("default-graduation-thesis-prompt")
                    .insertedDiagrams(objectMapper.writeValueAsString(availableDiagrams))
                    .isCurrent(true)
                    .versionNotes("AI智能生成 - 毕业论文")
                    .build();

            thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType)
                    .ifPresent(old -> {
                        old.setIsCurrent(false);
                        thesisVersionRepository.save(old);
                    });

            thesisVersionRepository.save(versionEntity);

            log.info("毕业论文生成完成, version={}, path={}, diagrams={}", newVersion, markdownPath, availableDiagrams.size());
            return versionEntity.getId().toString();

        } catch (IllegalArgumentException e) {
            thesisProgressService.failByDocType(taskId, docType, "生成失败: " + e.getMessage());
            if (userId != null && !userId.isBlank()) {
                notificationService.sendSystemNotification(
                        userId,
                        getDocTypeDisplayName(docType) + "生成失败",
                        "❌ " + getDocTypeDisplayName(docType) + "生成失败: " + e.getMessage(),
                        resourceUrl,
                        taskId
                );
            }
            throw e;
        } catch (Exception e) {
            log.error("生成毕业论文失败", e);
            thesisProgressService.failByDocType(taskId, docType, "生成失败: " + e.getMessage());
            if (userId != null && !userId.isBlank()) {
                notificationService.sendSystemNotification(
                        userId,
                        getDocTypeDisplayName(docType) + "生成失败",
                        "❌ " + getDocTypeDisplayName(docType) + "生成失败: " + e.getMessage(),
                        resourceUrl,
                        taskId
                );
            }
            throw new RuntimeException("生成论文失败: " + e.getMessage(), e);
        }
    }

    private Map<String, String> parseThesisInfo(String thesisInfoJson) {
        if (thesisInfoJson == null || thesisInfoJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(thesisInfoJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }

    private Map<String, String> normalizeThesisInfo(Map<String, String> thesisInfo, ThesisAnalysisReport analysisReport) {
        Map<String, String> normalized = thesisInfo != null ? new HashMap<>(thesisInfo) : new HashMap<>();
        String projectName = analysisReport != null ? analysisReport.getProjectName() : null;
        String defaultTitle = (projectName != null ? projectName : "项目") + "系统设计与实现";
        normalized.putIfAbsent("title", defaultTitle);
        normalized.putIfAbsent("college", "");
        normalized.putIfAbsent("studentName", "");
        normalized.putIfAbsent("studentId", "");
        normalized.putIfAbsent("major", "");
        normalized.putIfAbsent("className", "");
        normalized.putIfAbsent("advisor", "");
        normalized.putIfAbsent("defenseDate", "");
        return normalized;
    }

    @Transactional
    public String regenerateThesis(String taskId, Map<String, String> thesisInfo, boolean clearOldVersions) {
        return regenerateThesis(taskId, DEFAULT_DOC_TYPE, thesisInfo, clearOldVersions);
    }

    @Transactional
    public String regenerateThesis(String taskId, String docType, Map<String, String> thesisInfo, boolean clearOldVersions) {
        docType = normalizeDocType(docType);
        log.info("重新生成论文, taskId={}, docType={}, clearOldVersions={}", taskId, docType, clearOldVersions);
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }

        if (clearOldVersions) {
            if (DEFAULT_DOC_TYPE.equals(docType)) {
                try {
                    thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType)
                            .map(ThesisVersionEntity::getInsertedDiagrams)
                            .map(this::parseInsertedDiagrams)
                            .filter(map -> map != null && !map.isEmpty())
                            .ifPresent(map -> saveDiagramCache(taskId, map));
                } catch (Exception ignored) {
                }
            }
            thesisVersionRepository.deleteByTaskIdAndDocType(taskId, docType);
            thesisFeedbackRepository.deleteByTaskIdAndDocType(taskId, docType);
        }

        thesisProgressService.clearByDocType(taskId, docType);
        thesisProgressService.startByDocType(taskId, docType, "重新生成论文");
        return generateThesisWithDefaultPrompt(taskId, docType, thesisInfo);
    }

    @Transactional
    public Map<String, Object> uploadAndAnalyzeTemplate(String taskId, MultipartFile templateFile) {
        log.info("上传并解析论文模板, taskId={}", taskId);
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }

        try {
            String templatePath;
            if (templateFile != null && !templateFile.isEmpty()) {
                String filename = templateFile.getOriginalFilename();
                if (filename == null || !filename.toLowerCase().endsWith(".docx")) {
                    throw new IllegalArgumentException("模板文件必须是 .docx 格式");
                }
                if (templateFile.getSize() > 10 * 1024 * 1024) {
                    throw new IllegalArgumentException("模板文件大小不能超过 10MB");
                }
                templatePath = saveUploadedTemplate(taskId, templateFile);
            } else {
                ThesisAnalysisReport report = analysisReportService.buildReport(taskId);
                templatePath = templateAnalyzerService.generateDefaultTemplate(
                        taskId,
                        report.getProjectName() != null ? report.getProjectName() : "项目",
                        resolveWorkspaceBasePath());
            }

            Map<String, Object> templateStructure = templateAnalyzerService.analyzeTemplate(
                    createMultipartFileFromPath(Paths.get(templatePath)), taskId);
            templateStructure.put("templatePath", templatePath);
            return templateStructure;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("上传和解析模板失败", e);
            throw new RuntimeException("模板处理失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public String intelligentFillThesis(String taskId, String templatePath) {
        log.info("开始智能填充论文, taskId={}", taskId);

        String docType = DEFAULT_DOC_TYPE;

        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        if (templatePath == null || templatePath.trim().isEmpty()) {
            throw new IllegalArgumentException("模板路径不能为空");
        }
        if (!Files.exists(Paths.get(templatePath))) {
            throw new IllegalArgumentException("模板文件不存在: " + templatePath);
        }

        try (FileInputStream templateStream = new FileInputStream(templatePath);
             XWPFDocument document = new XWPFDocument(templateStream)) {

            ThesisAnalysisReport analysisReport = analysisReportService.buildReport(taskId);

            Map<String, String> availableDiagrams = generateAllDiagramsForThesis(taskId);
            Map<String, String> diagramDescriptions = intelligentFillerService
                    .generateAllDiagramDescriptions(availableDiagrams, analysisReport);

            Map<String, Object> filledContent = new HashMap<>();
            List<Map<String, String>> diagramInsertions = new java.util.ArrayList<>();

            for (int i = 0; i < document.getParagraphs().size(); i++) {
                XWPFParagraph paragraph = document.getParagraphs().get(i);
                String text = paragraph.getText();

                if (text != null && text.contains("{{") && text.contains("}}")) {
                    String context = extractContext(document, i);
                    String filledText = intelligentFillerService.intelligentFillContent(
                            text, context, analysisReport);
                    replaceParagraphText(paragraph, filledText);
                    filledContent.put("paragraph_" + i, filledText);
                }

                if (shouldInsertDiagram(paragraph, analysisReport)) {
                    Map<String, String> insertion = suggestDiagramForParagraphEnhanced(
                            paragraph, analysisReport, availableDiagrams);
                    if (insertion != null) {
                        String diagramType = insertion.get("diagramType");
                        String description = diagramDescriptions.get(diagramType);
                        insertion.put("description", description != null ? description : "");
                        diagramInsertions.add(insertion);
                    }
                }
            }

            insertDiagramsIntelligentlyEnhanced(document, diagramInsertions, availableDiagrams, taskId);

            Integer latestVersion = thesisVersionRepository.getLatestVersion(taskId, docType);
            Integer newVersion = (latestVersion != null ? latestVersion : 0) + 1;
            String docxPath = saveThesisVersion(document, taskId, docType, newVersion);

            Map<String, String> thesisInfo = normalizeThesisInfo(null, analysisReport);
            String htmlPreview = generateHtmlPreviewWithDiagrams(document, availableDiagrams, thesisInfo);
            ThesisVersionEntity versionEntity = ThesisVersionEntity.builder()
                    .taskId(taskId)
                    .userId(resolveUserId(taskId))
                    .version(newVersion)
                    .thesisTitle(thesisInfo.get("title"))
                    .docType(docType)
                    .thesisInfo(objectMapper.writeValueAsString(thesisInfo))
                    .fullContent(objectMapper.writeValueAsString(filledContent))
                    .htmlPreview(htmlPreview)
                    .docxFilePath(docxPath)
                    .status("preview")
                    .templatePath(templatePath)
                    .insertedDiagrams(objectMapper.writeValueAsString(availableDiagrams))
                    .isCurrent(true)
                    .versionNotes("初始版本 - AI自动填充（含图表）")
                    .build();

            thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType)
                    .ifPresent(old -> {
                        old.setIsCurrent(false);
                        thesisVersionRepository.save(old);
                    });

            thesisVersionRepository.save(versionEntity);

            log.info("论文填充完成, version={}, path={}, diagrams={}", newVersion, docxPath, diagramInsertions.size());
            return versionEntity.getId().toString();

        } catch (Exception e) {
            log.error("智能填充论文失败", e);
            throw new RuntimeException("填充论文失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Long submitFeedback(String taskId, Integer version, String section, String feedbackType, String feedbackContent) {
        return submitFeedback(taskId, DEFAULT_DOC_TYPE, version, section, feedbackType, feedbackContent);
    }

    @Transactional
    public Long submitFeedback(String taskId, String docType, Integer version, String section, String feedbackType, String feedbackContent) {
        docType = normalizeDocType(docType);
        ThesisFeedbackEntity feedback = ThesisFeedbackEntity.builder()
                .taskId(taskId)
                .userId(resolveUserId(taskId))
                .docType(docType)
                .version(version)
                .section(section)
                .feedbackType(feedbackType)
                .feedbackContent(feedbackContent)
                .processed(false)
                .build();

        thesisFeedbackRepository.save(feedback);
        return feedback.getId();
    }

    @Transactional
    public String optimizeThesisBasedOnFeedback(String taskId, Long feedbackId) throws IOException {
        ThesisFeedbackEntity feedback = thesisFeedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new RuntimeException("反馈不存在"));

        String docType = normalizeDocType(feedback.getDocType());

        ThesisVersionEntity currentVersion = thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType)
                .orElseThrow(() -> new RuntimeException("当前版本不存在"));

        ThesisAnalysisReport analysisReport = analysisReportService.buildReport(taskId);

        if (currentVersion.getDocxFilePath() != null && Files.exists(Paths.get(currentVersion.getDocxFilePath()))) {
            return optimizeDocxVersion(currentVersion, feedback, analysisReport);
        }

        return optimizeMarkdownVersion(currentVersion, feedback, analysisReport);
    }

    public String getThesisPreview(String taskId, Integer version) {
        return getThesisPreview(taskId, DEFAULT_DOC_TYPE, version);
    }

    public String getThesisPreview(String taskId, String docType, Integer version) {
        docType = normalizeDocType(docType);
        Optional<ThesisVersionEntity> versionEntityOpt = version != null
                ? thesisVersionRepository.findByTaskIdAndDocTypeAndVersion(taskId, docType, version)
                : thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType);

        return versionEntityOpt.map(ThesisVersionEntity::getHtmlPreview).orElse(null);
    }

    public String getThesisMarkdownContent(String taskId, Integer version) {
        return getThesisMarkdownContent(taskId, DEFAULT_DOC_TYPE, version);
    }

    public String getThesisMarkdownContent(String taskId, String docType, Integer version) {
        docType = normalizeDocType(docType);
        Optional<ThesisVersionEntity> versionEntityOpt = version != null
                ? thesisVersionRepository.findByTaskIdAndDocTypeAndVersion(taskId, docType, version)
                : thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType);

        return versionEntityOpt.map(ThesisVersionEntity::getFullContent).orElse(null);
    }

    public List<ThesisVersionEntity> getVersionHistory(String taskId) {
        return getVersionHistory(taskId, DEFAULT_DOC_TYPE);
    }

    public List<ThesisVersionEntity> getVersionHistory(String taskId, String docType) {
        docType = normalizeDocType(docType);
        List<ThesisVersionEntity> versions = thesisVersionRepository.findByTaskIdAndDocTypeOrderByVersionDesc(taskId, docType);
        List<ThesisVersionEntity> result = new ArrayList<>(versions.size());
        for (ThesisVersionEntity v : versions) {
            String notes = v.getVersionNotes();
            if (notes == null || notes.isBlank()) {
                notes = "AI智能生成 - " + getDocTypeDisplayName(v.getDocType());
            }
            ThesisVersionEntity copy = ThesisVersionEntity.builder()
                    .id(v.getId())
                    .taskId(v.getTaskId())
                    .userId(v.getUserId())
                    .version(v.getVersion())
                    .thesisTitle(v.getThesisTitle())
                    .docType(v.getDocType())
                    .thesisInfo(v.getThesisInfo())
                    .abstractContent(v.getAbstractContent())
                    .keywords(v.getKeywords())
                    .chapterContents(v.getChapterContents())
                    .referencesList(v.getReferencesList())
                    .fullContent(v.getFullContent())
                    .htmlPreview(v.getHtmlPreview())
                    .docxFilePath(v.getDocxFilePath())
                    .markdownFilePath(v.getMarkdownFilePath())
                    .pdfFilePath(v.getPdfFilePath())
                    .status(v.getStatus())
                    .versionNotes(notes)
                    .templatePath(v.getTemplatePath())
                    .insertedDiagrams(v.getInsertedDiagrams())
                    .isCurrent(v.getIsCurrent())
                    .createdAt(v.getCreatedAt())
                    .build();
            result.add(copy);
        }
        return result;
    }

    @Transactional
    public void confirmFinalVersion(String taskId, Integer version) {
        confirmFinalVersion(taskId, DEFAULT_DOC_TYPE, version);
    }

    @Transactional
    public void confirmFinalVersion(String taskId, String docType, Integer version) {
        docType = normalizeDocType(docType);
        ThesisVersionEntity versionEntity = thesisVersionRepository.findByTaskIdAndDocTypeAndVersion(taskId, docType, version)
                .orElseThrow(() -> new RuntimeException("版本不存在"));
        versionEntity.setStatus("final");
        thesisVersionRepository.save(versionEntity);
    }

    private String optimizeDocxVersion(ThesisVersionEntity currentVersion,
                                       ThesisFeedbackEntity feedback,
                                       ThesisAnalysisReport analysisReport) {
        try (FileInputStream fis = new FileInputStream(currentVersion.getDocxFilePath());
             XWPFDocument document = new XWPFDocument(fis)) {

            XWPFParagraph targetParagraph = findParagraphBySection(document, feedback.getSection());
            if (targetParagraph != null) {
                String originalContent = targetParagraph.getText();
                feedback.setOriginalContent(originalContent);

                String optimizedContent = intelligentFillerService.optimizeContentBasedOnFeedback(
                        originalContent, feedback.getFeedbackContent(), analysisReport);

                replaceParagraphText(targetParagraph, optimizedContent);
                feedback.setOptimizedContent(optimizedContent);
                feedback.setProcessed(true);
                thesisFeedbackRepository.save(feedback);
            }

            Integer newVersion = currentVersion.getVersion() + 1;
            String newDocxPath = saveThesisVersion(document, currentVersion.getTaskId(), currentVersion.getDocType(), newVersion);
            Map<String, String> thesisInfo = parseThesisInfo(currentVersion.getThesisInfo());
            String htmlPreview = generateHtmlPreview(document, thesisInfo);

            ThesisVersionEntity newVersionEntity = ThesisVersionEntity.builder()
                    .taskId(currentVersion.getTaskId())
                    .userId(resolveUserId(currentVersion.getTaskId()))
                    .version(newVersion)
                    .fullContent(currentVersion.getFullContent())
                    .thesisTitle(currentVersion.getThesisTitle())
                    .docType(currentVersion.getDocType())
                    .thesisInfo(currentVersion.getThesisInfo())
                    .htmlPreview(htmlPreview)
                    .docxFilePath(newDocxPath)
                    .status("preview")
                    .templatePath(currentVersion.getTemplatePath())
                    .insertedDiagrams(currentVersion.getInsertedDiagrams())
                    .isCurrent(true)
                    .versionNotes("根据用户反馈优化：" + feedback.getFeedbackType())
                    .build();

            currentVersion.setIsCurrent(false);
            thesisVersionRepository.save(currentVersion);
            thesisVersionRepository.save(newVersionEntity);

            return newVersionEntity.getId().toString();
        } catch (Exception e) {
            throw new RuntimeException("优化失败: " + e.getMessage(), e);
        }
    }

    private String optimizeMarkdownVersion(ThesisVersionEntity currentVersion,
                                           ThesisFeedbackEntity feedback,
                                           ThesisAnalysisReport analysisReport) throws IOException {
        String originalContent = currentVersion.getFullContent();
        if (originalContent == null) {
            throw new RuntimeException("当前版本内容为空，无法优化");
        }

        String optimizedContent = intelligentFillerService.optimizeContentBasedOnFeedback(
                originalContent, feedback.getFeedbackContent(), analysisReport);
        feedback.setOriginalContent(originalContent);
        feedback.setOptimizedContent(optimizedContent);
        feedback.setProcessed(true);
        thesisFeedbackRepository.save(feedback);

        Integer newVersion = currentVersion.getVersion() + 1;
        String markdownPath = saveMarkdownVersion(currentVersion.getTaskId(), optimizedContent, currentVersion.getDocType(), newVersion);
        Map<String, String> diagrams = parseInsertedDiagrams(currentVersion.getInsertedDiagrams());
        Map<String, String> thesisInfo = normalizeThesisInfo(parseThesisInfo(currentVersion.getThesisInfo()), analysisReport);
        String htmlPreview = convertMarkdownToHtmlPreview(optimizedContent, diagrams, thesisInfo);

        ThesisVersionEntity newVersionEntity = ThesisVersionEntity.builder()
                .taskId(currentVersion.getTaskId())
                .userId(resolveUserId(currentVersion.getTaskId()))
                .version(newVersion)
                .thesisTitle(thesisInfo.get("title"))
                .docType(currentVersion.getDocType())
                .thesisInfo(objectMapper.writeValueAsString(thesisInfo))
                .fullContent(optimizedContent)
                .htmlPreview(htmlPreview)
                .markdownFilePath(markdownPath)
                .status("preview")
                .templatePath(currentVersion.getTemplatePath())
                .insertedDiagrams(currentVersion.getInsertedDiagrams())
                .isCurrent(true)
                .versionNotes("根据用户反馈优化：" + feedback.getFeedbackType())
                .build();

        currentVersion.setIsCurrent(false);
        thesisVersionRepository.save(currentVersion);
        thesisVersionRepository.save(newVersionEntity);

        return newVersionEntity.getId().toString();
    }

    private Map<String, String> generateAllDiagramsForThesis(String taskId) {
        Map<String, String> cached = loadDiagramCache(taskId);
        if (!cached.isEmpty()) {
            return cached;
        }

        try {
            Map<String, String> aiDiagrams = aiThesisDiagramService.generateAllThesisDiagrams(taskId);
            Map<String, String> result = aiDiagrams != null ? aiDiagrams : new LinkedHashMap<>();
            if (!result.isEmpty()) {
                saveDiagramCache(taskId, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("生成毕业论文图表失败", e);
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> loadDiagramCache(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Path cacheFile = Paths.get(resolveWorkspaceBasePath(), taskId, "diagrams", "thesis-diagrams.json");
            if (!Files.exists(cacheFile)) {
                return new LinkedHashMap<>();
            }
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, String> cached = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            return cached != null ? cached : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void saveDiagramCache(String taskId, Map<String, String> diagrams) {
        if (taskId == null || taskId.isBlank() || diagrams == null || diagrams.isEmpty()) {
            return;
        }
        try {
            Path dir = Paths.get(resolveWorkspaceBasePath(), taskId, "diagrams");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve("thesis-diagrams.json");
            Files.writeString(cacheFile, objectMapper.writeValueAsString(diagrams), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private String saveMarkdownVersion(String taskId, String content, String docType, Integer version) throws IOException {
        Path outputDir = Paths.get(resolveWorkspaceBasePath(), taskId, "output");
        Files.createDirectories(outputDir);
        String normalizedDocType = normalizeDocType(docType);
        String filename = DEFAULT_DOC_TYPE.equals(normalizedDocType)
                ? String.format("thesis_v%d.md", version)
                : String.format("%s_v%d.md", normalizedDocType, version);
        Path outputPath = outputDir.resolve(filename);
        Files.writeString(outputPath, content, StandardCharsets.UTF_8);
        return outputPath.toString();
    }

    private Map<String, String> parseInsertedDiagrams(String insertedDiagrams) {
        if (insertedDiagrams == null || insertedDiagrams.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(insertedDiagrams, new TypeReference<Map<String, String>>() {});
        } catch (Exception ignored) {
            // fallback for list structure
        }
        try {
            List<Map<String, String>> list = objectMapper.readValue(
                    insertedDiagrams, new TypeReference<List<Map<String, String>>>() {});
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, String> item : list) {
                if (item == null) {
                    continue;
                }
                String type = item.get("diagramType");
                String path = item.get("diagramPath");
                if (type != null && path != null && !result.containsKey(type)) {
                    result.put(type, path);
                }
            }
            return result;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String saveThesisVersion(XWPFDocument document, String taskId, String docType, Integer version) throws IOException {
        Path outputDir = Paths.get(resolveWorkspaceBasePath(), taskId, "output");
        Files.createDirectories(outputDir);
        String normalizedDocType = normalizeDocType(docType);
        String filename;
        if (DEFAULT_DOC_TYPE.equals(normalizedDocType)) {
            filename = String.format("毕业论文_v%d.docx", version);
        } else if ("task_book".equals(normalizedDocType)) {
            filename = String.format("任务书_v%d.docx", version);
        } else if ("opening_report".equals(normalizedDocType)) {
            filename = String.format("开题报告_v%d.docx", version);
        } else {
            filename = String.format("%s_v%d.docx", normalizedDocType, version);
        }
        Path outputPath = outputDir.resolve(filename);
        try (FileOutputStream out = new FileOutputStream(outputPath.toFile())) {
            document.write(out);
        }
        return outputPath.toString();
    }

    private String convertMarkdownToHtmlPreview(String markdownContent,
                                                Map<String, String> diagrams,
                                                Map<String, String> thesisInfo) {
        if (thesisInfo == null) {
            thesisInfo = new HashMap<>();
        }
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'SimSun', serif; font-size: 16px; line-height: 1.6; max-width: 794px; margin: 0 auto; padding: 40px; background: #f5f5f5; }");
        html.append(".content { background: white; padding: 96px 96px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); min-height: 1123px; }");
        html.append("h1 { text-align: left; font-size: 19px; font-weight: bold; margin: 24px 0; font-family: 'SimHei', sans-serif; }");
        html.append("h2 { text-align: left; font-size: 16px; font-weight: bold; margin: 18px 0; font-family: 'SimHei', sans-serif; }");
        html.append("h3 { text-align: left; font-size: 16px; font-weight: normal; margin: 12px 0; font-family: 'SimSun', serif; }");
        html.append("p { text-indent: 2em; margin: 0 0; text-align: justify; line-height: 1.6; }");
        html.append(".center { text-align: center; text-indent: 0; }");
        html.append(".abstract { margin: 30px 0; }");
        html.append(".keywords { font-weight: bold; margin-top: 10px; }");
        html.append(".figure { margin: 20px 0; text-align: center; page-break-inside: avoid; }");
        html.append(".figure img { max-width: 70%; height: auto; margin-bottom: 10px; }");
        html.append(".figure-caption { text-align: center; font-family: 'SimSun', serif; font-size: 14px; margin-top: 5px; }");
        html.append("table { width: 100%; border-collapse: collapse; margin: 20px 0; border-top: 2px solid #000; border-bottom: 2px solid #000; }");
        html.append("th { border: none; border-bottom: 1px solid #000; padding: 8px; text-align: center; font-family: 'SimHei', sans-serif; font-weight: bold; font-size: 14px; }");
        html.append("td { border: none; padding: 8px; text-align: center; font-size: 14px; }");
        html.append("code { background: #f5f5f5; padding: 2px 6px; border-radius: 3px; font-family: 'Consolas', monospace; }");
        html.append("pre { background: #f5f5f5; padding: 15px; border-radius: 5px; overflow-x: auto; font-family: 'Consolas', monospace; font-size: 14px; }");
        html.append(".cover-page { text-align: center; page-break-after: always; padding: 40px 0; font-family: 'SimSun', serif; }");
        html.append(".cover-page .school-name-img { width: 320px; height: auto; margin: 0 auto 10px auto; display: block; }");
        html.append(".cover-page .school-name-en { font-family: 'Times New Roman', serif; font-size: 14px; font-weight: bold; margin: 10px 0 30px 0; }");
        html.append(".cover-page .thesis-type { font-family: 'STXingkai', 'KaiTi', serif; font-size: 48px; font-weight: bold; color: #C55A11; margin: 40px 0; }");
        html.append(".cover-page .school-logo { width: 100px; height: 100px; margin: 30px 0; }");
        html.append(".cover-page .info-section { text-align: left; margin: 40px auto; width: 480px; }");
        html.append(".cover-page .info-line { display: flex; margin: 15px 0; align-items: flex-end; }");
        html.append(".cover-page .info-line .label { width: 100px; flex-shrink: 0; text-align: justify; text-align-last: justify; font-weight: bold; }");
        html.append(".cover-page .info-line .value { flex: 1; border-bottom: 1px solid #000; padding: 0 10px; text-align: center; font-weight: bold; min-height: 28px; }");
        html.append(".cover-page .info-line.title-line .label { font-family: 'SimHei', sans-serif; font-size: 21px; }");
        html.append(".cover-page .info-line.title-line .value { font-family: 'SimHei', sans-serif; font-size: 21px; }");
        html.append(".cover-page .info-line:not(.title-line) .label { font-family: 'SimSun', serif; font-size: 24px; }");
        html.append(".cover-page .info-line:not(.title-line) .value { font-family: 'SimSun', serif; font-size: 24px; font-weight: normal; }");
        html.append(".originality-page { page-break-after: always; padding: 40px 0; }");
        html.append(".originality-page h2 { text-align: center; font-size: 21px; font-family: 'SimHei', sans-serif; margin: 20px 0; }");
        html.append(".originality-page .statement { text-indent: 2em; line-height: 2.0; margin: 30px 0; font-size: 16px; }");
        html.append(".originality-page .signature { margin: 40px 0 20px 0; font-size: 16px; text-align: right; padding-right: 40px; }");
        html.append(".toc-page { page-break-after: always; padding: 20px 0; }");
        html.append(".toc-page h2 { text-align: center; font-size: 19px; font-family: 'SimHei', sans-serif; margin-bottom: 40px; letter-spacing: 2px; }");
        html.append(".toc-entry { margin: 5px 0; font-size: 16px; font-family: 'SimSun', serif; display: flex; justify-content: space-between; align-items: baseline; }");
        html.append(".toc-entry .title { background: #fff; padding-right: 5px; z-index: 1; }");
        html.append(".toc-entry .dots { flex: 1; border-bottom: 1px dotted #000; margin: 0 5px; transform: translateY(-4px); }");
        html.append(".toc-entry .page-num { background: #fff; padding-left: 5px; z-index: 1; }");
        html.append(".toc-entry.level-1 { margin-left: 1em; }");
        html.append(".toc-entry.level-2 { margin-left: 2em; }");
        html.append("</style></head><body><div class='content'>");

        html.append("<div class='cover-page'>");
        html.append("<img class='school-name-img' src='").append(resolveProjectImageDataUri(SCHOOL_NAME_IMG_URL)).append("' alt='江西农业大学' />");
        html.append("<div class='school-name-en'>JIANGXI  AGRICULTURAL  UNIVERSITY</div>");
        html.append("<div class='thesis-type'>本 科 毕 业 论 文</div>");
        html.append("<img class='school-logo' src='").append(resolveProjectImageDataUri(SCHOOL_LOGO_URL)).append("' alt='校徽' />");
        html.append("<div class='info-section'>");
        html.append("<div class='info-line title-line'><span class='label'>题    目：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("title", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>学    院：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("college", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>姓    名：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("studentName", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>学    号：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("studentId", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>专    业：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("major", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>班    级：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("className", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>指导教师：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("advisor", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>答辩日期：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("defenseDate", "")))
                .append("</span></div>");
        html.append("</div></div>");

        html.append("<div class='originality-page'>");
        html.append("<h2>江西农业大学</h2>");
        html.append("<h2>本科毕业论文原创性声明</h2>");
        html.append("<p class='statement'>本人郑重声明：所呈交的本科毕业论文是本人在导师的指导下独立进行研究工作所取得的成果。尽我所知，除文中已经注明引用的内容外，本论文不包含任何其他个人或集体已经发表或撰写过的研究成果。对论文所涉及的研究工作做出贡献的其他个人和集体，均已在文中以明确方式标明或致谢。</p>");
        html.append("<div class='signature'>作者签名：</div>");
        html.append("<div class='signature'>日    期：</div>");
        html.append("</div>");

        html.append("<div class='toc-page'>");
        html.append("<h2>目    录</h2>");
        html.append("<div id='toc-content'></div>");
        html.append("</div>");

        int figureNumber = 1;
        boolean inCodeBlock = false;
        boolean inTable = false;
        int tableRowIndex = 0;
        boolean inAsciiTable = false;
        boolean skipBasicInfo = true;
        boolean hasEmbeddedImages = markdownContent != null && markdownContent.contains("![");
        Pattern markdownImagePattern = Pattern.compile("^!\\[([^\\]]*)\\]\\(([^\\)]+)\\)\\s*$");
        StringBuilder asciiTableContent = new StringBuilder();
        Set<String> insertedDiagrams = new HashSet<>();
        Map<String, String> diagramChapterMapping = buildDiagramChapterMapping();
        Map<String, String> diagramDisplayNames = buildDiagramDisplayNames();

        String[] lines = markdownContent.split("\n");

        StringBuilder tocHtml = new StringBuilder();
        for (String line : lines) {
            if (skipBasicInfo) {
                if (line.contains("摘要") || line.contains("Abstract") || line.contains("摘 要")) {
                    skipBasicInfo = false;
                } else {
                    continue;
                }
            }

            Matcher imageMatcher = markdownImagePattern.matcher(line.trim());
            if (imageMatcher.find()) {
                String caption = imageMatcher.group(1) != null ? imageMatcher.group(1).trim() : "";
                String diagramPath = imageMatcher.group(2) != null ? imageMatcher.group(2).trim() : "";
                if (!diagramPath.isEmpty()) {
                    String diagramKey = findDiagramKeyByPath(diagrams, diagramPath);
                    String displayName = caption;
                    if ((displayName == null || displayName.isBlank()) && diagramKey != null) {
                        displayName = diagramKey;
                    }

                    String imgSrc = resolveImageSrc(diagramPath);
                    html.append("<div class='figure'>");
                    html.append("<img src='").append(imgSrc).append("' alt='").append(escapeHtml(displayName)).append("'/>");
                    html.append("<div class='figure-caption'>图").append(figureNumber)
                            .append(" ").append(escapeHtml(displayName)).append("</div>");
                    html.append("</div>");

                    if (diagramKey != null) {
                        insertedDiagrams.add(diagramKey);
                    }
                    figureNumber++;
                }
                continue;
            }

            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("</pre>");
                    inCodeBlock = false;
                } else {
                    html.append("<pre><code>");
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                html.append(escapeHtml(line)).append("\n");
                continue;
            }

            if (isAsciiTableLine(line)) {
                if (!inAsciiTable) {
                    inAsciiTable = true;
                    asciiTableContent = new StringBuilder();
                }
                asciiTableContent.append(line).append("\n");
                continue;
            } else if (inAsciiTable) {
                html.append(renderAsciiBoxTableToHtml(asciiTableContent.toString()));
                inAsciiTable = false;
            }

            if (line.startsWith("# ")) {
                String title = line.substring(2).trim();
                html.append("<h1>").append(escapeHtmlAndStripMarkdown(title)).append("</h1>");
                if (!hasEmbeddedImages) {
                    figureNumber = insertDiagramAfterTitle(html, title, diagrams, diagramChapterMapping,
                            diagramDisplayNames, insertedDiagrams, figureNumber);
                }
            } else if (line.startsWith("## ")) {
                String title = line.substring(3).trim();
                html.append("<h2>").append(escapeHtmlAndStripMarkdown(title)).append("</h2>");
                if (!hasEmbeddedImages) {
                    figureNumber = insertDiagramAfterTitle(html, title, diagrams, diagramChapterMapping,
                            diagramDisplayNames, insertedDiagrams, figureNumber);
                }
            } else if (line.startsWith("### ")) {
                String title = line.substring(4).trim();
                html.append("<h3>").append(escapeHtmlAndStripMarkdown(title)).append("</h3>");
                if (!hasEmbeddedImages) {
                    figureNumber = insertDiagramAfterTitle(html, title, diagrams, diagramChapterMapping,
                            diagramDisplayNames, insertedDiagrams, figureNumber);
                }
            } else if (line.startsWith("#### ")) {
                html.append("<h4>").append(escapeHtmlAndStripMarkdown(line.substring(5))).append("</h4>");
            } else if (line.startsWith("|")) {
                if (!inTable) {
                    html.append("<table>");
                    inTable = true;
                    tableRowIndex = 0;
                }
                if (line.contains("---")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                html.append("<tr>");
                for (String cell : cells) {
                    if (!cell.trim().isEmpty()) {
                        String cellHtml = escapeHtmlAndStripMarkdown(cell.trim()).replace("\n", "<br/>");
                        if (tableRowIndex == 0) {
                            html.append("<th>").append(cellHtml).append("</th>");
                        } else {
                            html.append("<td>").append(cellHtml).append("</td>");
                        }
                    }
                }
                html.append("</tr>");
                tableRowIndex++;
            } else {
                if (inTable) {
                    html.append("</table>");
                    inTable = false;
                }
                if (!line.trim().isEmpty()) {
                    String pHtml = escapeHtmlAndStripMarkdown(line).replace("\n", "<br/>");
                    html.append("<p>").append(pHtml).append("</p>");
                }
            }
        }

        if (inTable) {
            html.append("</table>");
        }
        if (inAsciiTable) {
            html.append(renderAsciiBoxTableToHtml(asciiTableContent.toString()));
        }
        if (inCodeBlock) {
            html.append("</code></pre>");
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    private int insertDiagramAfterTitle(StringBuilder html, String title,
                                        Map<String, String> diagrams, Map<String, String> chapterMapping,
                                        Map<String, String> displayNames, Set<String> inserted, int figureNumber) {
        for (Map.Entry<String, String> entry : chapterMapping.entrySet()) {
            String keyword = entry.getKey();
            String diagramKey = entry.getValue();

            if (title.contains(keyword) && !inserted.contains(diagramKey) && diagrams.containsKey(diagramKey)) {
                String diagramPath = diagrams.get(diagramKey);
                String displayName = displayNames.getOrDefault(diagramKey, "图表");

                String imgSrc = resolveImageSrc(diagramPath);

                html.append("<div class='figure'>");
                html.append("<img src='").append(imgSrc).append("' alt='").append(displayName).append("'/>");
                html.append("<div class='figure-caption'>图").append(figureNumber)
                        .append(" ").append(displayName).append("</div>");
                html.append("</div>");

                inserted.add(diagramKey);
                figureNumber++;
                break;
            }
        }
        return figureNumber;
    }

    private String resolveImageSrc(String diagramPath) {
        if (diagramPath == null) {
            return "";
        }
        if (diagramPath.startsWith("http://") || diagramPath.startsWith("https://")) {
            return diagramPath;
        }
        try {
            Path path = Paths.get(diagramPath);
            if (Files.exists(path)) {
                byte[] bytes = Files.readAllBytes(path);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                return "data:image/png;base64," + base64;
            }
        } catch (Exception e) {
            log.debug("读取图表失败: {}", e.getMessage());
        }
        return "file:///" + diagramPath.replace("\\", "/");
    }

    private String findDiagramKeyByPath(Map<String, String> diagrams, String diagramPath) {
        if (diagrams == null || diagrams.isEmpty() || diagramPath == null || diagramPath.isBlank()) {
            return null;
        }
        String normalizedTarget = diagramPath.trim().replace("\\", "/");
        for (Map.Entry<String, String> entry : diagrams.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String normalizedValue = entry.getValue().trim().replace("\\", "/");
            if (normalizedValue.equalsIgnoreCase(normalizedTarget)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String extractContext(XWPFDocument document, int paragraphIndex) {
        StringBuilder context = new StringBuilder();
        int start = Math.max(0, paragraphIndex - 2);
        int end = Math.min(document.getParagraphs().size() - 1, paragraphIndex + 2);
        for (int i = start; i <= end; i++) {
            if (i != paragraphIndex) {
                context.append(document.getParagraphs().get(i).getText()).append("\n");
            }
        }
        return context.toString();
    }

    private boolean shouldInsertDiagram(XWPFParagraph paragraph, ThesisAnalysisReport report) {
        String text = paragraph.getText();
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("架构") || lower.contains("设计")
                || lower.contains("结构") || lower.contains("流程")
                || lower.contains("模块") || lower.contains("类图");
    }

    private Map<String, String> suggestDiagramForParagraphEnhanced(
            XWPFParagraph paragraph,
            ThesisAnalysisReport report,
            Map<String, String> availableDiagrams) {

        String text = paragraph.getText().toLowerCase();
        String diagramType = null;

        if (text.contains("分层") || text.contains("架构层")) {
            diagramType = "layerArchitecture";
        } else if (text.contains("组件") && text.contains("交互")) {
            diagramType = "componentInteraction";
        } else if (text.contains("类图") || text.contains("类结构")) {
            diagramType = "classDiagram";
        } else if (text.contains("包依赖") || text.contains("模块依赖")) {
            diagramType = "packageDependency";
        } else if (text.contains("时序") || text.contains("调用链") || text.contains("调用流程")) {
            diagramType = "sequenceDiagram";
        } else if (text.contains("业务流程") || text.contains("处理流程")) {
            diagramType = "businessFlow";
        } else if (text.contains("状态") && (text.contains("转换") || text.contains("流转"))) {
            diagramType = "stateMachine";
        } else if (text.contains("设计模式") || text.contains("模式应用")) {
            diagramType = "designPatterns";
        } else if (text.contains("部署") && text.contains("架构")) {
            diagramType = "deploymentArchitecture";
        } else if (text.contains("监控") && (text.contains("架构") || text.contains("体系"))) {
            diagramType = "monitoringArchitecture";
        } else if (text.contains("er图") || text.contains("实体关系")) {
            diagramType = "erDiagram";
        } else if (text.contains("表结构") || text.contains("数据表")) {
            diagramType = "tableStructure";
        } else if (text.contains("模块关系") || text.contains("模块划分")) {
            diagramType = "moduleRelation";
        }

        if (diagramType != null && availableDiagrams.containsKey(diagramType)) {
            Map<String, String> suggestion = new HashMap<>();
            suggestion.put("afterParagraph", text);
            suggestion.put("diagramType", diagramType);
            suggestion.put("diagramPath", availableDiagrams.get(diagramType));
            return suggestion;
        }

        return null;
    }

    private void insertDiagramsIntelligentlyEnhanced(
            XWPFDocument document,
            List<Map<String, String>> insertions,
            Map<String, String> availableDiagrams,
            String taskId) {

        int figureNumber = 1;

        for (Map<String, String> insertion : insertions) {
            String diagramType = insertion.get("diagramType");
            String diagramPath = insertion.get("diagramPath");
            String description = insertion.get("description");

            if (diagramPath == null || !Files.exists(Paths.get(diagramPath))) {
                continue;
            }

            try (FileInputStream imageStream = new FileInputStream(diagramPath)) {
                XWPFParagraph imageParagraph = document.createParagraph();
                imageParagraph.setAlignment(ParagraphAlignment.CENTER);
                imageParagraph.createRun().addPicture(
                        imageStream,
                        XWPFDocument.PICTURE_TYPE_PNG,
                        diagramType + ".png",
                        500 * 9525,
                        300 * 9525);

                XWPFParagraph captionParagraph = document.createParagraph();
                captionParagraph.setAlignment(ParagraphAlignment.CENTER);
                captionParagraph.createRun().setText(String.format("图%d %s", figureNumber++, diagramType));

                if (description != null && !description.isEmpty()) {
                    XWPFParagraph descParagraph = document.createParagraph();
                    descParagraph.createRun().setText(description);
                }
            } catch (Exception e) {
                log.warn("插入图表失败: {}", diagramType, e);
            }
        }
    }

    private String generateHtmlPreviewWithDiagrams(XWPFDocument document,
                                                   Map<String, String> availableDiagrams,
                                                   Map<String, String> thesisInfo) {
        if (thesisInfo == null) {
            thesisInfo = new HashMap<>();
        }
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'SimSun', serif; font-size: 16px; line-height: 1.6; max-width: 794px; margin: 0 auto; padding: 40px; background: #f5f5f5; }");
        html.append(".content { background: white; padding: 96px 96px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); min-height: 1123px; }");
        html.append("h1 { text-align: left; font-size: 19px; font-weight: bold; margin: 24px 0; font-family: 'SimHei', sans-serif; }");
        html.append("h2 { text-align: left; font-size: 16px; font-weight: bold; margin: 18px 0; font-family: 'SimHei', sans-serif; }");
        html.append("h3 { text-align: left; font-size: 16px; font-weight: normal; margin: 12px 0; font-family: 'SimSun', serif; }");
        html.append("p { text-indent: 2em; margin: 0 0; text-align: justify; line-height: 1.6; }");
        html.append(".center { text-align: center; text-indent: 0; }");
        html.append(".figure { margin: 20px 0; text-align: center; page-break-inside: avoid; }");
        html.append(".figure img { max-width: 70%; height: auto; margin-bottom: 10px; }");
        html.append(".figure-caption { text-align: center; font-family: 'SimSun', serif; font-size: 14px; margin-top: 5px; }");
        html.append(".cover-page { text-align: center; page-break-after: always; padding: 40px 0; font-family: 'SimSun', serif; }");
        html.append(".cover-page .school-name-img { width: 320px; height: auto; margin: 0 auto 10px auto; display: block; }");
        html.append(".cover-page .school-name-en { font-family: 'Times New Roman', serif; font-size: 14px; font-weight: bold; margin: 10px 0 30px 0; }");
        html.append(".cover-page .thesis-type { font-family: 'STXingkai', 'KaiTi', serif; font-size: 48px; font-weight: bold; color: #C55A11; margin: 40px 0; }");
        html.append(".cover-page .school-logo { width: 100px; height: 100px; margin: 30px 0; }");
        html.append(".cover-page .info-section { text-align: left; margin: 40px auto; width: 480px; }");
        html.append(".cover-page .info-line { display: flex; margin: 15px 0; align-items: flex-end; }");
        html.append(".cover-page .info-line .label { width: 100px; flex-shrink: 0; text-align: justify; text-align-last: justify; font-weight: bold; }");
        html.append(".cover-page .info-line .value { flex: 1; border-bottom: 1px solid #000; padding: 0 10px; text-align: center; font-weight: bold; min-height: 28px; }");
        html.append(".cover-page .info-line.title-line .label { font-family: 'SimHei', sans-serif; font-size: 21px; }");
        html.append(".cover-page .info-line.title-line .value { font-family: 'SimHei', sans-serif; font-size: 21px; }");
        html.append(".cover-page .info-line:not(.title-line) .label { font-family: 'SimSun', serif; font-size: 24px; }");
        html.append(".cover-page .info-line:not(.title-line) .value { font-family: 'SimSun', serif; font-size: 24px; font-weight: normal; }");
        html.append(".originality-page { page-break-after: always; padding: 40px 0; }");
        html.append(".originality-page h2 { text-align: center; font-size: 21px; font-family: 'SimHei', sans-serif; margin: 20px 0; }");
        html.append(".originality-page .statement { text-indent: 2em; line-height: 2.0; margin: 30px 0; font-size: 16px; }");
        html.append(".originality-page .signature { margin: 40px 0 20px 0; font-size: 16px; text-align: right; padding-right: 40px; }");
        html.append(".toc-page { page-break-after: always; padding: 20px 0; }");
        html.append(".toc-page h2 { text-align: center; font-size: 19px; font-family: 'SimHei', sans-serif; margin-bottom: 40px; letter-spacing: 2px; }");
        html.append(".toc-entry { margin: 5px 0; font-size: 16px; font-family: 'SimSun', serif; display: flex; justify-content: space-between; align-items: baseline; }");
        html.append(".toc-entry .title { background: #fff; padding-right: 5px; z-index: 1; }");
        html.append(".toc-entry .dots { flex: 1; border-bottom: 1px dotted #000; margin: 0 5px; transform: translateY(-4px); }");
        html.append(".toc-entry .page-num { background: #fff; padding-left: 5px; z-index: 1; }");
        html.append(".toc-entry.level-1 { margin-left: 1em; }");
        html.append(".toc-entry.level-2 { margin-left: 2em; }");
        html.append("</style></head><body><div class='content'>");

        html.append("<div class='cover-page'>");
        html.append("<img class='school-name-img' src='").append(resolveProjectImageDataUri(SCHOOL_NAME_IMG_URL)).append("' alt='江西农业大学' />");
        html.append("<div class='school-name-en'>JIANGXI  AGRICULTURAL  UNIVERSITY</div>");
        html.append("<div class='thesis-type'>本 科 毕 业 论 文</div>");
        html.append("<img class='school-logo' src='").append(resolveProjectImageDataUri(SCHOOL_LOGO_URL)).append("' alt='校徽' />");
        html.append("<div class='info-section'>");
        html.append("<div class='info-line title-line'><span class='label'>题    目：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("title", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>学    院：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("college", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>姓    名：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("studentName", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>学    号：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("studentId", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>专    业：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("major", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>班    级：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("className", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>指导教师：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("advisor", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>答辩日期：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("defenseDate", "")))
                .append("</span></div>");
        html.append("</div></div>");

        html.append("<div class='originality-page'>");
        html.append("<h2>江西农业大学</h2>");
        html.append("<h2>本科毕业论文原创性声明</h2>");
        html.append("<p class='statement'>本人郑重声明：所呈交的本科毕业论文是本人在导师的指导下独立进行研究工作所取得的成果。尽我所知，除文中已经注明引用的内容外，本论文不包含任何其他个人或集体已经发表或撰写过的研究成果。对论文所涉及的研究工作做出贡献的其他个人和集体，均已在文中以明确方式标明或致谢。</p>");
        html.append("<div class='signature'>作者签名：</div>");
        html.append("<div class='signature'>日    期：</div>");
        html.append("</div>");

        html.append("<div class='toc-page'>");
        html.append("<h2>目    录</h2>");
        html.append("<div id='toc-content'></div>");
        html.append("</div>");

        List<String> tocEntries = new ArrayList<>();
        boolean skipBasicInfo = true;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            if (skipBasicInfo) {
                if (trimmed.contains("摘要") || trimmed.contains("Abstract") || trimmed.contains("摘 要")) {
                    skipBasicInfo = false;
                } else {
                    continue;
                }
            }
            int level = resolveHeadingLevel(trimmed, paragraph);
            if (level >= 0) {
                tocEntries.add(level + "::" + trimmed);
            }
        }

        if (!tocEntries.isEmpty()) {
            StringBuilder tocHtml = new StringBuilder();
            for (String entry : tocEntries) {
                String[] parts = entry.split("::", 2);
                int level = Integer.parseInt(parts[0]);
                String title = escapeHtml(parts[1]);
                String levelClass = level == 1 ? " level-1" : level == 2 ? " level-2" : "";
                tocHtml.append("<div class='toc-entry").append(levelClass).append("'><span class='title'>")
                        .append(title)
                        .append("</span><span class='dots'></span><span class='page-num'></span></div>");
            }
            int tocIndex = html.indexOf("<div id='toc-content'></div>");
            if (tocIndex > 0) {
                html.replace(tocIndex, tocIndex + "<div id='toc-content'></div>".length(),
                        "<div id='toc-content'>" + tocHtml + "</div>");
            }
        }

        skipBasicInfo = true;
        int figureNumber = 1;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            if (skipBasicInfo) {
                if (trimmed.contains("摘要") || trimmed.contains("Abstract") || trimmed.contains("摘 要")) {
                    skipBasicInfo = false;
                } else {
                    continue;
                }
            }

            int level = resolveHeadingLevel(trimmed, paragraph);
            ParagraphAlignment alignment = paragraph.getAlignment();
            if (level == 0) {
                html.append("<h1>").append(escapeHtml(trimmed)).append("</h1>");
            } else if (level == 1) {
                html.append("<h2>").append(escapeHtml(trimmed)).append("</h2>");
            } else if (level == 2) {
                html.append("<h3>").append(escapeHtml(trimmed)).append("</h3>");
            } else {
                String className = alignment == ParagraphAlignment.CENTER ? " class='center'" : "";
                html.append("<p").append(className).append(">")
                        .append(escapeHtml(trimmed))
                        .append("</p>");
            }

            String lowercaseText = trimmed.toLowerCase();
            for (Map.Entry<String, String> diagram : availableDiagrams.entrySet()) {
                if (shouldInsertDiagramAfterText(lowercaseText, diagram.getKey())) {
                    html.append(generateFigureHtml(diagram.getKey(), diagram.getValue(), figureNumber++));
                    break;
                }
            }
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    private boolean shouldInsertDiagramAfterText(String text, String diagramType) {
        return switch (diagramType) {
            case "layerArchitecture" -> text.contains("架构") || text.contains("分层");
            case "classDiagram" -> text.contains("类图") || text.contains("类结构");
            case "sequenceDiagram" -> text.contains("时序") || text.contains("调用");
            case "businessFlow" -> text.contains("流程");
            case "designPatterns" -> text.contains("设计模式");
            case "deploymentArchitecture" -> text.contains("部署");
            case "erDiagram" -> text.contains("数据库") || text.contains("er图");
            default -> false;
        };
    }

    private String generateFigureHtml(String diagramType, String diagramPath, int figureNumber) {
        StringBuilder html = new StringBuilder();
        String imgSrc = resolveImageSrc(diagramPath);
        html.append("<div class='figure'>");
        html.append(String.format("<img src='%s' alt='%s' />", imgSrc, diagramType));
        html.append(String.format("<div class='figure-caption'>图%d %s</div>", figureNumber, diagramType));
        html.append("</div>");
        return html.toString();
    }

    private String generateHtmlPreview(XWPFDocument document, Map<String, String> thesisInfo) {
        if (thesisInfo == null) {
            thesisInfo = new HashMap<>();
        }
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'SimSun', serif; font-size: 16px; line-height: 1.6; max-width: 794px; margin: 0 auto; padding: 40px; background: #f5f5f5; }");
        html.append(".content { background: white; padding: 96px 96px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); min-height: 1123px; }");
        html.append("h1 { text-align: left; font-size: 19px; font-weight: bold; margin: 24px 0; font-family: 'SimHei', sans-serif; }");
        html.append("h2 { text-align: left; font-size: 16px; font-weight: bold; margin: 18px 0; font-family: 'SimHei', sans-serif; }");
        html.append("h3 { text-align: left; font-size: 16px; font-weight: normal; margin: 12px 0; font-family: 'SimSun', serif; }");
        html.append("p { text-indent: 2em; margin: 0 0; text-align: justify; line-height: 1.6; }");
        html.append(".center { text-align: center; text-indent: 0; }");
        html.append(".cover-page { text-align: center; page-break-after: always; padding: 40px 0; font-family: 'SimSun', serif; }");
        html.append(".cover-page .school-name-img { width: 320px; height: auto; margin: 0 auto 10px auto; display: block; }");
        html.append(".cover-page .school-name-en { font-family: 'Times New Roman', serif; font-size: 14px; font-weight: bold; margin: 10px 0 30px 0; }");
        html.append(".cover-page .thesis-type { font-family: 'STXingkai', 'KaiTi', serif; font-size: 48px; font-weight: bold; color: #C55A11; margin: 40px 0; }");
        html.append(".cover-page .school-logo { width: 100px; height: 100px; margin: 30px 0; }");
        html.append(".cover-page .info-section { text-align: left; margin: 40px auto; width: 480px; }");
        html.append(".cover-page .info-line { display: flex; margin: 15px 0; align-items: flex-end; }");
        html.append(".cover-page .info-line .label { width: 100px; flex-shrink: 0; text-align: justify; text-align-last: justify; font-weight: bold; }");
        html.append(".cover-page .info-line .value { flex: 1; border-bottom: 1px solid #000; padding: 0 10px; text-align: center; font-weight: bold; min-height: 28px; }");
        html.append(".cover-page .info-line.title-line .label { font-family: 'SimHei', sans-serif; font-size: 21px; }");
        html.append(".cover-page .info-line.title-line .value { font-family: 'SimHei', sans-serif; font-size: 21px; }");
        html.append(".cover-page .info-line:not(.title-line) .label { font-family: 'SimSun', serif; font-size: 24px; }");
        html.append(".cover-page .info-line:not(.title-line) .value { font-family: 'SimSun', serif; font-size: 24px; font-weight: normal; }");
        html.append(".originality-page { page-break-after: always; padding: 40px 0; }");
        html.append(".originality-page h2 { text-align: center; font-size: 21px; font-family: 'SimHei', sans-serif; margin: 20px 0; }");
        html.append(".originality-page .statement { text-indent: 2em; line-height: 2.0; margin: 30px 0; font-size: 16px; }");
        html.append(".originality-page .signature { margin: 40px 0 20px 0; font-size: 16px; text-align: right; padding-right: 40px; }");
        html.append(".toc-page { page-break-after: always; padding: 20px 0; }");
        html.append(".toc-page h2 { text-align: center; font-size: 19px; font-family: 'SimHei', sans-serif; margin-bottom: 40px; letter-spacing: 2px; }");
        html.append(".toc-entry { margin: 5px 0; font-size: 16px; font-family: 'SimSun', serif; display: flex; justify-content: space-between; align-items: baseline; }");
        html.append(".toc-entry .title { background: #fff; padding-right: 5px; z-index: 1; }");
        html.append(".toc-entry .dots { flex: 1; border-bottom: 1px dotted #000; margin: 0 5px; transform: translateY(-4px); }");
        html.append(".toc-entry .page-num { background: #fff; padding-left: 5px; z-index: 1; }");
        html.append(".toc-entry.level-1 { margin-left: 1em; }");
        html.append(".toc-entry.level-2 { margin-left: 2em; }");
        html.append("</style></head><body><div class='content'>");

        html.append("<div class='cover-page'>");
        html.append("<img class='school-name-img' src='").append(resolveProjectImageDataUri(SCHOOL_NAME_IMG_URL)).append("' alt='江西农业大学' />");
        html.append("<div class='school-name-en'>JIANGXI  AGRICULTURAL  UNIVERSITY</div>");
        html.append("<div class='thesis-type'>本 科 毕 业 论 文</div>");
        html.append("<img class='school-logo' src='").append(resolveProjectImageDataUri(SCHOOL_LOGO_URL)).append("' alt='校徽' />");
        html.append("<div class='info-section'>");
        html.append("<div class='info-line title-line'><span class='label'>题    目：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("title", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>学    院：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("college", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>姓    名：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("studentName", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>学    号：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("studentId", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>专    业：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("major", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>班    级：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("className", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>指导教师：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("advisor", "")))
                .append("</span></div>");
        html.append("<div class='info-line'><span class='label'>答辩日期：</span><span class='value'>")
                .append(escapeHtml(thesisInfo.getOrDefault("defenseDate", "")))
                .append("</span></div>");
        html.append("</div></div>");

        html.append("<div class='originality-page'>");
        html.append("<h2>江西农业大学</h2>");
        html.append("<h2>本科毕业论文原创性声明</h2>");
        html.append("<p class='statement'>本人郑重声明：所呈交的本科毕业论文是本人在导师的指导下独立进行研究工作所取得的成果。尽我所知，除文中已经注明引用的内容外，本论文不包含任何其他个人或集体已经发表或撰写过的研究成果。对论文所涉及的研究工作做出贡献的其他个人和集体，均已在文中以明确方式标明或致谢。</p>");
        html.append("<div class='signature'>作者签名：</div>");
        html.append("<div class='signature'>日    期：</div>");
        html.append("</div>");

        html.append("<div class='toc-page'>");
        html.append("<h2>目    录</h2>");
        html.append("<div id='toc-content'></div>");
        html.append("</div>");

        List<String> tocEntries = new ArrayList<>();
        boolean skipBasicInfo = true;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            if (skipBasicInfo) {
                if (trimmed.contains("摘要") || trimmed.contains("Abstract") || trimmed.contains("摘 要")) {
                    skipBasicInfo = false;
                } else {
                    continue;
                }
            }
            int level = resolveHeadingLevel(trimmed, paragraph);
            if (level >= 0) {
                tocEntries.add(level + "::" + trimmed);
            }
        }

        if (!tocEntries.isEmpty()) {
            StringBuilder tocHtml = new StringBuilder();
            for (String entry : tocEntries) {
                String[] parts = entry.split("::", 2);
                int level = Integer.parseInt(parts[0]);
                String title = escapeHtml(parts[1]);
                String levelClass = level == 1 ? " level-1" : level == 2 ? " level-2" : "";
                tocHtml.append("<div class='toc-entry").append(levelClass).append("'><span class='title'>")
                        .append(title)
                        .append("</span><span class='dots'></span><span class='page-num'></span></div>");
            }
            int tocIndex = html.indexOf("<div id='toc-content'></div>");
            if (tocIndex > 0) {
                html.replace(tocIndex, tocIndex + "<div id='toc-content'></div>".length(),
                        "<div id='toc-content'>" + tocHtml + "</div>");
            }
        }

        skipBasicInfo = true;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            if (skipBasicInfo) {
                if (trimmed.contains("摘要") || trimmed.contains("Abstract") || trimmed.contains("摘 要")) {
                    skipBasicInfo = false;
                } else {
                    continue;
                }
            }

            int level = resolveHeadingLevel(trimmed, paragraph);
            ParagraphAlignment alignment = paragraph.getAlignment();
            if (level == 0) {
                html.append("<h1>").append(escapeHtml(trimmed)).append("</h1>");
            } else if (level == 1) {
                html.append("<h2>").append(escapeHtml(trimmed)).append("</h2>");
            } else if (level == 2) {
                html.append("<h3>").append(escapeHtml(trimmed)).append("</h3>");
            } else {
                String className = alignment == ParagraphAlignment.CENTER ? " class='center'" : "";
                html.append("<p").append(className).append(">")
                        .append(escapeHtml(trimmed))
                        .append("</p>");
            }
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    private int resolveHeadingLevel(String text, XWPFParagraph paragraph) {
        if (text == null) {
            return -1;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        if (isSpecialHeading(trimmed) || trimmed.matches("^\\d+\\s+.*")) {
            return 0;
        }
        if (trimmed.matches("^\\d+\\.\\d+\\s+.*")) {
            return 1;
        }
        if (trimmed.matches("^\\d+\\.\\d+\\.\\d+\\s+.*")) {
            return 2;
        }
        if (paragraph != null) {
            String style = paragraph.getStyle();
            if ("Heading1".equals(style)) {
                return 0;
            }
            if ("Heading2".equals(style)) {
                return 1;
            }
            if ("Heading3".equals(style)) {
                return 2;
            }
        }
        return -1;
    }

    private boolean isSpecialHeading(String text) {
        return "摘要".equals(text)
                || "Abstract".equalsIgnoreCase(text)
                || "参考文献".equals(text)
                || "致谢".equals(text)
                || text.startsWith("附录");
    }

    private XWPFParagraph findParagraphBySection(XWPFDocument document, String section) {
        if (document == null || section == null || section.isBlank()) {
            return null;
        }
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text != null && text.contains(section)) {
                return paragraph;
            }
        }
        return null;
    }

    private void replaceParagraphText(XWPFParagraph paragraph, String newText) {
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }
        paragraph.createRun().setText(newText);
    }

    private MultipartFile createMultipartFileFromPath(Path filePath) throws IOException {
        byte[] fileContent = Files.readAllBytes(filePath);
        String filename = filePath.getFileName().toString();

        return new MultipartFile() {
            @Override
            public String getName() {
                return "template";
            }

            @Override
            public String getOriginalFilename() {
                return filename;
            }

            @Override
            public String getContentType() {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }

            @Override
            public boolean isEmpty() {
                return fileContent.length == 0;
            }

            @Override
            public long getSize() {
                return fileContent.length;
            }

            @Override
            public byte[] getBytes() throws IOException {
                return fileContent;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(fileContent);
            }

            @Override
            public void transferTo(java.io.File dest) throws IOException {
                Files.write(dest.toPath(), fileContent);
            }
        };
    }

    private String saveUploadedTemplate(String taskId, MultipartFile file) throws IOException {
        Path templateDir = Paths.get(resolveWorkspaceBasePath(), taskId, "templates");
        Files.createDirectories(templateDir);
        Path templatePath = templateDir.resolve("user-template.docx");
        file.transferTo(templatePath.toFile());
        return templatePath.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeHtmlAndStripMarkdown(String text) {
        if (text == null) {
            return "";
        }
        String result = text
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("__([^_]+)__", "$1")
                .replaceAll("_([^_]+)_", "$1")
                .replaceAll("~~([^~]+)~~", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("^#{1,6}\\s+", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        return escapeHtml(result);
    }

    private String resolveProjectImageDataUri(String imagePath) {
        try {
            Path resolvedPath = resolveProjectAssetPath(imagePath);
            if (resolvedPath != null && Files.exists(resolvedPath)) {
                byte[] bytes = Files.readAllBytes(resolvedPath);
                String mimeType = Files.probeContentType(resolvedPath);
                if (mimeType == null || mimeType.isBlank()) {
                    mimeType = "image/png";
                }
                return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            }
        } catch (Exception e) {
            log.debug("读取项目图片失败: {}", imagePath, e);
        }
        return imagePath;
    }

    private Path resolveProjectAssetPath(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }

        Path direct = Paths.get(imagePath);
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().normalize();
        }

        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            Path current = Paths.get(userDir).toAbsolutePath().normalize();
            for (int i = 0; i < 4 && current != null; i++) {
                Path candidate = current.resolve(imagePath).normalize();
                if (Files.exists(candidate)) {
                    return candidate;
                }
                current = current.getParent();
            }
        }

        return direct.toAbsolutePath().normalize();
    }

    private Map<String, String> buildDiagramChapterMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("系统架构", "systemArchitecture");
        mapping.put("架构概述", "systemArchitecture");
        mapping.put("总体架构", "systemArchitecture");
        mapping.put("技术架构", "systemArchitecture");
        mapping.put("架构设计", "layerArchitecture");
        mapping.put("分层设计", "layerArchitecture");
        mapping.put("模块设计", "moduleStructure");
        mapping.put("模块划分", "moduleStructure");
        mapping.put("功能模块", "moduleStructure");
        mapping.put("类图", "classDiagram");
        mapping.put("详细设计", "classDiagram");
        mapping.put("时序图", "sequenceDiagram");
        mapping.put("业务流程", "businessFlow");
        mapping.put("流程设计", "businessFlow");
        mapping.put("数据库设计", "erDiagram");
        mapping.put("实体关系", "erDiagram");
        mapping.put("部署方案", "deploymentArchitecture");
        mapping.put("监控设计", "monitoringArchitecture");
        return mapping;
    }

    private Map<String, String> buildDiagramDisplayNames() {
        Map<String, String> displayNames = new LinkedHashMap<>();
        displayNames.put("systemArchitecture", "系统架构图");
        displayNames.put("layerArchitecture", "分层架构图");
        displayNames.put("moduleStructure", "模块结构图");
        displayNames.put("classDiagram", "核心类图");
        displayNames.put("sequenceDiagram", "系统交互时序图");
        displayNames.put("businessFlow", "业务流程图");
        displayNames.put("erDiagram", "数据库E-R图");
        displayNames.put("deploymentArchitecture", "部署架构图");
        displayNames.put("monitoringArchitecture", "监控架构图");
        return displayNames;
    }

    private String resolveWorkspaceBasePath() {
        List<Path> candidates = new ArrayList<>();
        if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
            candidates.add(Paths.get(workspaceBasePath));
        }

        String tmpDir = System.getProperty("java.io.tmpdir");
        if (tmpDir != null && !tmpDir.isBlank()) {
            candidates.add(Paths.get(tmpDir, "zwiki-workspace"));
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            candidates.add(Paths.get(userHome, ".zwiki", "zwiki-workspace"));
        }

        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            candidates.add(Paths.get(userDir, ".zwiki", "zwiki-workspace"));
        }

        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (isWritableDir(candidate)) {
                return candidate.toString();
            }
        }

        return Paths.get(".", "zwiki-workspace").toAbsolutePath().normalize().toString();
    }

    private boolean isWritableDir(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write_probe");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
