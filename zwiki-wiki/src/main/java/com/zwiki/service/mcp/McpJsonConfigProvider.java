package com.zwiki.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.model.ProxyMode;
import com.zwiki.mcp.model.TransportType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author pai
 * @description: MCP JSON配置提供者，从mcp.json加载MCP服务器配置
 * @date 2026/1/25
 */
@Component
@Slf4j
public class McpJsonConfigProvider {

    private final ObjectMapper objectMapper;

    public McpJsonConfigProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<McpServerProfile> getEnabledServers() {
        File mcpJson = resolveMcpJson();
        if (mcpJson == null || !mcpJson.exists()) {
            log.info("mcp.json not found, no MCP servers configured");
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(mcpJson);
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || !serversNode.isObject() || serversNode.isEmpty()) {
                return Collections.emptyList();
            }

            List<McpServerProfile> profiles = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode cfg = entry.getValue();

                boolean enabled = parseEnabled(cfg);
                if (!enabled) {
                    log.debug("MCP server '{}' is disabled, skipping", name);
                    continue;
                }

                TransportType transport = parseTransport(cfg);
                String url = parseUrl(cfg);
                String command = parseCommand(cfg);

                if (transport == TransportType.STDIO) {
                    if (command == null || command.isBlank()) {
                        log.warn("MCP server '{}' is stdio but has no command, skipping", name);
                        continue;
                    }
                } else {
                    if (url == null || url.isBlank()) {
                        log.warn("MCP server '{}' has no url, skipping", name);
                        continue;
                    }
                }

                Map<String, String> headers = parseHeaders(cfg);
                Duration timeout = Duration.ofSeconds(
                        cfg.hasNonNull("timeout") ? cfg.get("timeout").asInt(300) : 300);
                List<String> args = parseArgs(cfg);
                Map<String, String> env = parseEnv(cfg);

                profiles.add(new McpServerProfile(name, url, transport, headers,
                        ProxyMode.DISABLED, null, null, timeout, command, args, env));
            }

