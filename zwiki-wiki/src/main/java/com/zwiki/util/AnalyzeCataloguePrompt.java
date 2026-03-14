package com.zwiki.util;

import org.springframework.core.io.ClassPathResource;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 目录结构生成prompt（整合优化版）
 * 模板文件：templates/ftl/analyse_catalogue_prompt.ftl
 *
 * @author: CYM-pai
 * @date: 2026/01/30 14:22
 **/
@Slf4j
public class AnalyzeCataloguePrompt {

    private static final String TEMPLATE_PATH = "templates/ftl/analyse_catalogue_prompt.ftl";

    /**
     * 统一目录生成prompt，从FTL模板文件加载。
     * 占位符：{{$code_files}}（文件树）、{{$key_files}}（关键文件内容）、{{$repository_location}}
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
                log.info("目录生成prompt模板加载成功: {}, 长度={}", path, content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("加载目录生成prompt模板失败: {}", path, e);
            throw new RuntimeException("Failed to load prompt template: " + path, e);
        }
    }
}
