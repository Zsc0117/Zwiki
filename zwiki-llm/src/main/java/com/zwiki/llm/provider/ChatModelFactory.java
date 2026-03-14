package com.zwiki.llm.provider;

import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.llm.model.ProviderContext;
import com.zwiki.llm.model.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.http.client.reactive.ClientHttpConnector;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 根据 Provider 配置动态创建/缓存 ChatModel 实例。
 * 每个唯一的 (baseUrl + apiKey) 组合对应一个缓存的 ChatModel。
 * 所有 Provider 均通过 OpenAI 兼容 API 协议调用。
 *
 * 注意：此类不是 @Component，而是由 LlmBalancerAutoConfiguration 显式创建 Bean，
 * 以避免与 LoadBalancingChatModel 之间的循环依赖。
 */
@Slf4j
public class ChatModelFactory {

    private static final Duration LLM_READ_TIMEOUT = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, ChatModel> cache = new ConcurrentHashMap<>();
    private static final ResponseErrorHandler DEFAULT_RESPONSE_ERROR_HANDLER = new DefaultResponseErrorHandler();

    private final OpenAiChatModel defaultChatModel;
    private final ToolCallingManager toolCallingManager;
    /**
     * Spring 管理的 RestClient.Builder 供应器。
     * 通过 RestClientCustomizer 注入的 Builder 包含所有 HTTP 拦截器（如 reasoning_content 注入、token usage 提取等），
     * 确保 ChatModelFactory 创建的 ChatModel 实例与默认 ChatModel 拥有相同的 HTTP 拦截行为。
     * 每次调用 get() 返回全新的 prototype 实例（已应用全部 Customizer）。
     */
    private final Supplier<org.springframework.web.client.RestClient.Builder> restClientBuilderSupplier;
    /**
     * WebClient 用的 ClientHttpConnector 供应器。
     * 包装了 ReasoningContentWebClientConnector，用于在流式调用中捕获/注入 reasoning_content。
     */
    private final Supplier<ClientHttpConnector> webClientConnectorSupplier;

    public ChatModelFactory(ChatModel defaultChatModel, ToolCallingManager toolCallingManager,
                            Supplier<org.springframework.web.client.RestClient.Builder> restClientBuilderSupplier,
                            Supplier<ClientHttpConnector> webClientConnectorSupplier) {
        this.restClientBuilderSupplier = restClientBuilderSupplier;
        this.webClientConnectorSupplier = webClientConnectorSupplier;
        this.toolCallingManager = toolCallingManager != null ? toolCallingManager : ToolCallingManager.builder().build();
        if (defaultChatModel == null) {
            this.defaultChatModel = null;
            log.info("ChatModelFactory created without default ChatModel, will create models on demand");
        } else if (defaultChatModel instanceof OpenAiChatModel openAi) {
            this.defaultChatModel = openAi;
        } else {
            this.defaultChatModel = null;
            log.warn("Default ChatModel is not OpenAiChatModel ({}), dynamic provider support may be limited",
                    defaultChatModel.getClass().getSimpleName());
        }
    }

