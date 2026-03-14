package com.zwiki.llm.model;

/**
 * 支持的LLM提供商类型枚举。
 * 每种 Provider 对应一个默认 baseUrl（可被用户自定义覆盖）。
 */
public enum ProviderType {

    // supportsStreamUsage: whether the provider supports stream_options.include_usage for token counting in SSE
    DASHSCOPE("dashscope", "https://dashscope.aliyuncs.com/compatible-mode", true),
    OPENAI("openai", "https://api.openai.com", true),
    AZURE("azure", null, true),
    MINIMAX("minimax", "https://api.minimax.chat/v1", true),
    DEEPSEEK("deepseek", "https://api.deepseek.com", true),
    MOONSHOT("moonshot", "https://api.moonshot.cn/v1", true),
    ZHIPU("zhipu", "https://open.bigmodel.cn/api/paas/v4", true),
    CUSTOM("custom", null, true);

    private final String code;
    private final String defaultBaseUrl;
    private final boolean supportsStreamUsage;

    ProviderType(String code, String defaultBaseUrl, boolean supportsStreamUsage) {
        this.code = code;
        this.defaultBaseUrl = defaultBaseUrl;
        this.supportsStreamUsage = supportsStreamUsage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    /**
     * 返回该 Provider 是否支持 stream_options.include_usage 参数。
     * 支持的 Provider 在 SSE 流式响应的最后一个 chunk 中返回 token usage 信息。
     */
    public boolean supportsStreamUsage() {
        return supportsStreamUsage;
    }

    /**
     * 根据 code 字符串解析 ProviderType，不区分大小写。
     * 未知的 provider 返回 CUSTOM。
     */
    public static ProviderType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return DASHSCOPE;
        }
        String normalized = code.trim().toLowerCase();
        for (ProviderType pt : values()) {
            if (pt.code.equals(normalized)) {
                return pt;
            }
        }
        return CUSTOM;
    }

    /**
     * 解析有效的 baseUrl：优先使用用户自定义值，否则使用 Provider 默认值。
     */
    public static String resolveBaseUrl(String provider, String customBaseUrl) {
        if (customBaseUrl != null && !customBaseUrl.isBlank()) {
            String url = customBaseUrl.trim();
            // 去除末尾斜杠
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        ProviderType pt = fromCode(provider);
        return pt.getDefaultBaseUrl();
    }
}
