package com.zwiki.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.mcp.client.McpClient;
import com.zwiki.mcp.client.McpClientFactory;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.tool.McpToolDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author pai
 * @description: MCP工具回调提供者，管理MCP客户端和工具回调
 * @date 2026/1/26
 */
@Component
@Slf4j
public class McpToolCallbackProvider {

    private final McpJsonConfigProvider configProvider;
    private final McpClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    private volatile ToolCallback[] cachedCallbacks = new ToolCallback[0];
    private final List<McpClient> activeClients = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public McpToolCallbackProvider(McpJsonConfigProvider configProvider, ObjectMapper objectMapper) {
        this.configProvider = configProvider;
        this.objectMapper = objectMapper;
        this.clientFactory = new McpClientFactory(objectMapper);
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        lock.writeLock().lock();
        try {
            log.info("Reloading MCP tools from mcp.json ...");
            closeActiveClients();
            cachedCallbacks = loadToolCallbacks();
            log.info("MCP tools reloaded: {} tool(s) available", cachedCallbacks.length);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @PreDestroy
    public void destroy() {
        closeActiveClients();
    }

    private void closeActiveClients() {
        if (!activeClients.isEmpty()) {
            log.info("Closing {} active MCP client(s)", activeClients.size());
            for (McpClient client : activeClients) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("Error closing MCP client: {}", e.getMessage());
                }
            }
            activeClients.clear();
        }
    }

    public ToolCallback[] getToolCallbacks() {
        lock.readLock().lock();
        try {
            if (cachedCallbacks.length > 0) {
                // Check if any STDIO clients have died
                boolean anyDead = activeClients.stream().anyMatch(c -> !c.isAlive());
                if (!anyDead) {
                    return cachedCallbacks;
                }
                log.warn("Detected dead MCP client(s), triggering reload...");
            }
        } finally {
            lock.readLock().unlock();
        }

        // Cache is empty or has dead clients — check if there are enabled servers and try one reload
        if (!configProvider.getEnabledServers().isEmpty()) {
            log.info("MCP tools cache empty or stale, attempting reload...");
            reload();
        }

        lock.readLock().lock();
        try {
            return cachedCallbacks;
        } finally {
            lock.readLock().unlock();
        }
    }

    private ToolCallback[] loadToolCallbacks() {
        List<McpServerProfile> enabledServers = configProvider.getEnabledServers();
        if (enabledServers.isEmpty()) {
            return new ToolCallback[0];
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        for (McpServerProfile profile : enabledServers) {
            try {
                McpClient client = clientFactory.create(profile);
                client.initialize();
                activeClients.add(client);
                List<McpToolDescriptor> tools = client.listTools();
                List<String> toolNames = tools.stream().map(McpToolDescriptor::name).toList();
                log.info("MCP server '{}': discovered {} tool(s): {}", profile.name(), tools.size(), toolNames);

                for (McpToolDescriptor descriptor : tools) {
                    if (descriptor.name() == null || descriptor.name().isBlank()) {
                        log.warn("Skipping MCP tool with blank name from server '{}'", profile.name());
                        continue;
                    }
                    callbacks.add(new SpringAiMcpToolCallback(objectMapper, client, descriptor));
                }
            } catch (Exception e) {
                log.error("❌ Failed to connect to MCP server '{}' (type={}): {}", 
                    profile.name(), profile.transportType(), e.getMessage());
                log.error("  命令: {} {}", profile.command(), profile.args());
                log.debug("  详细错误:", e);
            }
        }

        return callbacks.toArray(new ToolCallback[0]);
    }
}
