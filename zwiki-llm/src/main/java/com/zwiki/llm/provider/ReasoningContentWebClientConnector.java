package com.zwiki.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwiki.llm.LoadBalancingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpResponseDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 包装 ClientHttpConnector，为 WebClient（流式调用）提供 reasoning_content 的注入和捕获。
 * <p>
 * 思考模型（如 Kimi K2.5）在工具调用场景下要求每个 assistant+tool_calls 消息都包含 reasoning_content。
 * RestClient 拦截器（RestClientTimeoutConfig）已处理非流式调用，
 * 此 Connector 处理流式调用（WebClient）的相同逻辑：
 * <ul>
 *   <li>请求侧：将缓存的 reasoning_content 注入到 assistant 消息中</li>
 *   <li>响应侧：从 SSE 数据流中捕获 reasoning_content 并缓存，供后续请求注入</li>
 * </ul>
 */
@Slf4j
public class ReasoningContentWebClientConnector implements ClientHttpConnector {

    private final ClientHttpConnector delegate;
    private final ObjectMapper objectMapper;

    /**
     * 实例级 reasoning_content 缓存，用于跨 reactor 线程传递。
     * ThreadLocal 在流式场景下无法跨 reactor 线程传递数据：
     * 响应捕获在 reactor-http-nio-3，下次请求在 reactor-http-nio-4，ThreadLocal 为空。
     * 此 Deque 作为跨线程后备存储，按 FIFO 顺序消费。
     */
    private final ConcurrentLinkedDeque<String> capturedReasoningContents = new ConcurrentLinkedDeque<>();
    private static final int MAX_INSTANCE_CACHE_SIZE = 50;

    public ReasoningContentWebClientConnector(ClientHttpConnector delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public Mono<ClientHttpResponse> connect(HttpMethod method, URI uri,
            Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {

        boolean isChatEndpoint = uri.getPath() != null && uri.getPath().contains("chat/completions");

        if (!isChatEndpoint) {
            return delegate.connect(method, uri, requestCallback);
        }

        // Always wrap request body for chat endpoints.
        // We CANNOT skip based on ThreadLocal pending list being empty, because in streaming
        // scenarios the reactor thread handling this request differs from the one that captured
        // reasoning_content from the previous response. ThreadLocal is per-thread and empty here.
        return delegate.connect(method, uri, httpRequest -> {
            ClientHttpRequest wrappedRequest = new ClientHttpRequestDecorator(httpRequest) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return DataBufferUtils.join(Flux.from(body))
                            .flatMap(buffer -> {
                                try {
                                    byte[] bytes = consumeBuffer(buffer);
                                    byte[] modified = injectReasoningContent(bytes);
                                    DataBuffer newBuffer = getDelegate().bufferFactory().wrap(modified);
                                    getHeaders().setContentLength(modified.length);
                                    return getDelegate().writeWith(Mono.just(newBuffer));
                                } catch (Exception e) {
                                    log.warn("Failed to inject reasoning_content into WebClient request: {}", e.getMessage());
                                    DataBuffer fallbackBuffer = getDelegate().bufferFactory().wrap(consumeBuffer(buffer));
                                    return getDelegate().writeWith(Mono.just(fallbackBuffer));
                                }
                            });
                }
            };
            return requestCallback.apply(wrappedRequest);
        }).map(this::wrapResponseForCapture);
    }

