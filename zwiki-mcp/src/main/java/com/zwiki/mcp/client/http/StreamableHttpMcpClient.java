package com.zwiki.mcp.client.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwiki.mcp.client.McpClient;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.tool.McpToolDescriptor;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class StreamableHttpMcpClient implements McpClient {

    private final ObjectMapper objectMapper;
    private final McpServerProfile profile;
    private final JsonRpcHttpClient jsonRpc;

    public StreamableHttpMcpClient(ObjectMapper objectMapper, McpServerProfile profile) {
        this.objectMapper = objectMapper;
        this.profile = profile;

        ProxySelector proxySelector = null;
        if (profile.proxyHost() != null && profile.proxyPort() != null) {
            proxySelector = ProxySelector.of(new InetSocketAddress(profile.proxyHost(), profile.proxyPort()));
        }

        Duration timeout = profile.timeout() != null ? profile.timeout() : Duration.ofSeconds(300);
        this.jsonRpc = new JsonRpcHttpClient(objectMapper, URI.create(profile.url()), timeout, profile.headers(), proxySelector);
    }

    @Override
    public void initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2025-03-26");

        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "zwiki");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);

        params.set("capabilities", objectMapper.createObjectNode());

        jsonRpc.sendRequest("initialize", params);
        jsonRpc.sendNotification("notifications/initialized", null);
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        JsonNode result = jsonRpc.sendRequest("tools/list", objectMapper.createObjectNode());
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            return Collections.emptyList();
        }

        List<McpToolDescriptor> list = new ArrayList<>();
        Iterator<JsonNode> it = tools.elements();
        while (it.hasNext()) {
            JsonNode t = it.next();
            String name = t.hasNonNull("name") ? t.get("name").asText() : null;
            String desc = t.hasNonNull("description") ? t.get("description").asText() : "";
            JsonNode schema = t.get("inputSchema");
            if (name != null && schema != null) {
                list.add(new McpToolDescriptor(name, desc, schema));
            }
        }
        return list;
    }

    @Override
    public JsonNode callTool(String name, JsonNode arguments) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        if (arguments != null) {
            params.set("arguments", arguments);
        }
        else {
            params.set("arguments", objectMapper.createObjectNode());
        }
        return jsonRpc.sendRequest("tools/call", params);
    }
}
