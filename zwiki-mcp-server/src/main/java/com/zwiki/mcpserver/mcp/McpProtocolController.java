package com.zwiki.mcpserver.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP 协议控制器。
 * 支持两种传输方式：
 * 1. SSE (Server-Sent Events) — GET /sse + POST /sse/message
 * 2. Streamable HTTP — POST /mcp
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpProtocolController {

    private final McpToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "zwiki-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    // SSE 会话管理
    private final ConcurrentHashMap<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepaliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PostConstruct
    public void init() {
        // 每 15 秒发送 SSE 注释保持连接活跃，防止代理/CDN 关闭空闲连接
        keepaliveScheduler.scheduleAtFixedRate(() -> {
            sseEmitters.forEach((sid, emitter) -> {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                    sseEmitters.remove(sid);
                }
            });
        }, 15, 15, TimeUnit.SECONDS);
    }

    // ======================== Streamable HTTP (POST /mcp) ========================

    @PostMapping(value = {"/mcp", "/sse"})
    public ResponseEntity<JsonNode> handleStreamableHttp(@RequestBody JsonNode request) {
        log.info("MCP Streamable HTTP request: {}", request);

        try {
            // 处理批量请求
            if (request.isArray()) {
                ArrayNode responses = objectMapper.createArrayNode();
                for (JsonNode item : request) {
                    JsonNode response = processJsonRpc(item);
                    if (response != null) {
                        responses.add(response);
                    }
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responses);
            }

            JsonNode response = processJsonRpc(request);
            if (response == null) {
                return ResponseEntity.accepted().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Streamable HTTP 处理异常", e);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildError(null, -32603, "Internal error: " + e.getMessage()));
        }
    }

    // ======================== SSE Transport (GET /sse + POST /sse/message) ========================

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSseConnect() {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        sseEmitters.put(sessionId, emitter);
        emitter.onCompletion(() -> sseEmitters.remove(sessionId));
        emitter.onTimeout(() -> sseEmitters.remove(sessionId));
        emitter.onError(e -> sseEmitters.remove(sessionId));

        log.info("SSE 客户端连接: sessionId={}", sessionId);

        // 发送 endpoint 事件，告知客户端消息端点
        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data("/sse/message?sessionId=" + sessionId));
        } catch (IOException e) {
            log.error("发送 SSE endpoint 事件失败", e);
        }

        return emitter;
    }

    @PostMapping(value = "/sse/message")
    public ResponseEntity<Void> handleSseMessage(
            @RequestParam("sessionId") String sessionId,
            @RequestBody JsonNode request) {

        SseEmitter emitter = sseEmitters.get(sessionId);
        if (emitter == null) {
            log.warn("SSE 会话不存在: sessionId={}", sessionId);
            return ResponseEntity.notFound().build();
        }

        log.info("SSE message received: sessionId={}, request={}", sessionId, request);

        JsonNode response;
        try {
            response = processJsonRpc(request);
        } catch (Exception e) {
            log.error("SSE JSON-RPC 处理异常: sessionId={}", sessionId, e);
            response = buildError(
                    request.has("id") ? request.get("id") : null,
                    -32603, "Internal error: " + e.getMessage());
        }

        if (response != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.error("发送 SSE 响应失败: sessionId={}", sessionId, e);
                sseEmitters.remove(sessionId);
            }
        }

        return ResponseEntity.accepted().build();
    }

    // ======================== JSON-RPC 2.0 处理 ========================

    private JsonNode processJsonRpc(JsonNode request) {
        String method = request.has("method") ? request.get("method").asText() : "";
        JsonNode id = request.has("id") ? request.get("id") : null;
        JsonNode params = request.has("params") ? request.get("params") : objectMapper.createObjectNode();

        log.info("处理 JSON-RPC: method={}, id={}", method, id);

        return switch (method) {
            case "initialize" -> handleInitialize(id, params);
            case "notifications/initialized" -> null; // notification, no response
            case "tools/list" -> handleToolsList(id);
            case "tools/call" -> handleToolsCall(id, params);
            case "ping" -> buildResult(id, objectMapper.createObjectNode());
            default -> buildError(id, -32601, "Method not found: " + method);
        };
    }

    private JsonNode handleInitialize(JsonNode id, JsonNode params) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);

        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode toolsCap = objectMapper.createObjectNode();
        toolsCap.put("listChanged", false);
        capabilities.set("tools", toolsCap);
        result.set("capabilities", capabilities);

        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);

        log.info("MCP 客户端初始化完成");
        return buildResult(id, result);
    }

    private JsonNode handleToolsList(JsonNode id) {
        List<McpToolDefinition> tools = toolRegistry.listTools();
        ArrayNode toolsArray = objectMapper.createArrayNode();

        for (McpToolDefinition tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            toolNode.set("inputSchema", tool.getInputSchema());
            toolsArray.add(toolNode);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolsArray);
        return buildResult(id, result);
    }

    private JsonNode handleToolsCall(JsonNode id, JsonNode params) {
        String toolName = params.has("name") ? params.get("name").asText() : "";
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();

        if (!toolRegistry.hasTool(toolName)) {
            return buildError(id, -32602, "Unknown tool: " + toolName);
        }

        log.info("调用工具: name={}, arguments={}", toolName, arguments);
        long start = System.currentTimeMillis();

        String resultText = toolRegistry.callTool(toolName, arguments);

        long elapsed = System.currentTimeMillis() - start;
        boolean isToolError = resultText != null && resultText.startsWith("工具执行失败");
        log.info("工具调用完成: name={}, elapsed={}ms, isError={}, resultLength={}", toolName, elapsed,
                isToolError, resultText != null ? resultText.length() : 0);

        // MCP tools/call 响应格式
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", resultText != null ? resultText : "");
        content.add(textContent);
        result.set("content", content);
        result.put("isError", isToolError);

        return buildResult(id, result);
    }

    // ======================== 异常处理 ========================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonNode> handleException(Exception e) {
        log.error("MCP 控制器未捕获异常: {}", e.getMessage(), e);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildError(null, -32603, "Internal error: " + e.getMessage()));
    }

    // ======================== JSON-RPC 响应构建 ========================

    private JsonNode buildResult(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        response.set("result", result);
        return response;
    }

    private JsonNode buildError(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }
}
