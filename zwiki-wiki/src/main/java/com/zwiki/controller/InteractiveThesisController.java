package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.ThesisVersionEntity;
import com.zwiki.service.InteractiveThesisService;
import com.zwiki.service.ThesisProgressService;
import com.zwiki.service.template.GraduationThesisDocxService;
import com.zwiki.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互式论文生成控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/thesis/interactive")
@RequiredArgsConstructor
public class InteractiveThesisController {

    private final InteractiveThesisService interactiveThesisService;
    private final GraduationThesisDocxService graduationThesisDocxService;
    private final ThesisProgressService thesisProgressService;
    private final MinioService minioService;

    @PostMapping("/generate/{taskId}")
    public ResultVo<Map<String, Object>> generateThesis(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestBody(required = false) Map<String, String> thesisInfo) {
        try {
            String versionId = interactiveThesisService.generateThesisWithDefaultPrompt(taskId, docType, thesisInfo);
            return ResultVo.success(Map.of("versionId", versionId));
        } catch (Exception e) {
            log.error("生成论文失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/progress/{taskId}")
    public ResultVo<Map<String, Object>> getProgress(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType) {
        try {
            ThesisProgressService.Progress progress = thesisProgressService.getByDocType(taskId, docType).orElse(null);
            if (progress == null) {
                return ResultVo.success(Map.of(
                        "status", "not_started",
                        "progress", 0,
                        "currentStep", "未开始",
                        "updatedAt", System.currentTimeMillis()
                ));
            }
            return ResultVo.success(Map.of(
                    "status", progress.getStatus(),
                    "progress", progress.getProgress(),
                    "currentStep", progress.getCurrentStep(),
                    "updatedAt", progress.getUpdatedAt()
            ));
        } catch (Exception e) {
            log.error("查询论文生成进度失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/regenerate/{taskId}")
    public ResultVo<Map<String, Object>> regenerate(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, String> thesisInfo = new HashMap<>();
            boolean clearOldVersions = true;
            if (body != null) {
                body.forEach((key, value) -> {
                    if (value instanceof String str) {
                        thesisInfo.put(key, str);
                    }
                });
                if (body.containsKey("clearOldVersions")) {
                    clearOldVersions = Boolean.TRUE.equals(body.get("clearOldVersions"));
                }
            }
            String versionId = interactiveThesisService.regenerateThesis(taskId, docType,
                    thesisInfo.isEmpty() ? null : thesisInfo, clearOldVersions);
            return ResultVo.success(Map.of("versionId", versionId));
        } catch (Exception e) {
            log.error("重新生成论文失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/upload-template/{taskId}")
    public ResultVo<Map<String, Object>> uploadTemplate(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "template", required = false) MultipartFile templateFile) {
        try {
            Map<String, Object> templateStructure = interactiveThesisService
                    .uploadAndAnalyzeTemplate(taskId, templateFile);
            return ResultVo.success(templateStructure);
        } catch (Exception e) {
            log.error("上传模板失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/fill/{taskId}")
    public ResultVo<Map<String, Object>> fillThesis(
            @PathVariable("taskId") String taskId,
            @RequestParam("templatePath") String templatePath) {
        try {
            String versionId = interactiveThesisService.intelligentFillThesis(taskId, templatePath);
            return ResultVo.success(Map.of("versionId", versionId));
        } catch (Exception e) {
            log.error("填充论文失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/preview/{taskId}")
    public ResultVo<Map<String, Object>> previewThesis(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "version", required = false) Integer version) {
        try {
            String htmlPreview = interactiveThesisService.getThesisPreview(taskId, docType, version);
            if (htmlPreview == null) {
                return ResultVo.success(Map.of(
                        "htmlContent", "",
                        "notGenerated", true,
                        "hint", "请先生成论文"
                ));
            }
            return ResultVo.success(Map.of("htmlContent", htmlPreview, "notGenerated", false));
        } catch (Exception e) {
            log.error("预览论文失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/feedback")
    public ResultVo<Map<String, Object>> submitFeedback(@RequestBody Map<String, Object> feedbackData) {
        try {
            String taskId = (String) feedbackData.get("taskId");
            String docType = (String) feedbackData.get("docType");
            Integer version = feedbackData.get("version") instanceof Number number ? number.intValue() : null;
            String section = (String) feedbackData.get("section");
            String feedbackType = (String) feedbackData.get("feedbackType");
            String feedbackContent = (String) feedbackData.get("feedbackContent");

            Long feedbackId = interactiveThesisService.submitFeedback(
                    taskId, docType, version, section, feedbackType, feedbackContent);
            return ResultVo.success(Map.of("feedbackId", feedbackId));
        } catch (Exception e) {
            log.error("提交反馈失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/optimize/{feedbackId}")
    public ResultVo<Map<String, Object>> optimizeThesis(
            @PathVariable("feedbackId") Long feedbackId,
            @RequestParam("taskId") String taskId) {
        try {
            String newVersionId = interactiveThesisService.optimizeThesisBasedOnFeedback(taskId, feedbackId);
            return ResultVo.success(Map.of("newVersionId", newVersionId));
        } catch (Exception e) {
            log.error("优化论文失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/versions/{taskId}")
    public ResultVo<List<ThesisVersionEntity>> getVersionHistory(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType) {
        try {
            return ResultVo.success(interactiveThesisService.getVersionHistory(taskId, docType));
        } catch (Exception e) {
            log.error("查询版本历史失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @PostMapping("/confirm/{taskId}")
    public ResultVo<Void> confirmFinalVersion(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam("version") Integer version) {
        try {
            interactiveThesisService.confirmFinalVersion(taskId, docType, version);
            return ResultVo.success();
        } catch (Exception e) {
            log.error("确认最终版本失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/download-docx/{taskId}")
    public ResultVo<Map<String, Object>> downloadDocx(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "version", required = false) Integer version) {
        return downloadDocxWithInfo(taskId, docType, version, null);
    }

    @PostMapping("/download-docx/{taskId}")
    public ResultVo<Map<String, Object>> downloadDocxWithInfo(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "version", required = false) Integer version,
            @RequestBody(required = false) Map<String, String> thesisInfo) {
        try {
            byte[] docxBytes = graduationThesisDocxService.getDocxBytes(taskId, docType, version, thesisInfo);
            String normalizedDocType = (docType == null || docType.isBlank()) ? "thesis" : docType.trim();
            int safeVersion = version != null ? version : 1;

            String displayFilename;
            if ("task_book".equals(normalizedDocType)) {
                displayFilename = String.format("任务书_v%d.docx", safeVersion);
            } else if ("opening_report".equals(normalizedDocType)) {
                displayFilename = String.format("开题报告_v%d.docx", safeVersion);
            } else {
                displayFilename = String.format("毕业论文_v%d.docx", safeVersion);
            }

            String objectFilename = displayFilename;

            if (minioService == null || !minioService.isEnabled()) {
                return ResultVo.error("MinIO未配置，无法下载文档");
            }

            String downloadUrl = minioService.uploadDocument(
                    "thesis/" + taskId,
                    docxBytes,
                    objectFilename,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            return ResultVo.success(Map.of(
                    "filename", displayFilename,
                    "downloadUrl", downloadUrl
            ));
        } catch (Exception e) {
            log.error("下载Word文档失败", e);
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/download-markdown/{taskId}")
    public ResultVo<Map<String, Object>> downloadMarkdown(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "docType", required = false) String docType,
            @RequestParam(name = "version", required = false) Integer version) {
        try {
            String markdownContent = interactiveThesisService.getThesisMarkdownContent(taskId, docType, version);
            if (markdownContent == null || markdownContent.isEmpty()) {
                return ResultVo.error("论文内容为空");
            }
            String normalizedDocType = (docType == null || docType.isBlank()) ? "thesis" : docType.trim();
            int safeVersion = version != null ? version : 1;

            String displayFilename;
            if ("task_book".equals(normalizedDocType)) {
                displayFilename = String.format("任务书_v%d.md", safeVersion);
            } else if ("opening_report".equals(normalizedDocType)) {
                displayFilename = String.format("开题报告_v%d.md", safeVersion);
            } else {
                displayFilename = String.format("毕业论文_v%d.md", safeVersion);
            }

            String objectFilename = displayFilename;
            byte[] bytes = markdownContent.getBytes(StandardCharsets.UTF_8);

            if (minioService == null || !minioService.isEnabled()) {
                return ResultVo.error("MinIO未配置，无法下载文档");
            }

            String downloadUrl = minioService.uploadDocument(
                    "thesis/" + taskId,
                    bytes,
                    objectFilename,
                    "text/markdown");
            return ResultVo.success(Map.of(
                    "filename", displayFilename,
                    "downloadUrl", downloadUrl
            ));
        } catch (Exception e) {
            log.error("下载Markdown文档失败", e);
            return ResultVo.error(e.getMessage());
        }
    }
}
