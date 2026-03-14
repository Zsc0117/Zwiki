package com.zwiki.llm.model;

import java.util.Map;

/**
 * 当前请求的 Provider 上下文，通过 ThreadLocal 传递给 HTTP 拦截器。
 * 包含 baseUrl、apiKey、provider 类型和自定义请求头。
 */
public class ProviderContext {

    private static final ThreadLocal<ProviderContext> CURRENT = new ThreadLocal<>();

    private String baseUrl;
    private String apiKey;
    private ProviderType providerType;
    private String apiVersion;
    private Map<String, String> extraHeaders;

    public ProviderContext() {
    }

    public ProviderContext(String baseUrl, String apiKey, ProviderType providerType,
                           String apiVersion, Map<String, String> extraHeaders) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.providerType = providerType;
        this.apiVersion = apiVersion;
        this.extraHeaders = extraHeaders;
    }

    public static void set(ProviderContext ctx) {
        CURRENT.set(ctx);
    }

    public static ProviderContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public Map<String, String> getExtraHeaders() {
        return extraHeaders;
    }

    public void setExtraHeaders(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders;
    }

    @Override
    public String toString() {
        return "ProviderContext{" +
                "baseUrl='" + baseUrl + '\'' +
                ", providerType=" + providerType +
                ", apiVersion='" + apiVersion + '\'' +
                ", extraHeaders=" + (extraHeaders != null ? extraHeaders.size() + " entries" : "null") +
                '}';
    }
}
