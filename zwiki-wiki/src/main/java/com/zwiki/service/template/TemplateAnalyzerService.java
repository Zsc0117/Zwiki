package com.zwiki.service.template;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板解析服务
 * 解析用户上传的毕业论文模板，识别需要填充的部分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateAnalyzerService {

    /**
     * 解析上传的论文模板
     *
     * @param templateFile 模板文件
     * @param taskId 任务ID
     * @return 模板结构分析结果
     */
    public Map<String, Object> analyzeTemplate(MultipartFile templateFile, String taskId) {
        log.info("开始解析论文模板, 任务ID: {}", taskId);

        Map<String, Object> templateStructure = new HashMap<>();

        try {
            XWPFDocument document = new XWPFDocument(templateFile.getInputStream());

            List<Map<String, Object>> sections = new ArrayList<>();
            List<String> placeholders = new ArrayList<>();
            List<String> imagePlaceholders = new ArrayList<>();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();

                if (isChapterTitle(paragraph)) {
                    Map<String, Object> section = new HashMap<>();
                    section.put("title", text);
                    section.put("level", getHeadingLevel(paragraph));
                    sections.add(section);
                }

                placeholders.addAll(extractPlaceholders(text));

                if (text.contains("{{图") || text.contains("{{image") || text.contains("【图")) {
                    imagePlaceholders.add(text);
                }
            }

            List<XWPFTable> tables = document.getTables();
            templateStructure.put("tableCount", tables.size());
            templateStructure.put("sections", sections);
            templateStructure.put("placeholders", placeholders);
            templateStructure.put("imagePlaceholders", imagePlaceholders);
            templateStructure.put("paragraphCount", document.getParagraphs().size());

            document.close();

            log.info("模板解析完成, 发现 {} 个章节, {} 个占位符", sections.size(), placeholders.size());

        } catch (IOException e) {
            log.error("解析模板失败", e);
            throw new RuntimeException("解析模板失败: " + e.getMessage(), e);
        }

        return templateStructure;
    }

    /**
     * 识别需要填充的内容类型
     */
    public Map<String, String> identifyRequiredContent(Map<String, Object> templateStructure) {
        Map<String, String> requiredContent = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<String> placeholders = (List<String>) templateStructure.get("placeholders");

        if (placeholders != null) {
            for (String placeholder : placeholders) {
                String contentType = inferContentType(placeholder);
                requiredContent.put(placeholder, contentType);
            }
        }

        return requiredContent;
    }

    /**
     * 生成默认通用模板（如果用户未提供）
     */
    public String generateDefaultTemplate(String taskId, String projectName, String workspaceBasePath) {
        log.info("生成默认论文模板, 项目: {}", projectName);

        try {
            XWPFDocument document = new XWPFDocument();

            XWPFParagraph title = document.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText(projectName + "系统设计与实现");
            titleRun.setBold(true);
            titleRun.setFontSize(22);

            addParagraph(document, "作者：{{author}}", ParagraphAlignment.CENTER);
            addParagraph(document, "专业：{{major}}", ParagraphAlignment.CENTER);
            addParagraph(document, "指导教师：{{advisor}}", ParagraphAlignment.CENTER);
            addParagraph(document, "日期：{{date}}", ParagraphAlignment.CENTER);
            addPageBreak(document);

            addHeading(document, "摘要", 1);
            addParagraph(document, "{{abstract}}", ParagraphAlignment.LEFT);
            addParagraph(document, "关键词：{{keywords}}", ParagraphAlignment.LEFT);
            addPageBreak(document);

            addHeading(document, "目录", 1);
            addParagraph(document, "{{toc}}", ParagraphAlignment.LEFT);
            addPageBreak(document);

            addHeading(document, "第一章 绪论", 1);
            addHeading(document, "1.1 项目背景", 2);
            addParagraph(document, "{{background}}", ParagraphAlignment.LEFT);
            addHeading(document, "1.2 项目意义", 2);
            addParagraph(document, "{{significance}}", ParagraphAlignment.LEFT);
            addHeading(document, "1.3 主要工作", 2);
            addParagraph(document, "{{mainWork}}", ParagraphAlignment.LEFT);

            addHeading(document, "第二章 需求分析", 1);
            addHeading(document, "2.1 功能需求", 2);
            addParagraph(document, "{{functionalRequirements}}", ParagraphAlignment.LEFT);
            addHeading(document, "2.2 非功能需求", 2);
            addParagraph(document, "{{nonFunctionalRequirements}}", ParagraphAlignment.LEFT);

            addHeading(document, "第三章 系统设计", 1);
            addHeading(document, "3.1 系统架构设计", 2);
            addParagraph(document, "{{systemArchitecture}}", ParagraphAlignment.LEFT);
            addParagraph(document, "{{图:架构图}}", ParagraphAlignment.CENTER);
            addHeading(document, "3.2 数据库设计", 2);
            addParagraph(document, "{{databaseDesign}}", ParagraphAlignment.LEFT);
            addHeading(document, "3.3 接口设计", 2);
            addParagraph(document, "{{apiDesign}}", ParagraphAlignment.LEFT);

            addHeading(document, "第四章 详细设计", 1);
            addHeading(document, "4.1 核心模块设计", 2);
            addParagraph(document, "{{coreModuleDesign}}", ParagraphAlignment.LEFT);
            addParagraph(document, "{{图:类图}}", ParagraphAlignment.CENTER);
            addHeading(document, "4.2 关键流程设计", 2);
            addParagraph(document, "{{keyFlowDesign}}", ParagraphAlignment.LEFT);
            addParagraph(document, "{{图:时序图}}", ParagraphAlignment.CENTER);

            addHeading(document, "第五章 实现与测试", 1);
            addHeading(document, "5.1 开发环境", 2);
            addParagraph(document, "{{developmentEnvironment}}", ParagraphAlignment.LEFT);
            addHeading(document, "5.2 关键技术实现", 2);
            addParagraph(document, "{{keyImplementation}}", ParagraphAlignment.LEFT);
            addHeading(document, "5.3 系统测试", 2);
            addParagraph(document, "{{systemTesting}}", ParagraphAlignment.LEFT);

            addHeading(document, "第六章 总结与展望", 1);
            addHeading(document, "6.1 工作总结", 2);
            addParagraph(document, "{{summary}}", ParagraphAlignment.LEFT);
            addHeading(document, "6.2 未来展望", 2);
            addParagraph(document, "{{futureWork}}", ParagraphAlignment.LEFT);

            addPageBreak(document);
            addHeading(document, "参考文献", 1);
            addParagraph(document, "{{references}}", ParagraphAlignment.LEFT);

            Path outputDir = Paths.get(workspaceBasePath, taskId);
            Files.createDirectories(outputDir);
            String templatePath = outputDir.resolve("default-template.docx").toString();
            try (FileOutputStream out = new FileOutputStream(templatePath)) {
                document.write(out);
            }

            document.close();

            log.info("默认模板生成完成: {}", templatePath);
            return templatePath;

        } catch (IOException e) {
            log.error("生成默认模板失败", e);
            throw new RuntimeException("生成默认模板失败: " + e.getMessage(), e);
        }
    }

    private boolean isChapterTitle(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        return style != null && (style.startsWith("Heading") || style.startsWith("标题"));
    }

    private int getHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return 0;
        }

        if (style.contains("1")) return 1;
        if (style.contains("2")) return 2;
        if (style.contains("3")) return 3;
        return 0;
    }

    private List<String> extractPlaceholders(String text) {
        List<String> placeholders = new ArrayList<>();
        int start = 0;
        while ((start = text.indexOf("{{", start)) != -1) {
            int end = text.indexOf("}}", start);
            if (end != -1) {
                placeholders.add(text.substring(start, end + 2));
                start = end + 2;
            } else {
                break;
            }
        }
        return placeholders;
    }

    private String inferContentType(String placeholder) {
        String lower = placeholder.toLowerCase();
        if (lower.contains("abstract") || lower.contains("摘要")) return "abstract";
        if (lower.contains("background") || lower.contains("背景")) return "background";
        if (lower.contains("requirement") || lower.contains("需求")) return "requirements";
        if (lower.contains("design") || lower.contains("设计")) return "design";
        if (lower.contains("test") || lower.contains("测试")) return "testing";
        return "general";
    }

    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph heading = document.createParagraph();
        heading.setStyle("Heading" + level);
        XWPFRun run = heading.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(level == 1 ? 16 : 14);
    }

    private void addParagraph(XWPFDocument document, String text, ParagraphAlignment alignment) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontSize(12);
    }

    private void addPageBreak(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setPageBreak(true);
    }
}
