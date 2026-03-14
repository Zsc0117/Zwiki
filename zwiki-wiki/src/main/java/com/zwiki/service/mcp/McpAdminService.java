package com.zwiki.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.mcp.client.McpClient;
import com.zwiki.mcp.client.McpClientFactory;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.tool.McpToolDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class McpAdminService {

    private final McpJsonConfigProvider configProvider;
    private final McpClientFactory clientFactory;

    public McpAdminService(McpJsonConfigProvider configProvider, ObjectMapper objectMapper) {
        this.configProvider = configProvider;
        this.clientFactory = new McpClientFactory(objectMapper);
    }

    public List<McpServerInfo> listServers() {
        return configProvider.getAllServers().stream()
                .map(p -> new McpServerInfo(
                        p.name(),
                        p.url(),
                        p.transportType().name(),
                        configProvider.isEnabled(p.name())))
                .toList();
    }

    public McpTestResult testConnection(String serverName) {
        McpServerProfile profile = configProvider.getEnabledServers().stream()
                .filter(p -> p.name().equals(serverName))
                .findFirst()
                .orElse(null);

        if (profile == null) {
            profile = configProvider.getAllServers().stream()
                    .filter(p -> p.name().equals(serverName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Server not found in mcp.json: " + serverName));
        }

        McpClient client = clientFactory.create(profile);
        client.initialize();
        List<McpToolDescriptor> tools = client.listTools();
        return new McpTestResult(tools.size(),
                tools.stream().limit(20).map(McpToolDescriptor::name).toList());
    }

    public List<McpToolInfo> listTools(String serverName) {
        McpServerProfile profile = configProvider.getAllServers().stream()
                .filter(p -> p.name().equals(serverName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Server not found in mcp.json: " + serverName));

        McpClient client = clientFactory.create(profile);
        client.initialize();
        List<McpToolDescriptor> tools = client.listTools();
        return tools.stream()
                .map(t -> new McpToolInfo(t.name(), t.description()))
                .toList();
    }

    public record McpServerInfo(String name, String url, String transport, boolean enabled) {}
    public record McpToolInfo(String name, String description) {}
    public record McpTestResult(int toolCount, List<String> sampleTools) {}
}
