package com.zwiki.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwiki.llm.LoadBalancingChatModel;
import com.zwiki.llm.model.ProviderContext;
import com.zwiki.llm.model.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 通用 LLM HTTP 拦截器（适用于所有 Provider）。
 * 通过 ProviderContext ThreadLocal 获取当前请求的 Provider 上下文，
 * 注入 API Key、自定义请求头，并解析响应中的 token usage 和 content。
 */
@Slf4j
@Configuration
public class RestClientTimeoutConfig {

    @Bean
    public RestClientCustomizer aiRestClientTimeoutCustomizer(
            @Value("${project.ai.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${project.ai.http.read-timeout:20m}") Duration readTimeout,
            ObjectMapper objectMapper
    ) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
            requestFactory.setReadTimeout((int) readTimeout.toMillis());
            builder.requestFactory(new BufferingClientHttpRequestFactory(requestFactory));

            ClientHttpRequestInterceptor providerInterceptor = (request, body, execution) -> {
                try {
                    var uri = request.getURI();
                    String host = uri != null ? uri.getHost() : null;

                    // 从 ProviderContext 获取当前 Provider 上下文
                    ProviderContext ctx = ProviderContext.get();
                    boolean isLlmRequest = (ctx != null && ctx.getProviderType() != null);

                    // 向后兼容：无 ProviderContext 时检查旧的 dashscope 逻辑
                    if (!isLlmRequest && host != null && host.contains("dashscope")) {
                        isLlmRequest = true;
                    }

                    if (isLlmRequest) {
                        ProviderType providerType = ctx != null ? ctx.getProviderType() : ProviderType.DASHSCOPE;
                        log.info("LLM request [{}]: {} {}", providerType.getCode(), request.getMethod(), uri);

                        // 注入 API Key (优先使用 ProviderContext，回退到 CURRENT_API_KEY)
                        String apiKey = ctx != null ? ctx.getApiKey() : null;
                        if (apiKey == null || apiKey.isBlank()) {
                            apiKey = LoadBalancingChatModel.getCurrentApiKey();
                        }
                        if (apiKey != null && !apiKey.isBlank()) {
                            request.getHeaders().setBearerAuth(apiKey);
                        }

                        // 注入自定义请求头（企业网关场景）
                        if (ctx != null && ctx.getExtraHeaders() != null) {
                            for (Map.Entry<String, String> header : ctx.getExtraHeaders().entrySet()) {
                                String headerName = header.getKey();
                                // 安全过滤：阻止覆盖关键 header
                                if (!"host".equalsIgnoreCase(headerName)
                                        && !"authorization".equalsIgnoreCase(headerName)
                                        && !"content-type".equalsIgnoreCase(headerName)
                                        && !"content-length".equalsIgnoreCase(headerName)) {
                                    request.getHeaders().set(headerName, header.getValue());
                                }
                            }
                        }
                    }

                    String requestModel = null;
                    byte[] effectiveBody = body;
                    if (isLlmRequest && body != null && body.length > 0) {
                        try {
                            JsonNode req = objectMapper.readTree(body);
                            JsonNode modelNode = req.get("model");
                            if (modelNode != null && modelNode.isTextual()) {
                                requestModel = modelNode.asText();
                            }
                            boolean bodyModified = false;
                            ObjectNode obj = req.isObject() ? (ObjectNode) req : null;
                            
                            // 思考模型 + 工具调用场景：注入缓存的 reasoning_content 到 assistant 消息
                            // Kimi K2.5/K2 在工具调用后的请求中需要每个 assistant+tool_calls 消息都包含 reasoning_content，
                            // 但 Spring AI 不支持保留该字段，所以需要从缓存列表中按顺序注入
                            if (obj != null && requestModel != null && isThinkingModel(requestModel)) {
                                java.util.List<String> pendingReasonings = LoadBalancingChatModel.getPendingReasoningContents();
                                JsonNode messagesNode = req.get("messages");
                                if (messagesNode != null && messagesNode.isArray()) {
                                    int reasoningIdx = 0;
                                    int injectedFromCache = 0;
                                    int injectedPlaceholder = 0;
                                    for (JsonNode msgNode : messagesNode) {
                                        if (!msgNode.isObject()) continue;
                                        JsonNode roleNode = msgNode.get("role");
                                        JsonNode toolCallsNode = msgNode.get("tool_calls");
                                        JsonNode existingReasoningNode = msgNode.get("reasoning_content");
                                        // 找到有 tool_calls 但没有 reasoning_content 的 assistant 消息
                                        if (roleNode != null && "assistant".equals(roleNode.asText())
                                                && toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()
                                                && (existingReasoningNode == null || existingReasoningNode.isNull() 
                                                    || (existingReasoningNode.isTextual() && existingReasoningNode.asText().isBlank()))) {
                                            // Priority 1: ThreadLocal pending list
                                            if (reasoningIdx < pendingReasonings.size()) {
                                                String rc = pendingReasonings.get(reasoningIdx);
                                                reasoningIdx++;
                                                if (rc != null && !rc.isBlank()) {
                                                    ((ObjectNode) msgNode).put("reasoning_content", rc);
                                                    injectedFromCache++;
                                                    bodyModified = true;
                                                    continue;
                                                }
                                            }
                                            // Priority 2: Placeholder fallback (ensures field exists for API)
                                            ((ObjectNode) msgNode).put("reasoning_content", ".");
                                            injectedPlaceholder++;
                                            bodyModified = true;
                                        }
                                    }
                                    if (injectedFromCache > 0 || injectedPlaceholder > 0) {
                                        log.info("Injected reasoning_content for thinking model '{}': cache={}, placeholder={}",
                                                requestModel, injectedFromCache, injectedPlaceholder);
                                    }
                                }
                            }
                            
                            // 注入 DashScope 原生联网搜索参数
                            if (LoadBalancingChatModel.isWebSearchEnabled() && obj != null) {
                                ProviderType pt = ctx != null ? ctx.getProviderType() : ProviderType.DASHSCOPE;
                                if (pt == ProviderType.DASHSCOPE) {
                                    if (!obj.has("enable_search")) {
                                        obj.put("enable_search", true);
                                        bodyModified = true;
                                        log.info("Injected enable_search=true into DashScope request");
                                    }
                                }
                            }
                            
                            if (bodyModified) {
                                effectiveBody = objectMapper.writeValueAsBytes(obj);
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    var response = execution.execute(request, effectiveBody);

                    if (isLlmRequest) {
                        MediaType contentType = null;
                        try {
                            contentType = response.getHeaders().getContentType();
                        } catch (Exception ignored) {
                        }

                        boolean maybeJson = contentType == null
                                || MediaType.APPLICATION_JSON.includes(contentType)
                                || MediaType.APPLICATION_PROBLEM_JSON.includes(contentType)
                                || (contentType.getSubtype() != null && contentType.getSubtype().toLowerCase().contains("json"));

                        if (maybeJson) {
                            try {
                                byte[] respBytes = StreamUtils.copyToByteArray(response.getBody());
                                if (respBytes != null && respBytes.length > 0) {
                                    JsonNode resp = objectMapper.readTree(respBytes);

                                    // Prefer request model name (matches balancer's selectedModel)
                                    String model = requestModel;
                                    if (model == null || model.isBlank()) {
                                        JsonNode respModelNode = resp.get("model");
                                        if (respModelNode != null && respModelNode.isTextual()) {
                                            model = respModelNode.asText();
                                        }
                                    }

                                    JsonNode usage = resp.get("usage");
                                    if (model != null && !model.isBlank() && usage != null && usage.isObject()) {
                                        long promptTokens = usage.path("prompt_tokens").asLong(0);
                                        long completionTokens = usage.path("completion_tokens").asLong(0);
                                        long totalTokens = usage.path("total_tokens").asLong(0);
                                        LoadBalancingChatModel.setHttpUsageSnapshot(model, promptTokens, completionTokens, totalTokens);
                                        log.debug("HTTP usage snapshot set: model={}, input={}, output={}, total={}",
                                                model, promptTokens, completionTokens, totalTokens);
                                    }

                                    // 提取思考模型的 reasoning_content（CoT 思考过程）和 content（实际回答）
                                    // reasoning_content 是内部思考过程，content 才是最终回答
                                    JsonNode choices = resp.get("choices");
                                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                                        JsonNode message = choices.get(0).get("message");
                                        if (message != null) {
                                            // 提取 content（实际回答，作为 Spring AI getText() 的后备）
                                            JsonNode contentNode = message.get("content");
                                            if (contentNode != null && contentNode.isTextual() && !contentNode.asText().isBlank()) {
                                                LoadBalancingChatModel.setHttpContent(contentNode.asText());
                                                log.debug("Extracted HTTP content, length={}", contentNode.asText().length());
                                            }
                                            // 提取 reasoning_content（思考过程）
                                            JsonNode reasoningNode = message.get("reasoning_content");
                                            if (reasoningNode != null && reasoningNode.isTextual() && !reasoningNode.asText().isBlank()) {
                                                String reasoningContent = reasoningNode.asText();
                                                LoadBalancingChatModel.setReasoningContent(reasoningContent);
                                                log.debug("Extracted reasoning_content from thinking model, length={}", reasoningContent.length());
                                                
                                                // 如果响应中包含 tool_calls，需要缓存 reasoning_content 以便后续请求注入
                                                // Kimi 等思考模型要求在提交 tool 结果时，assistant 消息必须包含 reasoning_content
                                                JsonNode toolCallsNode = message.get("tool_calls");
                                                if (toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                                                    LoadBalancingChatModel.addPendingReasoningContent(reasoningContent);
                                                    log.info("Cached reasoning_content #{} for tool call scenario, will inject into next request",
                                                            LoadBalancingChatModel.getPendingReasoningContents().size());
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                                // ignore usage parsing failures to avoid breaking AI calls
                            }
                        }
                    }

                    return response;
                } catch (IOException e) {
                    throw e;
                }
            };
            builder.requestInterceptor(providerInterceptor);
        };
    }
    
    /**
     * 检查模型是否为思考/推理模型（如 Kimi K2.5, K2 等）
     * 这类模型在工具调用场景下需要在 assistant 消息中保留 reasoning_content，
     * 否则 API 会返回 "thinking is enabled but reasoning_content is missing" 错误
     */
    private boolean isThinkingModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        return lower.contains("kimi-k2") || lower.contains("kimi-k1.5");
    }
}
