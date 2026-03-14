package com.zwiki.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thesis LLM wrapper service adapted for Zwiki.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThesisLLMService {

    private final LlmService llmService;

    public String generateThesisContent(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("论文生成失败：prompt 为空");
        }
        try {
            return llmService.callWithoutTools(prompt);
        } catch (Exception e) {
            log.error("调用LLM失败", e);
            throw new RuntimeException("调用LLM失败: " + e.getMessage(), e);
        }
    }

    public String generateCodeSummary(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "";
        }
        try {
            return llmService.callWithoutTools(prompt);
        } catch (Exception e) {
            log.error("生成内容失败", e);
            return "暂时无法生成分析结果，请稍后重试。";
        }
    }
}
