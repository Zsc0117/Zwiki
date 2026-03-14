package com.zwiki.util;

import org.springframework.core.io.ClassPathResource;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档生成prompt（整合优化版）
 * 模板文件：templates/ftl/gen_doc_prompt.ftl
 *
 * @author pai
 * @date 2026/1/23 15:28
 */
@Slf4j
public class GenDocPrompt {

    private static final String TEMPLATE_PATH = "templates/ftl/gen_doc_prompt.ftl";

    /**
     * 统一文档生成prompt，从FTL模板文件加载。
     * 占位符：{{title}}, {{prompt}}, {{repository_location}},
     *         {{dependent_file_contents}}, {{catalogue_context}}, {{er_diagram}}
     */
    public static final String PROMPT;

    static {
        PROMPT = loadTemplate(TEMPLATE_PATH);
    }

    private static String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("文档生成prompt模板加载成功: {}, 长度={}", path, content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("加载文档生成prompt模板失败: {}", path, e);
            throw new RuntimeException("Failed to load prompt template: " + path, e);
        }
    }
}