            log.info("Loaded {} enabled MCP server(s) from mcp.json", profiles.size());
            return profiles;
        } catch (Exception e) {
            log.error("Failed to parse mcp.json: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public List<McpServerProfile> getAllServers() {
        File mcpJson = resolveMcpJson();
        if (mcpJson == null || !mcpJson.exists()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(mcpJson);
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || !serversNode.isObject() || serversNode.isEmpty()) {
                return Collections.emptyList();
            }

            List<McpServerProfile> profiles = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode cfg = entry.getValue();

                String url = parseUrl(cfg);
                if (url == null) url = "";
                TransportType transport = parseTransport(cfg);
                Map<String, String> headers = parseHeaders(cfg);
                Duration timeout = Duration.ofSeconds(
                        cfg.hasNonNull("timeout") ? cfg.get("timeout").asInt(300) : 300);
                String command = parseCommand(cfg);
                List<String> args = parseArgs(cfg);
                Map<String, String> env = parseEnv(cfg);

                profiles.add(new McpServerProfile(name, url, transport, headers,
                        ProxyMode.DISABLED, null, null, timeout, command, args, env));
            }
            return profiles;
        } catch (Exception e) {
            log.error("Failed to parse mcp.json: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public String readRawJson() {
        File mcpJson = resolveMcpJson();
        if (mcpJson == null || !mcpJson.exists()) {
            return "{\n  \"mcpServers\": {}\n}";
        }
        try {
            return Files.readString(mcpJson.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read mcp.json: {}", e.getMessage(), e);
            return "{\n  \"mcpServers\": {}\n}";
        }
    }

    public void writeRawJson(String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.has("mcpServers")) {
                throw new IllegalArgumentException("JSON must contain \"mcpServers\" key");
            }
            // Auto-correct and normalize the JSON structure
            JsonNode normalized = normalizeConfig(parsed);
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
            File mcpJson = resolveMcpJsonForWrite();
            Files.writeString(mcpJson.toPath(), pretty, StandardCharsets.UTF_8);
            log.info("mcp.json saved successfully: {}", mcpJson.getAbsolutePath());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save mcp.json: " + e.getMessage(), e);
        }
    }

    /**
     * Normalize and auto-correct mcp.json configuration.
     * - Standardizes field names (url→baseUrl, transport→type, enabled→isActive)
     * - Adds default values for missing fields
     * - Ensures proper structure for each server config
     */
    private JsonNode normalizeConfig(JsonNode root) {
        com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
        JsonNode serversNode = root.get("mcpServers");
        
        if (serversNode == null || !serversNode.isObject()) {
            result.set("mcpServers", objectMapper.createObjectNode());
            return result;
        }

        com.fasterxml.jackson.databind.node.ObjectNode normalizedServers = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
        
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String serverName = entry.getKey();
            JsonNode cfg = entry.getValue();
            
            if (!cfg.isObject()) {
                log.warn("Skipping invalid server config for '{}': not an object", serverName);
                continue;
            }

            com.fasterxml.jackson.databind.node.ObjectNode normalized = normalizeServerConfig(serverName, cfg);
            normalizedServers.set(serverName, normalized);
        }
        
        result.set("mcpServers", normalizedServers);
        return result;
    }

    /**
     * Normalize a single server configuration.
     */
    private com.fasterxml.jackson.databind.node.ObjectNode normalizeServerConfig(String serverName, JsonNode cfg) {
        com.fasterxml.jackson.databind.node.ObjectNode normalized = objectMapper.createObjectNode();
        
        // 1. Determine transport type first (affects required fields)
        String transportType = determineTransportType(cfg);
        normalized.put("type", transportType);
        
        // 2. Copy/normalize description
        if (cfg.hasNonNull("description")) {
            normalized.put("description", cfg.get("description").asText());
        }
        
        // 3. Normalize enabled/isActive → isActive
        boolean isActive = true;
        if (cfg.hasNonNull("isActive")) {
            isActive = cfg.get("isActive").asBoolean(true);
        } else if (cfg.hasNonNull("enabled")) {
            isActive = cfg.get("enabled").asBoolean(true);
        }
        normalized.put("isActive", isActive);
        
        // 4. Copy/normalize name (display name)
        if (cfg.hasNonNull("name")) {
            normalized.put("name", cfg.get("name").asText());
        } else {
            normalized.put("name", serverName);
        }
        
        // 5. Handle transport-specific fields
        if ("stdio".equalsIgnoreCase(transportType)) {
            // STDIO transport: command + args + env
            if (cfg.hasNonNull("command")) {
                normalized.put("command", cfg.get("command").asText());
            }
            if (cfg.hasNonNull("args") && cfg.get("args").isArray()) {
                normalized.set("args", cfg.get("args"));
            } else {
                normalized.set("args", objectMapper.createArrayNode());
            }
            if (cfg.hasNonNull("env") && cfg.get("env").isObject()) {
                normalized.set("env", cfg.get("env"));
            }
        } else {
            // HTTP-based transport: baseUrl + headers
            String url = null;
            if (cfg.hasNonNull("baseUrl")) {
                url = cfg.get("baseUrl").asText();
            } else if (cfg.hasNonNull("url")) {
                url = cfg.get("url").asText();
            }
            if (url != null && !url.isBlank()) {
                normalized.put("baseUrl", url);
            }
            
            // Copy headers
            if (cfg.hasNonNull("headers") && cfg.get("headers").isObject()) {
                normalized.set("headers", cfg.get("headers"));
            }
        }
        
        // 6. Copy timeout if present
        if (cfg.hasNonNull("timeout")) {
            normalized.put("timeout", cfg.get("timeout").asInt(300));
        }
        
        return normalized;
    }

    /**
     * Determine the transport type from config, with auto-detection.
     */
    private String determineTransportType(JsonNode cfg) {
        // Check explicit type/transport field
        String explicit = null;
        if (cfg.hasNonNull("type")) {
            explicit = cfg.get("type").asText();
        } else if (cfg.hasNonNull("transport")) {
            explicit = cfg.get("transport").asText();
        }
        
        if (explicit != null && !explicit.isBlank()) {
            // Normalize type names
            String lower = explicit.toLowerCase();
            if (lower.contains("sse") || "httpsse".equals(lower) || "http_sse".equals(lower)) {
                return "sse";
            }
            if (lower.contains("stdio")) {
                return "stdio";
            }
            if (lower.contains("streamable") || lower.contains("http")) {
                return "streamableHttp";
            }
            return explicit;
        }
        
        // Auto-detect: if 'command' field exists, it's stdio
        if (cfg.hasNonNull("command")) {
            return "stdio";
        }
        
        // Default to streamableHttp for HTTP-based
        return "streamableHttp";
    }

    public boolean isEnabled(String serverName) {
        File mcpJson = resolveMcpJson();
        if (mcpJson == null || !mcpJson.exists()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(mcpJson);
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || !serversNode.has(serverName)) {
                return false;
            }
            return parseEnabled(serversNode.get(serverName));
        } catch (Exception e) {
            return false;
        }
    }

    private String parseUrl(JsonNode cfg) {
        if (cfg.hasNonNull("url")) {
            return cfg.get("url").asText(null);
        }
        if (cfg.hasNonNull("baseUrl")) {
            return cfg.get("baseUrl").asText(null);
        }
        return null;
    }

    private boolean parseEnabled(JsonNode cfg) {
        if (cfg.hasNonNull("enabled")) {
            return cfg.get("enabled").asBoolean(true);
        }
        if (cfg.hasNonNull("isActive")) {
            return cfg.get("isActive").asBoolean(true);
        }
        return true;
    }

    private File resolveMcpJsonForWrite() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            throw new IllegalStateException("Cannot determine working directory");
        }
        File f = new File(userDir, "mcp.json");
        if (f.exists()) {
            return f;
        }
        File parent = new File(userDir).getParentFile();
        if (parent != null) {
            File pf = new File(parent, "mcp.json");
            if (pf.exists()) {
                return pf;
            }
        }
        return f;
    }

    private File resolveMcpJson() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            return null;
        }
        File f = new File(userDir, "mcp.json");
        if (f.exists()) {
            return f;
        }
        // try parent directory (in case running from a submodule)
        File parent = new File(userDir).getParentFile();
        if (parent != null) {
            File pf = new File(parent, "mcp.json");
            if (pf.exists()) {
                return pf;
            }
        }
        return f;
    }

    private TransportType parseTransport(JsonNode cfg) {
        String t = null;
        if (cfg.hasNonNull("transport")) {
            t = cfg.get("transport").asText(null);
        } else if (cfg.hasNonNull("type")) {
            t = cfg.get("type").asText(null);
        }
        if (t == null || t.isBlank()) {
            // Auto-detect: if 'command' field exists, it's stdio
            if (cfg.hasNonNull("command")) {
                return TransportType.STDIO;
            }
            return TransportType.STREAMABLE_HTTP;
        }
        if ("httpSse".equalsIgnoreCase(t) || "HTTP_SSE".equalsIgnoreCase(t)
                || "sse".equalsIgnoreCase(t)) {
            return TransportType.HTTP_SSE;
        }
        if ("stdio".equalsIgnoreCase(t) || "STDIO".equalsIgnoreCase(t)) {
            return TransportType.STDIO;
        }
        return TransportType.STREAMABLE_HTTP;
    }

    private String parseCommand(JsonNode cfg) {
        if (cfg.hasNonNull("command")) {
            return cfg.get("command").asText(null);
        }
        return null;
    }

    private List<String> parseArgs(JsonNode cfg) {
        if (!cfg.hasNonNull("args") || !cfg.get("args").isArray()) {
            return Collections.emptyList();
        }
        List<String> args = new ArrayList<>();
        for (JsonNode n : cfg.get("args")) {
            args.add(n.asText());
        }
        return args;
    }

    private Map<String, String> parseEnv(JsonNode cfg) {
        if (!cfg.hasNonNull("env") || !cfg.get("env").isObject()) {
            return Collections.emptyMap();
        }
        Map<String, String> env = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = cfg.get("env").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            env.put(entry.getKey(), entry.getValue().asText());
        }
        return env;
    }

    private Map<String, String> parseHeaders(JsonNode cfg) {
        if (!cfg.hasNonNull("headers") || !cfg.get("headers").isObject()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = cfg.get("headers").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            headers.put(entry.getKey(), entry.getValue().asText());
        }
        return headers;
    }
}
