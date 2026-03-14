package com.zwiki.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String name,
        String description,
        JsonNode inputSchema
) {
}
