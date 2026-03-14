package com.zwiki.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zwiki.mcp.client.McpClient;
import com.zwiki.mcp.tool.McpToolDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class SpringAiMcpToolCallback implements ToolCallback {

    private static final int MAX_IDENTICAL_CALLS = 3;
    private static final long CALL_WINDOW_MS = 60_000;

    private final ObjectMapper objectMapper;
    private final McpClient client;
    private final String toolName;
    private final ToolDefinition toolDefinition;
    private final ConcurrentHashMap<String, CallRecord> recentCalls = new ConcurrentHashMap<>();

    public SpringAiMcpToolCallback(ObjectMapper objectMapper, McpClient client, McpToolDescriptor descriptor) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.toolName = descriptor.name();

        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(descriptor.inputSchema());
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool input schema", e);
        }

        this.toolDefinition = DefaultToolDefinition.builder()
                .name(descriptor.name())
                .description(descriptor.description() != null ? descriptor.description() : "")
                .inputSchema(schemaJson)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = (toolInput == null || toolInput.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(toolInput);

            if ("bailian_web_search".equals(toolName) && args != null && args.isObject()) {
                ObjectNode obj = (ObjectNode) args;
                JsonNode countNode = obj.get("count");
                if (countNode == null || !countNode.canConvertToInt() || countNode.asInt() <= 0) {
                    obj.put("count", 5);
                }
            }

            if ("open_drawio_mermaid".equals(toolName) && args != null && args.isObject()) {
                ObjectNode obj = (ObjectNode) args;
                String key = obj.has("content") ? "content" : (obj.has("mermaid") ? "mermaid" : null);
                if (key != null && obj.get(key).isTextual()) {
                    String original = obj.get(key).asText();
                    String sanitized = sanitizeMermaid(original);
                    if (!sanitized.equals(original)) {
                        log.info("MCP tool '{}' sanitized Mermaid content before call", toolName);
                        obj.put(key, sanitized);
                    }
                }
            }

            // Dedup: check if this exact call has been made too many times recently
            String argsKey = args != null ? args.toString() : "";
            String dupMessage = checkAndRecordCall(argsKey);
            if (dupMessage != null) {
                return dupMessage;
            }

            log.info("MCP tool '{}' call: args={}", toolName, args);
            JsonNode result = client.callTool(toolName, args);
            log.info("MCP tool '{}' raw result: {}", toolName,
                    result != null ? result.toString().substring(0, Math.min(result.toString().length(), 500)) : "null");

            // Check MCP-level isError flag (tool execution failure, distinct from JSON-RPC error)
            if (result != null && result.has("isError") && result.get("isError").asBoolean(false)) {
                String errorText = normalizeToolResult(result);
                log.warn("MCP tool '{}' returned isError=true: {}", toolName,
                        errorText.substring(0, Math.min(errorText.length(), 300)));
                return "[工具执行失败: " + toolName + "] " + errorText +
                        "\n请不要重试此工具，直接基于已有信息回答用户的问题，或尝试使用其他工具。";
            }

            String normalized = normalizeToolResult(result);

            // Detect empty search results and return a clear message
            if (isEmptySearchResult(normalized)) {
                log.info("MCP tool '{}' returned empty search results for args={}", toolName, args);
                return "搜索未找到与查询相关的结果。请尝试使用不同的关键词，或者直接基于你已有的知识回答用户的问题，不要重复搜索相同的内容。";
            }

            String mediaNormalized = tryNormalizeMediaResult(normalized);
            if (mediaNormalized != null && !mediaNormalized.isBlank()) {
                normalized = mediaNormalized;
            }

            String drawioNormalized = tryNormalizeDrawioResult(normalized);
            if (drawioNormalized != null) {
                normalized = drawioNormalized;
            }

            log.info("MCP tool '{}' normalized result length={}", toolName, normalized.length());
            return normalized;
        }
        catch (Exception e) {
            if (isRateLimit(e)) {
                log.warn("MCP tool '{}' rate-limited: {}", toolName, e.getMessage());
                return "[该工具已被限流，请不要再重试此工具调用，直接基于已有信息回答用户问题。]";
            }
            log.error("MCP tool '{}' call failed: {}", toolName, e.getMessage(), e);
            return "[工具调用失败: " + toolName + "，错误: " + e.getMessage() + "。请直接基于已有信息回答用户问题。]";
        }
    }

    /**
     * 修复 Mermaid 中常见语法问题：
     * 1) class A,B,C style1, style2, style3  ->  class A,B,C style1
     * 2) subgraph X + 节点 X[...] 同名导致“将 X 设置为 X 的父级”循环错误
     * 3) URL 编码的 Mermaid 内容需要先解码
     * 4) erDiagram 中实体名包含非法字符
     * 5) 行尾多余分号（erDiagram 不支持）
     */
    private String sanitizeMermaid(String mermaid) {
        if (mermaid == null || mermaid.isBlank()) {
            return mermaid;
        }

        // 检测并解码 URL 编码的 Mermaid 内容
        String fixed = mermaid;
        if (fixed.contains("%0A") || fixed.contains("%20") || fixed.contains("%7C")) {
            try {
                String decoded = java.net.URLDecoder.decode(fixed, java.nio.charset.StandardCharsets.UTF_8);
                if (!decoded.equals(fixed) && !decoded.isBlank()) {
                    log.info("MCP tool '{}': decoded URL-encoded Mermaid content", toolName);
                    fixed = decoded;
                }
            } catch (Exception e) {
                log.debug("URL decode of Mermaid content failed, using original", e);
            }
        }

        fixed = sanitizeClassSyntax(fixed);
        fixed = sanitizeSubgraphIdConflicts(fixed);
        fixed = sanitizeErDiagram(fixed);
        return fixed;
    }

    /**
     * 修复 erDiagram 中的常见语法问题：
     * - 实体名不能包含特殊字符（只允许字母、数字、下划线、连字符）
     * - 移除行尾多余分号
     * - 修复不完整的关系行
     */
    private String sanitizeErDiagram(String mermaid) {
        if (!mermaid.trim().startsWith("erDiagram")) {
            return mermaid;
        }
        String[] lines = mermaid.split("\\r?\\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 跳过注释、空行、关键字行
            if (trimmed.isEmpty() || trimmed.startsWith("%%") || trimmed.equals("erDiagram")) {
                continue;
            }

            // 移除行尾分号（erDiagram 不支持）
            if (trimmed.endsWith(";")) {
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                lines[i] = indent + trimmed.substring(0, trimmed.length() - 1).stripTrailing();
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : mermaid;
    }

    private String sanitizeClassSyntax(String mermaid) {
        String[] lines = mermaid.split("\\r?\\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.startsWith("class ")) {
                continue;
            }
            String body = trimmed.substring("class ".length()).trim();
            if (body.isEmpty()) {
                continue;
            }

            int split = body.indexOf(' ');
            if (split <= 0 || split >= body.length() - 1) {
                continue;
            }

            String nodeList = body.substring(0, split).trim();
            String classNames = body.substring(split + 1).trim();
            if (!classNames.contains(",")) {
                continue;
            }

            String firstStyle = classNames.split(",")[0].trim();
            if (firstStyle.isEmpty()) continue;

            String indent = line.substring(0, line.indexOf(trimmed));
            String rebuilt = indent + "class " + nodeList + " " + firstStyle;
            if (!rebuilt.equals(line)) {
                lines[i] = rebuilt;
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : mermaid;
    }

    private String sanitizeSubgraphIdConflicts(String mermaid) {
        String[] lines = mermaid.split("\\r?\\n", -1);

        Pattern nodePattern = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*[\\[({]");
        Pattern subgraphPattern = Pattern.compile("^(\\s*subgraph\\s+)([A-Za-z_][A-Za-z0-9_]*)(\\b.*)$");

        Set<String> nodeIds = new HashSet<>();
        Set<String> usedIds = new HashSet<>();

        for (String line : lines) {
            Matcher subgraphMatcher = subgraphPattern.matcher(line);
            if (subgraphMatcher.find()) {
                usedIds.add(subgraphMatcher.group(2));
                continue;
            }

            Matcher nodeMatcher = nodePattern.matcher(line);
            if (nodeMatcher.find()) {
                String id = nodeMatcher.group(1);
                nodeIds.add(id);
                usedIds.add(id);
            }
        }

        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher subgraphMatcher = subgraphPattern.matcher(line);
            if (!subgraphMatcher.find()) {
                continue;
            }

            String subgraphId = subgraphMatcher.group(2);
            if (!nodeIds.contains(subgraphId)) {
                continue;
            }

            String newSubgraphId = subgraphId + "_Group";
            while (usedIds.contains(newSubgraphId)) {
                newSubgraphId = newSubgraphId + "_";
            }
            usedIds.add(newSubgraphId);

            lines[i] = subgraphMatcher.group(1) + newSubgraphId + subgraphMatcher.group(3);
            changed = true;
        }

        return changed ? String.join("\n", lines) : mermaid;
    }

    @Override
    public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
        return call(toolInput);
    }

    private String normalizeToolResult(JsonNode result) {
        if (result == null || result.isNull()) {
            return "";
        }

        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            Iterator<JsonNode> it = content.elements();
            while (it.hasNext()) {
                JsonNode item = it.next();
                if (item.hasNonNull("text")) {
                    sb.append(item.get("text").asText());
                }
                else {
                    sb.append(item.toString());
                }
                if (it.hasNext()) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }

        if (result.isTextual()) {
            return result.asText();
        }

        if (result instanceof TextNode) {
            return result.textValue();
        }

        return result.toString();
    }

    /**
     * Check if this identical call has been made too many times recently.
     * Returns an error message to return to the LLM if the limit is exceeded, null otherwise.
     */
    private String checkAndRecordCall(String argsKey) {
        long now = System.currentTimeMillis();
        // Clean up expired entries
        recentCalls.entrySet().removeIf(e -> now - e.getValue().firstCallTime > CALL_WINDOW_MS);

        CallRecord record = recentCalls.compute(argsKey, (key, existing) -> {
            if (existing == null || now - existing.firstCallTime > CALL_WINDOW_MS) {
                return new CallRecord(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        int count = record.count.get();
        if (count > MAX_IDENTICAL_CALLS) {
            log.warn("MCP tool '{}' identical call blocked (count={}, args={})", toolName, count, argsKey);
            return "[此工具已使用相同参数调用" + MAX_IDENTICAL_CALLS + "次且均未获得有效结果。" +
                    "请不要再重复调用，直接基于已有信息回答用户的问题，或尝试完全不同的搜索关键词。]";
        }
        return null;
    }

    /**
     * Detect empty search results from web search tools.
     */
    private boolean isEmptySearchResult(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return true;
        }
        // Detect bailian_web_search empty result: {"status": 1, "pages": [], ...}
        if ("bailian_web_search".equals(toolName)) {
            try {
                JsonNode parsed = objectMapper.readTree(normalized);
                JsonNode pages = parsed.get("pages");
                if (pages != null && pages.isArray() && pages.isEmpty()) {
                    return true;
                }
            } catch (Exception ignored) {
                // Not JSON or parsing failed, check string pattern
            }
            return normalized.contains("\"pages\": []") || normalized.contains("\"pages\":[]");
        }
        return false;
    }

    private boolean isRateLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("429") || lower.contains("rate limit") ||
                        lower.contains("too many requests") || lower.contains("ratelimit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String tryNormalizeMediaResult(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String tn = toolName != null ? toolName.toLowerCase() : "";
        boolean isMediaTool = tn.contains("image") || tn.contains("video") || tn.contains("wan") || tn.contains("media");
        if (!isMediaTool) {
            return null;
        }

        String trimmed = normalized.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }

        try {
            JsonNode parsed = objectMapper.readTree(trimmed);
            JsonNode resultsNode = parsed.get("results");
            if (resultsNode == null || !resultsNode.isArray() || resultsNode.isEmpty()) {
                return null;
            }

            List<String> urls = new ArrayList<>();
            for (JsonNode n : resultsNode) {
                if (n != null && n.isTextual()) {
                    String u = n.asText();
                    if (u != null && !u.isBlank()) {
                        urls.add(u.trim());
                    }
                }
            }
            if (urls.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("已生成媒体文件。请在最终回复中原样输出以下链接（不要改写、不要省略）：\n");
            for (String u : urls) {
                sb.append(u).append("\n");
            }
            return sb.toString().trim();
        }
        catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Post-process drawio tool results: extract URL and add instructions to prevent
     * the AI from reproducing Mermaid/XML content (which often gets URL-encoded and breaks rendering).
     */
    private String tryNormalizeDrawioResult(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String tn = toolName != null ? toolName.toLowerCase() : "";
        boolean isDrawioTool = tn.contains("drawio") || tn.contains("draw_io");
        if (!isDrawioTool) {
            return null;
        }

        // Extract the draw.io URL from the result
        Pattern urlPattern = Pattern.compile("(https?://(?:app\\.diagrams\\.net|embed\\.diagrams\\.net|(?:www\\.)?draw\\.io)[^\\s]*)");
        Matcher matcher = urlPattern.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }

        String drawioUrl = matcher.group(1);
        // Clean trailing punctuation
        drawioUrl = drawioUrl.replaceAll("[)。,，!?！？~～;；:：]+$", "");

        return "图表已成功生成，draw.io 编辑器链接如下：\n" + drawioUrl + "\n\n" +
                "【重要】请在回复中原样输出上面的完整链接。" +
                "不要在回复中用代码块输出 Mermaid/XML 源代码，前端会自动从链接中解析并渲染图表。" +
                "你可以简要描述图表内容，但不要复制或重新输出图表的源代码。";
    }

    private static class CallRecord {
        final AtomicInteger count;
        final long firstCallTime;

        CallRecord(long time) {
            this.count = new AtomicInteger(1);
            this.firstCallTime = time;
        }
    }
}
