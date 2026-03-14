package com.zwiki.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwiki.mcp.tool.McpToolDescriptor;

import java.util.List;

public interface McpClient {

    void initialize();

    List<McpToolDescriptor> listTools();

    JsonNode callTool(String name, JsonNode arguments);

    /** Check if the client connection is still alive. HTTP clients return true by default. */
    default boolean isAlive() { return true; }

    default void close() {}
}