    private byte[] consumeBuffer(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    /**
     * 将缓存的 reasoning_content 注入到请求体中的 assistant+tool_calls 消息。
     * 三级注入策略：
     * 1. ThreadLocal pending list（同线程场景）
     * 2. 实例级 ConcurrentLinkedDeque（跨 reactor 线程场景）
     * 3. 占位符 "."（兜底，确保字段存在以满足 API 要求）
     */
    private byte[] injectReasoningContent(byte[] body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            if (!req.isObject()) return body;

            ObjectNode obj = (ObjectNode) req;
            JsonNode modelNode = obj.get("model");
            String modelName = modelNode != null && modelNode.isTextual() ? modelNode.asText() : null;

            if (modelName == null || !isThinkingModel(modelName)) {
                return body;
            }

            JsonNode messagesNode = obj.get("messages");
            if (messagesNode == null || !messagesNode.isArray()) {
                return body;
            }

            // Get ThreadLocal pending list (may be empty if on a different reactor thread)
            List<String> pendingReasonings = LoadBalancingChatModel.getPendingReasoningContents();
            int reasoningIdx = 0;
            boolean modified = false;
            int injectedFromThreadLocal = 0;
            int injectedFromInstance = 0;
            int injectedPlaceholder = 0;

            for (JsonNode msgNode : messagesNode) {
                if (!msgNode.isObject()) continue;

                JsonNode roleNode = msgNode.get("role");
                JsonNode toolCallsNode = msgNode.get("tool_calls");
                JsonNode existingRc = msgNode.get("reasoning_content");

                if (roleNode != null && "assistant".equals(roleNode.asText())
                        && toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()
                        && isMissingReasoningContent(existingRc)) {

                    String reasoningContent = null;

                    // Priority 1: ThreadLocal pending list (same-thread)
                    if (reasoningIdx < pendingReasonings.size()) {
                        reasoningContent = pendingReasonings.get(reasoningIdx);
                        reasoningIdx++;
                        if (reasoningContent != null && !reasoningContent.isBlank()) {
                            injectedFromThreadLocal++;
                        }
                    }

                    // Priority 2: Instance-level deque (cross-reactor-thread backup)
                    if (reasoningContent == null || reasoningContent.isBlank()) {
                        reasoningContent = capturedReasoningContents.poll();
                        if (reasoningContent != null && !reasoningContent.isBlank()) {
                            injectedFromInstance++;
                        }
                    }

                    // Priority 3: Placeholder fallback (ensures field exists for API)
                    if (reasoningContent == null || reasoningContent.isBlank()) {
                        reasoningContent = ".";
                        injectedPlaceholder++;
                    }

                    ((ObjectNode) msgNode).put("reasoning_content", reasoningContent);
                    modified = true;
                }
            }

            if (modified) {
                log.info("[WebClient] Injected reasoning_content for thinking model '{}': " +
                        "threadLocal={}, instanceCache={}, placeholder={}",
                        modelName, injectedFromThreadLocal, injectedFromInstance, injectedPlaceholder);
                return objectMapper.writeValueAsBytes(obj);
            }
        } catch (Exception e) {
            log.warn("Failed to parse/modify WebClient request body for reasoning_content injection: {}", e.getMessage());
        }
        return body;
    }

    private boolean isMissingReasoningContent(JsonNode existingRc) {
        return existingRc == null || existingRc.isNull()
                || (existingRc.isTextual() && existingRc.asText().isBlank());
    }

    /**
     * 包装响应以从 SSE 流中捕获 reasoning_content。
     * 非破坏性读取 DataBuffer（保存/恢复 readPosition），不影响下游消费。
     */
    private ClientHttpResponse wrapResponseForCapture(ClientHttpResponse response) {
        return new ClientHttpResponseDecorator(response) {
            @Override
            public Flux<DataBuffer> getBody() {
                StringBuilder reasoningAccumulator = new StringBuilder();
                AtomicBoolean hasToolCalls = new AtomicBoolean(false);

                return getDelegate().getBody()
                        .doOnNext(buffer -> {
                            try {
                                // Non-destructive read: save and restore readPosition
                                int pos = buffer.readPosition();
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                buffer.readPosition(pos);

                                String chunk = new String(bytes, StandardCharsets.UTF_8);
                                parseSSEChunk(chunk, reasoningAccumulator, hasToolCalls);
                            } catch (Exception e) {
                                log.debug("Error parsing SSE chunk for reasoning_content capture: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            if (hasToolCalls.get() && reasoningAccumulator.length() > 0) {
                                String content = reasoningAccumulator.toString();
                                // Store in ThreadLocal (for same-thread scenarios, e.g. RestClient)
                                LoadBalancingChatModel.addPendingReasoningContent(content);
                                // Also store in instance-level deque (for cross-reactor-thread scenarios)
                                if (capturedReasoningContents.size() < MAX_INSTANCE_CACHE_SIZE) {
                                    capturedReasoningContents.offer(content);
                                }
                                log.info("[WebClient] Captured reasoning_content (length={}) from streaming response with tool_calls, " +
                                        "threadLocal={}, instanceCache={}",
                                        content.length(),
                                        LoadBalancingChatModel.getPendingReasoningContents().size(),
                                        capturedReasoningContents.size());
                            }
                        });
            }
        };
    }

    /**
     * 解析 SSE 数据块，提取 delta.reasoning_content 和检测 delta.tool_calls。
     * SSE 格式: data: {"choices":[{"delta":{"reasoning_content":"...","tool_calls":[...]}}]}
     */
    private void parseSSEChunk(String rawChunk, StringBuilder reasoningAccumulator, AtomicBoolean hasToolCalls) {
        for (String line : rawChunk.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) continue;

            try {
                JsonNode node = objectMapper.readTree(data);
                JsonNode choices = node.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;

                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) continue;

                JsonNode rc = delta.get("reasoning_content");
                if (rc != null && rc.isTextual()) {
                    reasoningAccumulator.append(rc.asText());
                }

                JsonNode tc = delta.get("tool_calls");
                if (tc != null && tc.isArray() && !tc.isEmpty()) {
                    hasToolCalls.set(true);
                }
            } catch (Exception ignored) {
                // Individual chunk parse failure is OK — may be partial JSON across buffer boundaries
            }
        }
    }

    private boolean isThinkingModel(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.toLowerCase(Locale.ROOT);
        return lower.contains("kimi-k2") || lower.contains("kimi-k1.5");
    }
}
