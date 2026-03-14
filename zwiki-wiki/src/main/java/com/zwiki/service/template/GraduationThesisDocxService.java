package com.zwiki.service.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.repository.entity.ThesisVersionEntity;
import com.zwiki.repository.dao.ThesisVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDocument1;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabTlc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * 毕业论文 Word 文档生成服务
 * 严格按照江西农业大学本科毕业论文格式规范生成 Word 文档
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraduationThesisDocxService {

    private final ThesisVersionRepository thesisVersionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${zwiki.workspace.base-path:/tmp/zwiki-workspace}")
    private String workspaceBasePath;

    // 字体常量
    private static final String FONT_HEITI = "黑体";
    private static final String FONT_SONGTI = "宋体";
    private static final String FONT_TIMES = "Times New Roman";

    // 字号常量 (磅值)
    private static final int SIZE_2 = 22;      // 二号
    private static final int SIZE_XIAO2 = 18;  // 小二号
    private static final int SIZE_3 = 16;      // 三号
    private static final int SIZE_4 = 14;      // 四号
    private static final int SIZE_XIAO4 = 12;  // 小四号
    private static final int SIZE_5 = 10;      // 五号

    // 行间距 (20磅 = 400 twips, 1磅 = 20 twips)
    private static final int LINE_SPACING_20 = 400;
    private static final int LINE_SPACING_28 = 560;  // 28磅行间距

    // 原创性声明内容
    private static final String ORIGINALITY_STATEMENT =
            "本人郑重声明：所呈交的本科毕业论文是本人在导师的指导下独立进行研究" +
            "工作所取得的成果。尽我所知，除文中已经注明引用的内容外，本论文不包含任" +
            "何其他个人或集体已经发表或撰写过的研究成果。对论文所涉及的研究工作做出" +
            "贡献的其他个人和集体，均已在文中以明确方式标明或致谢。";

    // 学校校徽图标URL
    private static final String SCHOOL_LOGO_URL = "docs/assets/jxau2.png";
    // 学校名称图片URL
    private static final String SCHOOL_NAME_IMG_URL = "docs/assets/jxau1.png";

    private static final String DEFAULT_DOC_TYPE = "thesis";

    private String normalizeDocType(String docType) {
        if (docType == null || docType.isBlank()) {
            return DEFAULT_DOC_TYPE;
        }
        return docType.trim();
    }

    /**
     * 根据论文版本生成 Word 文档
     *
     * @param taskId 任务ID
     * @param version 版本号（可选，为null则使用当前版本）
     * @return Word 文档文件路径
     */
    public String generateDocx(String taskId, Integer version) {
        return generateDocx(taskId, DEFAULT_DOC_TYPE, version, null);
    }

    public String generateDocx(String taskId, String docType, Integer version) {
        return generateDocx(taskId, docType, version, null);
    }

    /**
     * 根据论文版本生成 Word 文档（带论文信息）
     *
     * @param taskId 任务ID
     * @param version 版本号（可选，为null则使用当前版本）
     * @param thesisInfo 论文信息（学院、姓名、学号等）
     * @return Word 文档文件路径
     */
    public String generateDocx(String taskId, Integer version, Map<String, String> thesisInfo) {
        return generateDocx(taskId, DEFAULT_DOC_TYPE, version, thesisInfo);
    }

    public String generateDocx(String taskId, String docType, Integer version, Map<String, String> thesisInfo) {
        docType = normalizeDocType(docType);
        log.info("开始生成 Word 文档, 任务ID: {}, docType: {}, 版本: {}", taskId, docType, version);

        try {
            ThesisVersionEntity thesisVersion;
            if (version != null) {
                thesisVersion = thesisVersionRepository.findByTaskIdAndDocTypeAndVersion(taskId, docType, version)
                        .orElseThrow(() -> new RuntimeException("指定版本不存在"));
            } else {
                thesisVersion = thesisVersionRepository.findByTaskIdAndDocTypeAndIsCurrentTrue(taskId, docType)
                        .orElseThrow(() -> new RuntimeException("当前版本不存在"));
            }

            String content = thesisVersion.getFullContent();
            if (content == null || content.isEmpty()) {
                throw new RuntimeException("论文内容为空");
            }

            XWPFDocument document = createFormattedDocument(DEFAULT_DOC_TYPE.equals(docType));
            if (DEFAULT_DOC_TYPE.equals(docType)) {
                Map<String, String> diagrams = new HashMap<>();
                if (thesisVersion.getInsertedDiagrams() != null) {
                    try {
                        diagrams = objectMapper.readValue(thesisVersion.getInsertedDiagrams(),
                                new TypeReference<Map<String, String>>() {});
                    } catch (Exception e) {
                        log.warn("解析图表信息失败", e);
                    }
                }

                if (thesisInfo == null || thesisInfo.isEmpty()) {
                    thesisInfo = tryLoadThesisInfoFromVersion(thesisVersion);
                }
                if (thesisInfo == null) {
                    thesisInfo = new HashMap<>();
                }

                String thesisTitle = thesisVersion.getThesisTitle();
                if (thesisTitle == null || thesisTitle.isBlank()) {
                    thesisTitle = thesisInfo.get("title");
                }
                if (thesisTitle == null || thesisTitle.isBlank()) {
                    thesisTitle = extractThesisTitleFromContent(content);
                }
                thesisInfo.putIfAbsent("title", thesisTitle);
                if (thesisInfo.get("title") == null || thesisInfo.get("title").isBlank()) {
                    thesisInfo.put("title", thesisTitle);
                }

                createCoverPage(document, thesisInfo);
                createOriginalityStatementPage(document);
                createTableOfContentsPage(document, content);
                parseAndFillContent(document, content, diagrams);
            } else if ("task_book".equals(docType)) {
                createTaskBookDocument(document, content, thesisInfo);
            } else if ("opening_report".equals(docType)) {
                createOpeningReportDocument(document, content);
            } else {
                throw new RuntimeException("不支持的文档类型: " + docType);
            }

            String docxPath = saveDocument(document, taskId, thesisVersion.getVersion(), docType);
            thesisVersion.setDocxFilePath(docxPath);
            thesisVersionRepository.save(thesisVersion);

            log.info("Word 文档生成完成: {}", docxPath);
            return docxPath;

        } catch (Exception e) {
            log.error("生成 Word 文档失败", e);
            throw new RuntimeException("生成 Word 文档失败: " + e.getMessage(), e);
        }
    }

    public byte[] getDocxBytes(String taskId, Integer version, Map<String, String> thesisInfo) throws IOException {
        String docxPath = generateDocx(taskId, DEFAULT_DOC_TYPE, version, thesisInfo);
        return Files.readAllBytes(Paths.get(docxPath));
    }

    public byte[] getDocxBytes(String taskId, String docType, Integer version, Map<String, String> thesisInfo) throws IOException {
        String docxPath = generateDocx(taskId, docType, version, thesisInfo);
        return Files.readAllBytes(Paths.get(docxPath));
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

    private void createTaskBookDocument(XWPFDocument document, String jsonContent, Map<String, String> thesisInfo) {
        Map<String, Object> data = parseJsonObject(jsonContent);
        String contentRequirements = String.valueOf(data.getOrDefault("contentRequirements", ""));
        String references = String.valueOf(data.getOrDefault("references", ""));
        String schedule = String.valueOf(data.getOrDefault("schedule", ""));

        String titleText = thesisInfo != null ? String.valueOf(thesisInfo.getOrDefault("title", "")) : "";
        String studentName = thesisInfo != null ? String.valueOf(thesisInfo.getOrDefault("studentName", "")) : "";
        String studentId = thesisInfo != null ? String.valueOf(thesisInfo.getOrDefault("studentId", "")) : "";
        String major = thesisInfo != null ? String.valueOf(thesisInfo.getOrDefault("major", "")) : "";
        String className = thesisInfo != null ? String.valueOf(thesisInfo.getOrDefault("className", "")) : "";
        String majorClass = (major + ((className == null || className.isBlank()) ? "" : (" " + className))).trim();

        XWPFParagraph title = document.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setText("江西农业大学本科毕业论文（设计）任务书");
        run.setFontFamily(FONT_HEITI);
        run.setFontSize(SIZE_3);
        run.setBold(true);

        addEmptyParagraph(document, 1);

        // Create a 12-column grid table
        XWPFTable table = document.createTable(6, 12);
        setTableWidth(table, 9000);
        setTaskBookColWidths(table);
        table.setCellMargins(50, 100, 50, 100);

        // Row 0: 课题名称 (Label: 2 units, Content: 10 units)
        XWPFTableRow r0 = table.getRow(0);
        r0.setHeight(800);
        mergeCellsHorizontally(table, 0, 0, 1);
        setCellText(r0.getCell(0), "课题名称", true, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 0, 2, 11);
        setCellText(r0.getCell(2), titleText, false, ParagraphAlignment.LEFT);

        // Row 1: 学生姓名 (2) | Val (2) | 学号 (1) | Val (2) | 专业、班级 (2) | Val (3)
        XWPFTableRow r1 = table.getRow(1);
        r1.setHeight(600);
        mergeCellsHorizontally(table, 1, 0, 1);
        setCellText(r1.getCell(0), "学生姓名", true, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 1, 2, 3);
        setCellText(r1.getCell(2), studentName, false, ParagraphAlignment.CENTER);
        
        setCellText(r1.getCell(4), "学号", true, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 1, 5, 6);
        setCellText(r1.getCell(5), studentId, false, ParagraphAlignment.CENTER);
        
        mergeCellsHorizontally(table, 1, 7, 8);
        setCellText(r1.getCell(7), "专业、班级", true, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 1, 9, 11);
        setCellText(r1.getCell(9), majorClass, false, ParagraphAlignment.CENTER);

        // Row 2: 内容要求 (Merge 12)
        XWPFTableRow r2 = table.getRow(2);
        r2.setHeight(3800);
        mergeCellsHorizontally(table, 2, 0, 11);
        setCellContentWithHeader(r2.getCell(0), "毕业论文（设计）内容要求：", contentRequirements);

        // Row 3: 参考资料 (Merge 12)
        XWPFTableRow r3 = table.getRow(3);
        r3.setHeight(3800);
        mergeCellsHorizontally(table, 3, 0, 11);
        setCellContentWithHeader(r3.getCell(0), "主要参考资料：", references);

        // Row 4: 进度安排 (Merge 12)
        XWPFTableRow r4 = table.getRow(4);
        r4.setHeight(3000);
        mergeCellsHorizontally(table, 4, 0, 11);
        setCellContentWithHeader(r4.getCell(0), "毕业论文（设计）进度安排：", schedule);

        // Row 5: 签名
        XWPFTableRow r5 = table.getRow(5);
        r5.setHeight(1000);
        mergeCellsHorizontally(table, 5, 0, 2);
        setCellText(r5.getCell(0), "指导教师签名", true, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 5, 3, 5);
        setCellText(r5.getCell(3), "", false, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 5, 6, 8);
        setCellText(r5.getCell(6), "学生签名", true, ParagraphAlignment.CENTER);
        mergeCellsHorizontally(table, 5, 9, 11);
        setCellText(r5.getCell(9), "", false, ParagraphAlignment.CENTER);
    }

    private void createOpeningReportDocument(XWPFDocument document, String jsonContent) {
        Map<String, Object> data = parseJsonObject(jsonContent);
        String purposeAndMeaning = String.valueOf(data.getOrDefault("purposeAndMeaning", ""));
        String planAndContent = String.valueOf(data.getOrDefault("planAndContent", ""));
        String methodsAndApproach = String.valueOf(data.getOrDefault("methodsAndApproach", ""));
        String implementationPlan = String.valueOf(data.getOrDefault("implementationPlan", ""));

        XWPFParagraph title = document.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setText("江西农业大学本科毕业论文（设计）开题报告");
        run.setFontFamily(FONT_HEITI);
        run.setFontSize(SIZE_3);
        run.setBold(true);

        addEmptyParagraph(document, 1);

        XWPFTable table = document.createTable(4, 1);
        setTableWidth(table, 9000);

        setCellContentWithHeader(table.getRow(0).getCell(0), "一、课题研究的目的和意义", purposeAndMeaning);
        setCellContentWithHeader(table.getRow(1).getCell(0), "二、课题研究方案和主要内容", planAndContent);
        setCellContentWithHeader(table.getRow(2).getCell(0), "三、课题研究方法及技术途径", methodsAndApproach);
        setCellContentWithHeader(table.getRow(3).getCell(0), "四、实施计划", implementationPlan);
    }

    private void setCellContentWithHeader(XWPFTableCell cell, String header, String content) {
        while (cell.getParagraphs().size() > 0) {
            cell.removeParagraph(0);
        }
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.TOP);
        
        XWPFParagraph pHeader = cell.addParagraph();
        pHeader.setAlignment(ParagraphAlignment.LEFT);
        setSpacingBefore(pHeader, 50);
        setSpacingAfter(pHeader, 50);
        setLineSpacing(pHeader, LINE_SPACING_20);
        XWPFRun rHeader = pHeader.createRun();
        rHeader.setText(header);
        rHeader.setFontFamily(FONT_SONGTI);
        rHeader.setFontSize(SIZE_XIAO4);
        rHeader.setBold(true);

        String cleanedContent = stripMarkdown(content);
        if (cleanedContent != null && !cleanedContent.isBlank()) {
            XWPFParagraph pContent = cell.addParagraph();
            pContent.setAlignment(ParagraphAlignment.LEFT);
            pContent.setFirstLineIndent(480);
            setSpacingBefore(pContent, 0);
            setSpacingAfter(pContent, 50);
            setLineSpacing(pContent, LINE_SPACING_20);
            XWPFRun rContent = pContent.createRun();
            rContent.setFontFamily(FONT_SONGTI);
            rContent.setFontSize(SIZE_XIAO4);
            
            String[] lines = cleanedContent.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    rContent.addBreak();
                }
                rContent.setText(lines[i]);
            }
        }
    }

    private String stripMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return stripInlineMarkdown(text);
    }

    private String stripInlineMarkdown(String text) {
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
        return result;
    }

    private void setTaskBookColWidths(XWPFTable table) {
        // 使用 12 列网格，每列宽度 750 (12 * 750 = 9000)
        int unitWidth = 750;
        
        // 设置 TblGrid 提高在 Word 中的兼容性
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid tblGrid = table.getCTTbl().getTblGrid();
        if (tblGrid == null) {
            tblGrid = table.getCTTbl().addNewTblGrid();
        } else {
            // 清理已有的 gridCol
            int size = tblGrid.sizeOfGridColArray();
            for (int i = size - 1; i >= 0; i--) {
                tblGrid.removeGridCol(i);
            }
        }
        for (int i = 0; i < 12; i++) {
            tblGrid.addNewGridCol().setW(BigInteger.valueOf(unitWidth));
        }

        for (XWPFTableRow row : table.getRows()) {
            for (int i = 0; i < row.getTableCells().size(); i++) {
                setCellWidth(row.getCell(i), unitWidth);
            }
        }
    }

    private void setCellWidth(XWPFTableCell cell, int width) {
        if (cell.getCTTc().getTcPr() == null) {
            cell.getCTTc().addNewTcPr();
        }
        CTTblWidth w = cell.getCTTc().getTcPr().getTcW();
        if (w == null) {
            w = cell.getCTTc().getTcPr().addNewTcW();
        }
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(width));
    }

    private void mergeCellsHorizontally(XWPFTable table, int row, int fromCol, int toCol) {
        XWPFTableRow tableRow = table.getRow(row);
        for (int colIndex = fromCol; colIndex <= toCol; colIndex++) {
            XWPFTableCell cell = tableRow.getCell(colIndex);
            if (cell.getCTTc().getTcPr() == null) {
                cell.getCTTc().addNewTcPr();
            }
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHMerge hmerge = cell.getCTTc().getTcPr().getHMerge();
            if (hmerge == null) {
                hmerge = cell.getCTTc().getTcPr().addNewHMerge();
            }
            if (colIndex == fromCol) {
                hmerge.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.RESTART);
            } else {
                hmerge.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE);
            }
        }
    }

    private void mergeCellsVertically(XWPFTable table, int col, int fromRow, int toRow) {
        for (int rowIndex = fromRow; rowIndex <= toRow; rowIndex++) {
            XWPFTableCell cell = table.getRow(rowIndex).getCell(col);
            if (cell.getCTTc().getTcPr() == null) {
                cell.getCTTc().addNewTcPr();
            }
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge vmerge = cell.getCTTc().getTcPr().getVMerge();
            if (vmerge == null) {
                vmerge = cell.getCTTc().getTcPr().addNewVMerge();
            }
            if (rowIndex == fromRow) {
                vmerge.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.RESTART);
            } else {
                vmerge.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE);
            }
        }
    }

    private void setCellText(XWPFTableCell cell, String text, boolean isLabel, ParagraphAlignment alignment) {
        while (cell.getParagraphs().size() > 0) {
            cell.removeParagraph(0);
        }
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(alignment);
        p.setFirstLineIndent(0);
        setSpacingBefore(p, 0);
        setSpacingAfter(p, 0);
        setLineSpacing(p, LINE_SPACING_20);
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT_SONGTI);
        r.setFontSize(SIZE_XIAO4);
        r.setBold(isLabel);
        String cleaned = stripMarkdown(text);
        String[] lines = (cleaned == null ? "" : cleaned).split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                r.addBreak();
            }
            r.setText(lines[i]);
        }
    }

    private void setTableWidth(XWPFTable table, int width) {
        if (table.getCTTbl().getTblPr() == null) {
            table.getCTTbl().addNewTblPr();
        }
        CTTblWidth tblWidth = table.getCTTbl().getTblPr().getTblW();
        if (tblWidth == null) {
            tblWidth = table.getCTTbl().getTblPr().addNewTblW();
        }
        tblWidth.setType(STTblWidth.DXA);
        tblWidth.setW(BigInteger.valueOf(width));

        // 设置为固定布局，确保列宽按设置的比例显示
        if (table.getCTTbl().getTblPr().getTblLayout() == null) {
            table.getCTTbl().getTblPr().addNewTblLayout();
        }
        table.getCTTbl().getTblPr().getTblLayout().setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType.FIXED);
    }

    private String saveDocument(XWPFDocument document, String taskId, Integer version, String docType) throws IOException {
        Path baseDir = resolveWritableWorkspaceBasePath();
        Path outputDir = baseDir.resolve(taskId).resolve("output");
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
        document.close();

        return outputPath.toString();
    }

    private void fillTwoColumnRow(XWPFTableRow row, String left, String right) {
        XWPFTableCell c0 = row.getCell(0);
        XWPFTableCell c1 = row.getCell(1);

        c0.removeParagraph(0);
        XWPFParagraph p0 = c0.addParagraph();
        p0.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r0 = p0.createRun();
        r0.setText(left);
        r0.setFontFamily(FONT_HEITI);
        r0.setFontSize(SIZE_4);
        r0.setBold(true);

        c1.removeParagraph(0);
        XWPFParagraph p1 = c1.addParagraph();
        p1.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun r1 = p1.createRun();
        for (String line : right.split("\\r?\\n")) {
            if (!line.isEmpty()) {
                r1.setText(line);
            }
            r1.addBreak();
        }
        r1.setFontFamily(FONT_SONGTI);
        r1.setFontSize(SIZE_4);
    }

    private Path resolveWritableWorkspaceBasePath() throws IOException {
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
            candidates.add(Paths.get(userHome, "zwiki-workspace"));
        }

        IOException lastException = null;
        for (Path candidate : candidates) {
            try {
                Files.createDirectories(candidate);

                Path probe = candidate.resolve(".zwiki_write_probe");
                Files.writeString(probe, "ok", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.deleteIfExists(probe);

                if (!candidate.toAbsolutePath().toString().equals(candidate.toString())) {
                    log.info("论文工作空间目录: {} (absolute: {})", candidate, candidate.toAbsolutePath());
                } else {
                    log.info("论文工作空间目录: {}", candidate);
                }
                return candidate;
            } catch (Exception e) {
                log.warn("论文工作空间目录不可用: {} (workspaceBasePath={})", candidate, workspaceBasePath, e);
                lastException = (e instanceof IOException ioException) ? ioException : new IOException(e);
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("No writable workspace directory available");
    }

    private String extractThesisTitleFromContent(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("# ")) {
                String candidate = line.substring(2).trim();
                if (!candidate.isBlank() && !"摘要".equalsIgnoreCase(candidate) && !"abstract".equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
        }
        return "毕业论文";
    }

    private Map<String, String> tryLoadThesisInfoFromVersion(ThesisVersionEntity thesisVersion) {
        if (thesisVersion == null || thesisVersion.getThesisInfo() == null || thesisVersion.getThesisInfo().isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(thesisVersion.getThesisInfo(), new TypeReference<Map<String, Object>>() {
            });
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                result.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return result;
        } catch (Exception e) {
            log.debug("解析thesisInfo失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void createCoverPage(XWPFDocument document, Map<String, String> thesisInfo) {
        addEmptyParagraph(document, 1);
        insertSchoolNameImage(document);

        XWPFParagraph titlePara2 = document.createParagraph();
        titlePara2.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun2 = titlePara2.createRun();
        titleRun2.setText("JIANGXI  AGRICULTURAL  UNIVERSITY");
        titleRun2.setFontFamily(FONT_TIMES);
        titleRun2.setFontSize(14);
        titleRun2.setBold(true);

        XWPFParagraph titlePara3 = document.createParagraph();
        titlePara3.setAlignment(ParagraphAlignment.CENTER);
        setSpacingBefore(titlePara3, 200);
        XWPFRun titleRun3 = titlePara3.createRun();
        titleRun3.setText("本 科 毕 业 论 文");
        titleRun3.setFontFamily("华文行楷");
        titleRun3.setFontSize(36);
        titleRun3.setBold(true);
        titleRun3.setColor("C55A11");

        addEmptyParagraph(document, 1);
        insertSchoolLogo(document);
        addEmptyParagraph(document, 2);

        String title = thesisInfo.getOrDefault("title", "");
        createCoverInfoLineWithUnderline(document, "题    目", title, true);
        createCoverInfoLineWithUnderline(document, "学    院", thesisInfo.getOrDefault("college", ""), false);
        createCoverInfoLineWithUnderline(document, "姓    名", thesisInfo.getOrDefault("studentName", ""), false);
        createCoverInfoLineWithUnderline(document, "学    号", thesisInfo.getOrDefault("studentId", ""), false);
        createCoverInfoLineWithUnderline(document, "专    业", thesisInfo.getOrDefault("major", ""), false);
        createCoverInfoLineWithUnderline(document, "班    级", thesisInfo.getOrDefault("className", ""), false);
        createCoverInfoLineWithUnderline(document, "指导教师", thesisInfo.getOrDefault("advisor", ""), false);
        createCoverInfoLineWithUnderline(document, "答辩日期", thesisInfo.getOrDefault("defenseDate", ""), false);

        addPageBreak(document);
    }

    private void createCoverInfoLineWithUnderline(XWPFDocument document, String label, String value, boolean isTitle) {
        XWPFParagraph para = document.createParagraph();
        para.setIndentationLeft(1200);
        setLineSpacing(para, LINE_SPACING_28);

        XWPFRun labelRun = para.createRun();
        labelRun.setText(label + "：");
        if (isTitle) {
            labelRun.setFontFamily(FONT_HEITI);
            labelRun.setFontSize(SIZE_3);
        } else {
            labelRun.setFontFamily(FONT_SONGTI);
            labelRun.setFontSize(SIZE_XIAO2);
        }
        labelRun.setBold(true);

        String displayValue = (value == null) ? "" : value.trim();
        displayValue = stripInlineMarkdown(displayValue);

        XWPFRun valueRun = para.createRun();
        valueRun.setText(displayValue);
        if (isTitle) {
            valueRun.setFontFamily(FONT_HEITI);
            valueRun.setFontSize(SIZE_3);
        } else {
            valueRun.setFontFamily(FONT_SONGTI);
            valueRun.setFontSize(SIZE_XIAO2);
        }
        valueRun.setBold(false);
        valueRun.setUnderline(UnderlinePatterns.SINGLE);

        int underlineLen;
        if (displayValue.isBlank()) {
            underlineLen = isTitle ? 10 : 8;
        } else {
            underlineLen = 0;
        }

        if (underlineLen > 0) {
            XWPFRun underlineRun = para.createRun();
            String underlineText = "\u00A0".repeat(underlineLen);
            underlineRun.setText(underlineText);
            underlineRun.setUnderline(UnderlinePatterns.SINGLE);
            if (isTitle) {
                underlineRun.setFontFamily(FONT_HEITI);
                underlineRun.setFontSize(SIZE_3);
            } else {
                underlineRun.setFontFamily(FONT_SONGTI);
                underlineRun.setFontSize(SIZE_XIAO2);
            }
        }
    }

    private void createOriginalityStatementPage(XWPFDocument document) {
        addEmptyParagraph(document, 2);

        XWPFParagraph titlePara1 = document.createParagraph();
        titlePara1.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun1 = titlePara1.createRun();
        titleRun1.setText("江西农业大学");
        titleRun1.setFontFamily(FONT_HEITI);
        titleRun1.setFontSize(SIZE_3);
        titleRun1.setBold(true);

        XWPFParagraph titlePara2 = document.createParagraph();
        titlePara2.setAlignment(ParagraphAlignment.CENTER);
        setSpacingBefore(titlePara2, 100);
        XWPFRun titleRun2 = titlePara2.createRun();
        titleRun2.setText("本科毕业论文原创性声明");
        titleRun2.setFontFamily(FONT_HEITI);
        titleRun2.setFontSize(SIZE_3);
        titleRun2.setBold(true);

        addEmptyParagraph(document, 1);

        XWPFParagraph contentPara = document.createParagraph();
        contentPara.setAlignment(ParagraphAlignment.BOTH);
        contentPara.setFirstLineIndent(480);
        setLineSpacing(contentPara, LINE_SPACING_28);

        XWPFRun contentRun = contentPara.createRun();
        contentRun.setText(ORIGINALITY_STATEMENT);
        contentRun.setFontFamily(FONT_SONGTI);
        contentRun.setFontSize(SIZE_4);

        addEmptyParagraph(document, 4);

        XWPFParagraph signPara1 = document.createParagraph();
        signPara1.setAlignment(ParagraphAlignment.RIGHT);
        signPara1.setIndentationRight(1200);
        setLineSpacing(signPara1, LINE_SPACING_28);
        XWPFRun signRun1 = signPara1.createRun();
        signRun1.setText("作者签名：");
        signRun1.setFontFamily(FONT_SONGTI);
        signRun1.setFontSize(SIZE_4);

        addEmptyParagraph(document, 1);

        XWPFParagraph signPara2 = document.createParagraph();
        signPara2.setAlignment(ParagraphAlignment.RIGHT);
        signPara2.setIndentationRight(1200);
        setLineSpacing(signPara2, LINE_SPACING_28);
        XWPFRun signRun2 = signPara2.createRun();
        signRun2.setText("日    期：");
        signRun2.setFontFamily(FONT_SONGTI);
        signRun2.setFontSize(SIZE_4);

        addPageBreak(document);
    }

    private void createTableOfContentsPage(XWPFDocument document, String content) {
        XWPFParagraph tocTitle = document.createParagraph();
        tocTitle.setAlignment(ParagraphAlignment.CENTER);
        setSpacingBefore(tocTitle, 200);
        XWPFRun tocTitleRun = tocTitle.createRun();
        tocTitleRun.setText("目    录");
        tocTitleRun.setFontFamily(FONT_HEITI);
        tocTitleRun.setFontSize(SIZE_3);
        tocTitleRun.setBold(true);

        addEmptyParagraph(document, 1);

        String[] lines = content.split("\n");
        boolean skipBasicInfo = true;
        int pageNumber = 1;

        for (String line : lines) {
            if (skipBasicInfo) {
                if (line.contains("摘要") || line.contains("Abstract") || line.contains("摘 要")) {
                    skipBasicInfo = false;
                } else {
                    continue;
                }
            }

            if (line.startsWith("# ")) {
                String title = line.substring(2).trim();
                createTocEntryWithDots(document, title, 0, pageNumber++);
            } else if (line.startsWith("## ")) {
                String title = line.substring(3).trim();
                createTocEntryWithDots(document, title, 1, pageNumber++);
            } else if (line.startsWith("### ")) {
                String title = line.substring(4).trim();
                createTocEntryWithDots(document, title, 2, pageNumber);
            }
        }

        addPageBreak(document);
    }

    private void createTocEntryWithDots(XWPFDocument document, String title, int level, int pageNumber) {
        XWPFParagraph para = document.createParagraph();

        CTTabStop tabStop = para.getCTP().getPPr() != null
                ? para.getCTP().getPPr().addNewTabs().addNewTab()
                : para.getCTP().addNewPPr().addNewTabs().addNewTab();
        tabStop.setVal(STTabJc.RIGHT);
        tabStop.setPos(BigInteger.valueOf(8500));
        tabStop.setLeader(STTabTlc.DOT);

        para.setIndentationLeft(level * 420);
        setLineSpacing(para, LINE_SPACING_20);

        XWPFRun titleRun = para.createRun();
        titleRun.setText(title);
        titleRun.setFontFamily(FONT_SONGTI);
        titleRun.setFontSize(SIZE_XIAO4);

        XWPFRun tabRun = para.createRun();
        tabRun.addTab();

        XWPFRun pageRun = para.createRun();
        pageRun.setText(String.valueOf(pageNumber));
        pageRun.setFontFamily(FONT_TIMES);
        pageRun.setFontSize(SIZE_XIAO4);
    }

    private void addEmptyParagraph(XWPFDocument document, int count) {
        for (int i = 0; i < count; i++) {
            XWPFParagraph para = document.createParagraph();
            XWPFRun run = para.createRun();
            run.setText("");
        }
    }

    private void addPageBreak(XWPFDocument document) {
        XWPFParagraph para = document.createParagraph();
        XWPFRun run = para.createRun();
        run.addBreak(BreakType.PAGE);
    }

    private void insertSchoolNameImage(XWPFDocument document) {
        try {
            byte[] imageData = tryMakePngWhiteTransparent(readProjectAssetBytes(SCHOOL_NAME_IMG_URL));

            XWPFParagraph namePara = document.createParagraph();
            namePara.setAlignment(ParagraphAlignment.CENTER);

            XWPFRun nameRun = namePara.createRun();

            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
                nameRun.addPicture(bais, XWPFDocument.PICTURE_TYPE_PNG, "school_name.png",
                        Units.toEMU(300), Units.toEMU(60));
            }

            log.info("成功插入学校名称图片");
        } catch (Exception e) {
            log.warn("插入学校名称图片失败，使用文字代替: {}", e.getMessage());
            XWPFParagraph titlePara = document.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("江西农业大学");
            titleRun.setFontFamily(FONT_HEITI);
            titleRun.setFontSize(42);
            titleRun.setBold(true);
        }
    }

    private byte[] tryMakePngWhiteTransparent(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return pngBytes;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(pngBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            BufferedImage src = ImageIO.read(bais);
            if (src == null) {
                return pngBytes;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = src.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int a = (rgb >> 24) & 0xFF;
                    if (r >= 245 && g >= 245 && b >= 245) {
                        a = 0;
                    }
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    out.setRGB(x, y, argb);
                }
            }

            boolean ok = ImageIO.write(out, "png", baos);
            if (!ok) {
                return pngBytes;
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return pngBytes;
        }
    }

    private byte[] readProjectAssetBytes(String imagePath) throws IOException {
        Path resolvedPath = resolveProjectAssetPath(imagePath);
        if (resolvedPath == null || !Files.exists(resolvedPath)) {
            throw new IOException("项目图片不存在: " + imagePath);
        }
        return Files.readAllBytes(resolvedPath);
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

    private void insertSchoolLogo(XWPFDocument document) {
        try {
            byte[] imageData = readProjectAssetBytes(SCHOOL_LOGO_URL);

            XWPFParagraph logoPara = document.createParagraph();
            logoPara.setAlignment(ParagraphAlignment.CENTER);

            XWPFRun logoRun = logoPara.createRun();

            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageData)) {
                logoRun.addPicture(bais, XWPFDocument.PICTURE_TYPE_PNG, "school_logo.png",
                        Units.toEMU(120), Units.toEMU(120));
            }

            log.info("成功插入学校校徽图标");
        } catch (Exception e) {
            log.warn("插入学校校徽失败，跳过: {}", e.getMessage());
            addEmptyParagraph(document, 2);
        }
    }

    private XWPFDocument createFormattedDocument() {
        return createFormattedDocument(true);
    }

    private XWPFDocument createFormattedDocument(boolean withHeader) {
        XWPFDocument document = new XWPFDocument();

        CTDocument1 ctDocument = document.getDocument();
        CTBody ctBody = ctDocument.getBody();
        if (ctBody.getSectPr() == null) {
            ctBody.addNewSectPr();
        }
        CTSectPr sectPr = ctBody.getSectPr();

        CTPageSz pageSize = sectPr.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));

        CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setTop(BigInteger.valueOf(1417));
        pageMar.setBottom(BigInteger.valueOf(1417));
        pageMar.setLeft(BigInteger.valueOf(1587));
        pageMar.setRight(BigInteger.valueOf(1417));
        pageMar.setHeader(BigInteger.valueOf(851));
        pageMar.setFooter(BigInteger.valueOf(851));

        if (withHeader) {
            createDocumentHeader(document);
        }
        return document;
    }

    private void createDocumentHeader(XWPFDocument document) {
        try {
            XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();
            XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
            XWPFParagraph headerPara = header.createParagraph();
            headerPara.setAlignment(ParagraphAlignment.CENTER);

            XWPFRun headerRun = headerPara.createRun();
            headerRun.setText("江西农业大学本科论文");
            headerRun.setFontFamily(FONT_SONGTI);
            headerRun.setFontSize(SIZE_5);
        } catch (Exception e) {
            log.warn("创建页眉失败: {}", e.getMessage());
        }
    }

    private static final Map<String, String> DIAGRAM_CHAPTER_MAPPING = new LinkedHashMap<>();
    private static final Map<String, String> DIAGRAM_DISPLAY_NAMES = new LinkedHashMap<>();

    private static final List<String> DIAGRAM_INSERT_ORDER = Arrays.asList(
            "systemArchitecture", "layerArchitecture", "functionStructure", "moduleStructure",
            "useCaseDiagram", "activityDiagram", "businessFlow", "classDiagram",
            "sequenceDiagram", "stateDiagram", "dataFlowDiagram",
            "erDiagram", "componentDiagram", "deploymentDiagram"
    );

    static {
        DIAGRAM_CHAPTER_MAPPING.put("系统架构", "systemArchitecture");
        DIAGRAM_CHAPTER_MAPPING.put("架构概述", "systemArchitecture");
        DIAGRAM_CHAPTER_MAPPING.put("总体架构", "systemArchitecture");
        DIAGRAM_CHAPTER_MAPPING.put("技术架构", "systemArchitecture");

        DIAGRAM_CHAPTER_MAPPING.put("架构设计", "layerArchitecture");
        DIAGRAM_CHAPTER_MAPPING.put("分层设计", "layerArchitecture");
        DIAGRAM_CHAPTER_MAPPING.put("系统设计", "layerArchitecture");
        DIAGRAM_CHAPTER_MAPPING.put("总体设计", "layerArchitecture");

        DIAGRAM_CHAPTER_MAPPING.put("模块划分", "moduleStructure");
        DIAGRAM_CHAPTER_MAPPING.put("模块结构", "moduleStructure");
        DIAGRAM_CHAPTER_MAPPING.put("模块设计", "moduleStructure");
        DIAGRAM_CHAPTER_MAPPING.put("功能模块", "moduleStructure");
        DIAGRAM_CHAPTER_MAPPING.put("系统功能", "moduleStructure");

        DIAGRAM_CHAPTER_MAPPING.put("类图", "classDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("类设计", "classDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("详细设计", "classDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("代码结构", "classDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("用例设计", "useCaseDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("用例图", "useCaseDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("用例分析", "useCaseDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("需求分析", "useCaseDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("功能需求", "useCaseDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("时序图", "sequenceDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("交互设计", "sequenceDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("业务流程", "sequenceDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("流程设计", "sequenceDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("数据库设计", "erDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("E-R图", "erDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("数据模型", "erDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("数据表设计", "erDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("实体关系", "erDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("组件", "componentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("组件设计", "componentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("组件图", "componentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("接口设计", "componentDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("部署", "deploymentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("部署设计", "deploymentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("部署架构", "deploymentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("运维", "deploymentDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("环境部署", "deploymentDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("活动图", "activityDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("系统流程", "activityDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("处理流程", "activityDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("操作流程", "activityDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("状态设计", "stateDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("状态图", "stateDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("状态转换", "stateDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("状态流转", "stateDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("生命周期", "stateDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("数据流", "dataFlowDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("数据流程", "dataFlowDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("数据处理", "dataFlowDiagram");
        DIAGRAM_CHAPTER_MAPPING.put("信息流", "dataFlowDiagram");

        DIAGRAM_CHAPTER_MAPPING.put("功能结构", "functionStructure");
        DIAGRAM_CHAPTER_MAPPING.put("功能设计", "functionStructure");
        DIAGRAM_CHAPTER_MAPPING.put("功能划分", "functionStructure");
        DIAGRAM_CHAPTER_MAPPING.put("功能概述", "functionStructure");

        DIAGRAM_CHAPTER_MAPPING.put("业务设计", "businessFlow");
        DIAGRAM_CHAPTER_MAPPING.put("业务分析", "businessFlow");
        DIAGRAM_CHAPTER_MAPPING.put("核心业务", "businessFlow");

        DIAGRAM_DISPLAY_NAMES.put("systemArchitecture", "系统架构图");
        DIAGRAM_DISPLAY_NAMES.put("layerArchitecture", "分层架构图");
        DIAGRAM_DISPLAY_NAMES.put("moduleStructure", "模块结构图");
        DIAGRAM_DISPLAY_NAMES.put("classDiagram", "核心类图");
        DIAGRAM_DISPLAY_NAMES.put("useCaseDiagram", "系统用例图");
        DIAGRAM_DISPLAY_NAMES.put("sequenceDiagram", "系统交互时序图");
        DIAGRAM_DISPLAY_NAMES.put("erDiagram", "数据库E-R图");
        DIAGRAM_DISPLAY_NAMES.put("componentDiagram", "系统组件图");
        DIAGRAM_DISPLAY_NAMES.put("deploymentDiagram", "系统部署架构图");
        DIAGRAM_DISPLAY_NAMES.put("activityDiagram", "系统活动图");
        DIAGRAM_DISPLAY_NAMES.put("stateDiagram", "状态转换图");
        DIAGRAM_DISPLAY_NAMES.put("dataFlowDiagram", "数据流图");
        DIAGRAM_DISPLAY_NAMES.put("functionStructure", "功能结构图");
        DIAGRAM_DISPLAY_NAMES.put("businessFlow", "业务流程图");
    }

    private void parseAndFillContent(XWPFDocument document, String content, Map<String, String> diagrams) {
        String[] lines = content.split("\n");
        boolean inCodeBlock = false;
        boolean inTable = false;
        boolean inAsciiTable = false;
        boolean skipBasicInfo = true;
        boolean hasEmbeddedImages = content != null && content.contains("![");
        Pattern markdownImagePattern = Pattern.compile("^!\\[([^\\]]*)\\]\\(([^\\)]+)\\)\\s*$");
        Pattern figureCaptionPattern = Pattern.compile("^图\\d+\\s+.*$");
        StringBuilder tableContent = new StringBuilder();
        StringBuilder asciiTableContent = new StringBuilder();
        Set<String> insertedDiagrams = new HashSet<>();
        int figureNumber = 1;
        boolean justInsertedImage = false;

        for (String line : lines) {
            if (skipBasicInfo) {
                if (line.contains("摘要") || line.contains("Abstract") || line.contains("摘 要") || line.startsWith("# ")) {
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
                    insertImage(document, diagramPath, figureNumber, caption != null && !caption.isBlank() ? caption : "图表");
                    String diagramKey = findDiagramKeyByPath(diagrams, diagramPath);
                    if (diagramKey != null) {
                        insertedDiagrams.add(diagramKey);
                    }
                    figureNumber++;
                    justInsertedImage = true;
                }
                continue;
            }

            if (justInsertedImage && figureCaptionPattern.matcher(line.trim()).matches()) {
                justInsertedImage = false;
                continue;
            }
            justInsertedImage = false;

            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) {
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
                createAsciiThreeLineTable(document, asciiTableContent.toString());
                inAsciiTable = false;
            }

            if (line.startsWith("|")) {
                if (!inTable) {
                    inTable = true;
                    tableContent = new StringBuilder();
                }
                tableContent.append(line).append("\n");
                continue;
            } else if (inTable) {
                createTable(document, tableContent.toString());
                inTable = false;
            }

            if (line.startsWith("# ")) {
                String title = stripInlineMarkdown(line.substring(2).trim());
                createHeading1(document, title);
                if (!hasEmbeddedImages) {
                    figureNumber = insertDiagramAfterTitle(document, title, diagrams, insertedDiagrams, figureNumber);
                }
            } else if (line.startsWith("## ")) {
                String title = stripInlineMarkdown(line.substring(3).trim());
                createHeading2(document, title);
                if (!hasEmbeddedImages) {
                    figureNumber = insertDiagramAfterTitle(document, title, diagrams, insertedDiagrams, figureNumber);
                }
            } else if (line.startsWith("### ")) {
                String title = stripInlineMarkdown(line.substring(4).trim());
                createHeading3(document, title);
                if (!hasEmbeddedImages) {
                    figureNumber = insertDiagramAfterTitle(document, title, diagrams, insertedDiagrams, figureNumber);
                }
            } else if (line.startsWith("#### ")) {
                createHeading4(document, stripInlineMarkdown(line.substring(5).trim()));
            } else if (!line.trim().isEmpty()) {
                createParagraph(document, stripInlineMarkdown(line.trim()));
            }
        }

        if (inTable) {
            createTable(document, tableContent.toString());
        }

        if (inAsciiTable) {
            createAsciiThreeLineTable(document, asciiTableContent.toString());
        }

        insertRemainingDiagrams(document, diagrams, insertedDiagrams, figureNumber);
    }

    private int insertRemainingDiagrams(XWPFDocument document, Map<String, String> diagrams,
                                        Set<String> inserted, int figureNumber) {
        List<String> remainingDiagrams = new ArrayList<>();
        for (String diagramKey : DIAGRAM_INSERT_ORDER) {
            if (diagrams.containsKey(diagramKey) && !inserted.contains(diagramKey)) {
                remainingDiagrams.add(diagramKey);
            }
        }

        if (remainingDiagrams.isEmpty()) {
            log.info("所有图表都已插入到对应章节");
            return figureNumber;
        }

        log.info("尝试智能插入 {} 个未匹配的图表到相关章节", remainingDiagrams.size());

        for (String diagramKey : remainingDiagrams) {
            String diagramPath = diagrams.get(diagramKey);
            String displayName = DIAGRAM_DISPLAY_NAMES.getOrDefault(diagramKey, "图表");
            insertImage(document, diagramPath, figureNumber, displayName);
            inserted.add(diagramKey);
            figureNumber++;
            log.info("图表 {} 已插入文档", displayName);
        }

        return figureNumber;
    }

    private String findDiagramKeyByPath(Map<String, String> diagrams, String diagramPath) {
        if (diagrams == null || diagrams.isEmpty() || diagramPath == null || diagramPath.isBlank()) {
            return null;
        }
        String normalizedTarget = normalizeDiagramPath(diagramPath);
        String targetFileName = extractFileName(normalizedTarget);
        for (Map.Entry<String, String> entry : diagrams.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String normalizedValue = normalizeDiagramPath(entry.getValue());
            if (normalizedValue.equalsIgnoreCase(normalizedTarget)) {
                return entry.getKey();
            }
            String valueFileName = extractFileName(normalizedValue);
            if (!targetFileName.isBlank() && targetFileName.equalsIgnoreCase(valueFileName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String normalizeDiagramPath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim().replace("\\\\", "/");
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int h = p.indexOf('#');
        if (h >= 0) {
            p = p.substring(0, h);
        }
        return p;
    }

    private String extractFileName(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return "";
        }
        String p = normalizedPath;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private int insertDiagramAfterTitle(XWPFDocument document, String title,
                                        Map<String, String> diagrams, Set<String> inserted, int figureNumber) {
        for (Map.Entry<String, String> entry : DIAGRAM_CHAPTER_MAPPING.entrySet()) {
            String keyword = entry.getKey();
            String diagramKey = entry.getValue();

            if (title.contains(keyword) && !inserted.contains(diagramKey) && diagrams.containsKey(diagramKey)) {
                String diagramPath = diagrams.get(diagramKey);
                String displayName = DIAGRAM_DISPLAY_NAMES.getOrDefault(diagramKey, "图表");
                insertImage(document, diagramPath, figureNumber, displayName);
                inserted.add(diagramKey);
                figureNumber++;
                break;
            }
        }

        return figureNumber;
    }

    private void insertImage(XWPFDocument document, String imagePath, int figureNumber, String caption) {
        try {
            InputStream imageInputStream;
            String fileName;

            if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                URL url = new URL(imagePath);
                imageInputStream = url.openStream();
                fileName = imagePath.substring(imagePath.lastIndexOf('/') + 1);
                log.info("从URL下载图片: {}", imagePath);
            } else {
                File imageFile = new File(imagePath);
                if (!imageFile.exists()) {
                    log.warn("图片文件不存在: {}", imagePath);
                    return;
                }
                imageInputStream = new FileInputStream(imageFile);
                fileName = imageFile.getName();
            }

            XWPFParagraph imagePara = document.createParagraph();
            imagePara.setAlignment(ParagraphAlignment.CENTER);

            XWPFRun imageRun = imagePara.createRun();
            try (InputStream is = imageInputStream) {
                int pictureType = getPictureType(imagePath);
                imageRun.addPicture(is, pictureType, fileName,
                        Units.toEMU(400), Units.toEMU(280));
            }

            XWPFParagraph captionPara = document.createParagraph();
            captionPara.setAlignment(ParagraphAlignment.CENTER);
            setLineSpacing(captionPara, LINE_SPACING_20);

            XWPFRun captionRun = captionPara.createRun();
            captionRun.setText("图" + figureNumber + " " + caption);
            captionRun.setFontFamily(FONT_SONGTI);
            captionRun.setFontSize(SIZE_5);

            document.createParagraph();
            log.info("成功插入图片: {} (图{})", caption, figureNumber);

        } catch (Exception e) {
            log.error("插入图片失败: {}", imagePath, e);
        }
    }

    private int getPictureType(String imagePath) {
        String lower = imagePath.toLowerCase();
        if (lower.endsWith(".png")) {
            return XWPFDocument.PICTURE_TYPE_PNG;
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return XWPFDocument.PICTURE_TYPE_JPEG;
        } else if (lower.endsWith(".gif")) {
            return XWPFDocument.PICTURE_TYPE_GIF;
        } else if (lower.endsWith(".bmp")) {
            return XWPFDocument.PICTURE_TYPE_BMP;
        }
        return XWPFDocument.PICTURE_TYPE_PNG;
    }

    private void createHeading1(XWPFDocument document, String text) {
        text = stripInlineMarkdown(text);
        boolean needsPageBreak = text.contains("绪论") || text.contains("第1章") ||
                text.equals("致谢") || text.equals("参考文献");

        if (needsPageBreak) {
            addPageBreak(document);
        }

        XWPFParagraph paragraph = document.createParagraph();
        boolean isCenter = text.equals("摘要") || text.equals("Abstract") ||
                text.equals("目录") || text.equals("致谢") ||
                text.equals("参考文献") || text.equals("附录");

        paragraph.setAlignment(isCenter ? ParagraphAlignment.CENTER : ParagraphAlignment.LEFT);
        setLineSpacing(paragraph, LINE_SPACING_28);
        setSpacingBefore(paragraph, 312);
        setSpacingAfter(paragraph, 312);

        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily(FONT_HEITI);
        run.setFontSize(SIZE_4);
        run.setBold(true);
    }

    private void createHeading2(XWPFDocument document, String text) {
        text = stripInlineMarkdown(text);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        setLineSpacing(paragraph, LINE_SPACING_20);
        setSpacingBefore(paragraph, 240);
        setSpacingAfter(paragraph, 120);

        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily(FONT_HEITI);
        run.setFontSize(SIZE_XIAO4);
        run.setBold(true);
    }

    private void createHeading3(XWPFDocument document, String text) {
        text = stripInlineMarkdown(text);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        setLineSpacing(paragraph, LINE_SPACING_20);
        setSpacingBefore(paragraph, 156);
        setSpacingAfter(paragraph, 78);

        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily(FONT_SONGTI);
        run.setFontSize(SIZE_XIAO4);
        run.setBold(true);
    }

    private void createHeading4(XWPFDocument document, String text) {
        text = stripInlineMarkdown(text);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        setLineSpacing(paragraph, LINE_SPACING_20);

        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily(FONT_SONGTI);
        run.setFontSize(SIZE_XIAO4);
    }

    private void createParagraph(XWPFDocument document, String text) {
        text = stripInlineMarkdown(text);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.BOTH);
        paragraph.setFirstLineIndent(480);
        setLineSpacing(paragraph, LINE_SPACING_20);

        if (text.startsWith("关键词：") || text.startsWith("关键词:")) {
            createKeywordsParagraph(paragraph, text);
        } else if (text.startsWith("Key words:") || text.startsWith("Keywords:")) {
            createKeywordsEnParagraph(paragraph, text);
        } else {
            XWPFRun run = paragraph.createRun();
            writeMultilineText(run, text);
            run.setFontFamily(FONT_SONGTI);
            run.setFontSize(SIZE_XIAO4);
        }
    }

    private void writeMultilineText(XWPFRun run, String text) {
        if (run == null) {
            return;
        }
        if (text == null) {
            run.setText("");
            return;
        }
        String[] parts = text.split("\\r?\\n", -1);
        if (parts.length == 0) {
            run.setText("");
            return;
        }
        run.setText(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            run.addBreak();
            run.setText(parts[i]);
        }
    }

    private void createKeywordsParagraph(XWPFParagraph paragraph, String text) {
        XWPFRun labelRun = paragraph.createRun();
        labelRun.setText("关键词：");
        labelRun.setFontFamily(FONT_HEITI);
        labelRun.setFontSize(SIZE_4);
        labelRun.setBold(true);

        String keywords = stripInlineMarkdown(text.replaceFirst("关键词[：:]", "").trim());
        XWPFRun contentRun = paragraph.createRun();
        contentRun.setText(keywords);
        contentRun.setFontFamily(FONT_SONGTI);
        contentRun.setFontSize(SIZE_XIAO4);
    }

    private void createKeywordsEnParagraph(XWPFParagraph paragraph, String text) {
        XWPFRun labelRun = paragraph.createRun();
        labelRun.setText("Key words: ");
        labelRun.setFontFamily(FONT_TIMES);
        labelRun.setFontSize(SIZE_4);
        labelRun.setBold(true);

        String keywords = stripInlineMarkdown(text.replaceFirst("Key ?words[：:]?", "").trim());
        XWPFRun contentRun = paragraph.createRun();
        contentRun.setText(keywords);
        contentRun.setFontFamily(FONT_TIMES);
        contentRun.setFontSize(SIZE_XIAO4);
    }

    private void createTable(XWPFDocument document, String tableContent) {
        String[] rows = tableContent.split("\n");
        if (rows.length < 2) {
            return;
        }

        String[] firstRowCells = rows[0].split("\\|");
        int colCount = 0;
        for (String cell : firstRowCells) {
            if (!cell.trim().isEmpty()) {
                colCount++;
            }
        }
        if (colCount == 0) {
            return;
        }

        XWPFTable table = document.createTable();
        CTTblWidth tableWidth = table.getCTTbl().addNewTblPr().addNewTblW();
        tableWidth.setType(STTblWidth.PCT);
        tableWidth.setW(BigInteger.valueOf(5000));

        applyThreeLineTableStyle(table);

        boolean isFirstDataRow = true;
        for (String row : rows) {
            if (row.contains("---") || row.contains("---")) {
                continue;
            }

            String[] cells = row.split("\\|");
            XWPFTableRow tableRow;

            if (isFirstDataRow && table.getRows().size() > 0) {
                tableRow = table.getRow(0);
                isFirstDataRow = false;
            } else if (isFirstDataRow) {
                tableRow = table.getRow(0);
                isFirstDataRow = false;
            } else {
                tableRow = table.createRow();
            }

            int cellIndex = 0;
            for (String cell : cells) {
                String cellText = cell.trim();
                if (cellText.isEmpty()) {
                    continue;
                }

                XWPFTableCell tableCell;
                if (cellIndex < tableRow.getTableCells().size()) {
                    tableCell = tableRow.getCell(cellIndex);
                } else {
                    tableCell = tableRow.addNewTableCell();
                }

                tableCell.removeParagraph(0);
                XWPFParagraph cellPara = tableCell.addParagraph();
                cellPara.setAlignment(ParagraphAlignment.CENTER);

                XWPFRun cellRun = cellPara.createRun();
                cellRun.setText(stripInlineMarkdown(cellText).replace("\n", " "));
                cellRun.setFontFamily(FONT_SONGTI);
                cellRun.setFontSize(SIZE_5);

                cellIndex++;
            }
        }

        document.createParagraph();
    }

    private boolean isAsciiTableLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("┌") || t.startsWith("│") || t.startsWith("├") || t.startsWith("└");
    }

    private void createAsciiThreeLineTable(XWPFDocument document, String asciiTable) {
        if (asciiTable == null || asciiTable.isBlank()) {
            return;
        }
        List<List<String>> rows = parseAsciiBoxTable(asciiTable);
        if (rows.isEmpty()) {
            return;
        }

        int colCount = 0;
        for (List<String> row : rows) {
            colCount = Math.max(colCount, row.size());
        }
        if (colCount <= 0) {
            return;
        }

        XWPFTable table = document.createTable(rows.size(), colCount);
        CTTblWidth tableWidth = table.getCTTbl().addNewTblPr().addNewTblW();
        tableWidth.setType(STTblWidth.PCT);
        tableWidth.setW(BigInteger.valueOf(5000));
        applyThreeLineTableStyle(table);

        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            XWPFTableRow tableRow = table.getRow(r);
            for (int c = 0; c < colCount; c++) {
                String cellText = c < row.size() ? row.get(c) : "";
                XWPFTableCell cell = tableRow.getCell(c);
                cell.removeParagraph(0);
                XWPFParagraph p = cell.addParagraph();
                p.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun run = p.createRun();
                run.setText(stripInlineMarkdown(cellText).replace("\n", " "));
                run.setFontFamily(FONT_SONGTI);
                run.setFontSize(SIZE_5);
            }
        }

        document.createParagraph();
    }

    private List<List<String>> parseAsciiBoxTable(String asciiTable) {
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
        return rows;
    }

    private void applyThreeLineTableStyle(XWPFTable table) {
        if (table == null) {
            return;
        }
        if (table.getCTTbl().getTblPr() == null) {
            table.getCTTbl().addNewTblPr();
        }
        if (table.getCTTbl().getTblPr().getTblBorders() != null) {
            table.getCTTbl().getTblPr().unsetTblBorders();
        }
        CTTblBorders borders = table.getCTTbl().getTblPr().addNewTblBorders();

        CTBorder top = borders.addNewTop();
        top.setVal(STBorder.SINGLE);
        top.setSz(BigInteger.valueOf(12));

        CTBorder bottom = borders.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(12));

        CTBorder insideH = borders.addNewInsideH();
        insideH.setVal(STBorder.SINGLE);
        insideH.setSz(BigInteger.valueOf(4));

        CTBorder left = borders.addNewLeft();
        left.setVal(STBorder.NONE);
        CTBorder right = borders.addNewRight();
        right.setVal(STBorder.NONE);
        CTBorder insideV = borders.addNewInsideV();
        insideV.setVal(STBorder.NONE);
    }

    private void setLineSpacing(XWPFParagraph paragraph, int spacing) {
        CTPPr pPr = paragraph.getCTP().getPPr();
        if (pPr == null) {
            pPr = paragraph.getCTP().addNewPPr();
        }
        CTSpacing ctSpacing = pPr.getSpacing();
        if (ctSpacing == null) {
            ctSpacing = pPr.addNewSpacing();
        }
        ctSpacing.setLine(BigInteger.valueOf(spacing));
        ctSpacing.setLineRule(STLineSpacingRule.EXACT);
    }

    private void setSpacingBefore(XWPFParagraph paragraph, int spacing) {
        CTPPr pPr = paragraph.getCTP().getPPr();
        if (pPr == null) {
            pPr = paragraph.getCTP().addNewPPr();
        }
        CTSpacing ctSpacing = pPr.getSpacing();
        if (ctSpacing == null) {
            ctSpacing = pPr.addNewSpacing();
        }
        ctSpacing.setBefore(BigInteger.valueOf(spacing));
    }

    private void setSpacingAfter(XWPFParagraph paragraph, int spacing) {
        CTPPr pPr = paragraph.getCTP().getPPr();
        if (pPr == null) {
            pPr = paragraph.getCTP().addNewPPr();
        }
        CTSpacing ctSpacing = pPr.getSpacing();
        if (ctSpacing == null) {
            ctSpacing = pPr.addNewSpacing();
        }
        ctSpacing.setAfter(BigInteger.valueOf(spacing));
    }

    private String saveDocument(XWPFDocument document, String taskId, Integer version) throws IOException {
        return saveDocument(document, taskId, version, DEFAULT_DOC_TYPE);
    }
}
