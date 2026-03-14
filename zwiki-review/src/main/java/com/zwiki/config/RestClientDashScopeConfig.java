package com.zwiki.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.llm.LoadBalancingChatModel;
import com.zwiki.llm.model.ProviderContext;
import com.zwiki.llm.model.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 通用 LLM HTTP 拦截器 for zwiki-review.
 * 通过 ProviderContext ThreadLocal 获取当前请求的 Provider 上下文，
 * 注入 API Key、自定义请求头，并解析响应中的 token usage。
 */
@Slf4j
@Configuration
public class RestClientDashScopeConfig {

    @Bean
    public RestClientCustomizer reviewRestClientCustomizer(
            @Value("${project.ai.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${project.ai.http.read-timeout:20m}") Duration readTimeout,
            ObjectMapper objectMapper
    ) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
            requestFactory.setReadTimeout((int) readTimeout.toMillis());
            builder.requestFactory(new BufferingClientHttpRequestFactory(requestFactory));

            ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
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
                            if (obj != null && requestModel != null && isThinkingModel(requestModel)) {
                                java.util.List<String> pendingReasonings = LoadBalancingChatModel.getPendingReasoningContents();
                                if (!pendingReasonings.isEmpty()) {
                                    JsonNode messagesNode = req.get("messages");
                                    if (messagesNode != null && messagesNode.isArray()) {
                                        int reasoningIdx = 0;
                                        for (JsonNode msgNode : messagesNode) {
                                            if (reasoningIdx >= pendingReasonings.size()) break;
                                            if (msgNode.isObject()) {
                                                JsonNode roleNode = msgNode.get("role");
                                                JsonNode toolCallsNode = msgNode.get("tool_calls");
                                                JsonNode existingReasoningNode = msgNode.get("reasoning_content");
                                                if (roleNode != null && "assistant".equals(roleNode.asText())
                                                        && toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()
                                                        && (existingReasoningNode == null || existingReasoningNode.isNull()
                                                            || (existingReasoningNode.isTextual() && existingReasoningNode.asText().isBlank()))) {
                                                    ((ObjectNode) msgNode).put("reasoning_content", pendingReasonings.get(reasoningIdx));
                                                    bodyModified = true;
                                                    reasoningIdx++;
                                                }
                                            }
                                        }
                                        if (reasoningIdx > 0) {
                                            log.info("Injected reasoning_content into {} assistant message(s) for thinking model '{}'",
                                                    reasoningIdx, requestModel);
                                        }
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

                                    // 提取思考模型的 reasoning_content 和 content
                                    JsonNode choices = resp.get("choices");
                                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                                        JsonNode message = choices.get(0).get("message");
                                        if (message != null) {
                                            JsonNode contentNode = message.get("content");
                                            if (contentNode != null && contentNode.isTextual() && !contentNode.asText().isBlank()) {
                                                LoadBalancingChatModel.setHttpContent(contentNode.asText());
                                            }
                                            JsonNode reasoningNode = message.get("reasoning_content");
                                            if (reasoningNode != null && reasoningNode.isTextual() && !reasoningNode.asText().isBlank()) {
                                                String reasoningContent = reasoningNode.asText();
                                                LoadBalancingChatModel.setReasoningContent(reasoningContent);
                                                JsonNode toolCallsNode = message.get("tool_calls");
                                                if (toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                                                    LoadBalancingChatModel.addPendingReasoningContent(reasoningContent);
                                                    log.info("Cached reasoning_content #{} for tool call scenario",
                                                            LoadBalancingChatModel.getPendingReasoningContents().size());
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    return response;
                } catch (IOException e) {
                    throw e;
                }
            };
            builder.requestInterceptor(interceptor);
        };
    }

    private boolean isThinkingModel(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.toLowerCase(Locale.ROOT);
        return lower.contains("kimi-k2") || lower.contains("kimi-k1.5");
    }
}
