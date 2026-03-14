package com.zwiki.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zwiki.mcp.client.McpClient;

import java.util.Iterator;

public final class McpToolCallback {

    private final ObjectMapper objectMapper;
    private final McpClient client;
    private final String toolName;
    private final String inputSchemaJson;

    public McpToolCallback(ObjectMapper objectMapper, McpClient client, McpToolDescriptor descriptor) {
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

        this.inputSchemaJson = schemaJson;
    }

    public String toolName() {
        return toolName;
    }

    public String inputSchemaJson() {
        return inputSchemaJson;
    }

    public String invoke(String toolInput) {
        try {
            JsonNode args = (toolInput == null || toolInput.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(toolInput);

            JsonNode result = client.callTool(toolName, args);
            return normalizeToolResult(result);
        }
        catch (Exception e) {
            throw new IllegalStateException("MCP tool call failed: " + toolName, e);
        }
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
}
