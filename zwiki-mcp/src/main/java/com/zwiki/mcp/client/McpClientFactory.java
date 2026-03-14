package com.zwiki.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.mcp.client.http.SseMcpClient;
import com.zwiki.mcp.client.http.StreamableHttpMcpClient;
import com.zwiki.mcp.client.stdio.StdioMcpClient;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.model.TransportType;

public final class McpClientFactory {

    private final ObjectMapper objectMapper;

    public McpClientFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public McpClient create(McpServerProfile profile) {
        if (profile.transportType() == TransportType.HTTP_SSE) {
            return new SseMcpClient(objectMapper, profile);
        }
        if (profile.transportType() == TransportType.STREAMABLE_HTTP) {
            return new StreamableHttpMcpClient(objectMapper, profile);
        }
        if (profile.transportType() == TransportType.STDIO) {
            return new StdioMcpClient(objectMapper, profile);
        }
        throw new IllegalArgumentException("Unsupported transport type: " + profile.transportType());
    }
}
