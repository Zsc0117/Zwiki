package com.zwiki.mcpserver.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * MCP Tool 定义，对应 MCP 协议中的 Tool 结构
 */
@Data
@Builder
public class McpToolDefinition {
    private String name;
    private String description;
    private JsonNode inputSchema;
}