    /**
     * 获取或创建对应配置的 ChatModel 实例，并设置 ProviderContext。
     */
    public ChatModel getOrCreate(LlmBalancerProperties.ModelConfig config) {
        String provider = config.getProvider();
        String apiKey = config.getApiKey();
        String customBaseUrl = config.getBaseUrl();
        String apiVersion = config.getApiVersion();
        Map<String, String> extraHeaders = config.getExtraHeaders();

        ProviderType providerType = ProviderType.fromCode(provider);
        String resolvedBaseUrl = ProviderType.resolveBaseUrl(provider, customBaseUrl);
        boolean streamUsage = providerType.supportsStreamUsage();

        // 设置 ProviderContext 供 HTTP 拦截器使用
        ProviderContext ctx = new ProviderContext(resolvedBaseUrl, apiKey, providerType, apiVersion, extraHeaders);
        ProviderContext.set(ctx);

        // 如果没有自定义 baseUrl 且没有自定义 apiKey，使用默认 ChatModel
        if ((resolvedBaseUrl == null || resolvedBaseUrl.isBlank()) && (apiKey == null || apiKey.isBlank())) {
            if (defaultChatModel != null) {
                return defaultChatModel;
            }
            throw new IllegalStateException("No baseUrl configured for provider " + provider + " and no default ChatModel available");
        }

        // 缓存 key: baseUrl#hash(apiKey)#streamUsage
        String cacheKey = buildCacheKey(resolvedBaseUrl, apiKey, streamUsage);

        return cache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new ChatModel instance for provider={}, baseUrl={}, streamUsage={}", 
                    provider, resolvedBaseUrl, streamUsage);
            return createChatModel(resolvedBaseUrl, apiKey, streamUsage);
        });
    }

    /**
     * 获取默认 ChatModel（无 Provider 配置时的 fallback）。
     */
    public ChatModel getDefault() {
        if (defaultChatModel != null) {
            return defaultChatModel;
        }
        throw new IllegalStateException("No default ChatModel available");
    }

    /**
     * 清除缓存（配置变更时调用）。
     */
    public void clearCache() {
        cache.clear();
        log.info("ChatModelFactory cache cleared");
    }

    /**
     * 匹配 baseUrl 末尾的版本路径段，如 /v1、/v4 等。
     * 若 baseUrl 已包含版本路径，则 API 子路径不应再加 /v1 前缀。
     */
    private static final Pattern VERSION_SUFFIX = Pattern.compile(".*/v\\d+$");

    /**
     * 根据 baseUrl 是否已含版本路径，返回正确的 [chatPath, embeddingsPath]。
     */
    private String[] resolveApiPaths(String baseUrl) {
        if (baseUrl != null && VERSION_SUFFIX.matcher(baseUrl).matches()) {
            return new String[]{"/chat/completions", "/embeddings"};
        }
        return new String[]{"/v1/chat/completions", "/v1/embeddings"};
    }

    private org.springframework.web.client.RestClient.Builder createRestClientBuilder() {
        // 优先使用 Spring 管理的 Builder（已包含 RestClientCustomizer 注入的拦截器：
        // reasoning_content 注入/提取、token usage 捕获、ProviderContext API Key 注入等）
        if (restClientBuilderSupplier != null) {
            org.springframework.web.client.RestClient.Builder springBuilder = restClientBuilderSupplier.get();
            if (springBuilder != null) {
                log.debug("Using Spring-managed RestClient.Builder with interceptors for ChatModel creation");
                return springBuilder;
            }
        }
        // 回退：创建不含拦截器的原始 Builder（仅设置超时）
        log.warn("No Spring-managed RestClient.Builder available, creating raw builder without interceptors. " +
                "Thinking model tool-calling (e.g. kimi-k2.5) may not work correctly.");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        factory.setReadTimeout((int) LLM_READ_TIMEOUT.toMillis());
        return org.springframework.web.client.RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(factory));
    }

    private org.springframework.web.reactive.function.client.WebClient.Builder createWebClientBuilder() {
        if (webClientConnectorSupplier != null) {
            try {
                ClientHttpConnector connector = webClientConnectorSupplier.get();
                if (connector != null) {
                    log.debug("Using custom ClientHttpConnector with reasoning_content support for WebClient");
                    return org.springframework.web.reactive.function.client.WebClient.builder()
                            .clientConnector(connector);
                }
            } catch (Exception e) {
                log.warn("Failed to create custom ClientHttpConnector, using default WebClient: {}", e.getMessage());
            }
        }
        return org.springframework.web.reactive.function.client.WebClient.builder();
    }

    private ChatModel createChatModel(String baseUrl, String apiKey, boolean streamUsage) {
        if (defaultChatModel == null) {
            return buildNewChatModel(baseUrl, apiKey, streamUsage);
        }

        try {
            String[] paths = resolveApiPaths(baseUrl);
            MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
            headers.add("Content-Type", "application/json");

            OpenAiApi api = new OpenAiApi(
                    baseUrl,
                    new SimpleApiKey(apiKey != null ? apiKey : ""),
                    headers,
                    paths[0],
                    paths[1],
                    createRestClientBuilder(),
                    createWebClientBuilder(),
                    DEFAULT_RESPONSE_ERROR_HANDLER
            );

            OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                    .streamUsage(streamUsage)
                    .build();
            if (streamUsage) {
                log.debug("Enabling streamUsage for token counting in SSE responses");
            }

            return new OpenAiChatModel(api, defaultOptions, toolCallingManager,
                    org.springframework.retry.support.RetryTemplate.defaultInstance(),
                    io.micrometer.observation.ObservationRegistry.NOOP);
        } catch (Exception e) {
            log.error("Failed to create ChatModel via OpenAiApi constructor for baseUrl={}", baseUrl, e);
            return buildNewChatModel(baseUrl, apiKey, streamUsage);
        }
    }

    private ChatModel buildNewChatModel(String baseUrl, String apiKey, boolean streamUsage) {
        String[] paths = resolveApiPaths(baseUrl);
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("Content-Type", "application/json");

        OpenAiApi api = new OpenAiApi(
                baseUrl,
                new SimpleApiKey(apiKey != null ? apiKey : ""),
                headers,
                paths[0],
                paths[1],
                createRestClientBuilder(),
                createWebClientBuilder(),
                DEFAULT_RESPONSE_ERROR_HANDLER
        );

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .streamUsage(streamUsage)
                .build();

        return new OpenAiChatModel(api, defaultOptions, toolCallingManager,
                org.springframework.retry.support.RetryTemplate.defaultInstance(),
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    private String buildCacheKey(String baseUrl, String apiKey, boolean streamUsage) {
        String urlPart = baseUrl != null ? baseUrl : "default";
        String keyPart = apiKey != null ? String.valueOf(apiKey.hashCode()) : "nokey";
        return urlPart + "#" + keyPart + "#" + streamUsage;
    }
}
