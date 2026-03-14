package com.zwiki.mcpserver.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * MCP 工具注册中心。管理所有可用的 MCP 工具及其执行逻辑。
 */
@Slf4j
@Component
public class McpToolRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpToolDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, String>> handlers = new ConcurrentHashMap<>();

    public void register(String name, String description, Map<String, ParamDef> params,
                         Function<JsonNode, String> handler) {
        ObjectNode schema = buildInputSchema(params);
        definitions.put(name, McpToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build());
        handlers.put(name, handler);
        log.info("注册 MCP 工具: {}", name);
    }

    public List<McpToolDefinition> listTools() {
        return new ArrayList<>(definitions.values());
    }

    public boolean hasTool(String name) {
        return handlers.containsKey(name);
    }

    public String callTool(String name, JsonNode arguments) {
        Function<JsonNode, String> handler = handlers.get(name);
        if (handler == null) {
            return "错误：未知的工具 \"" + name + "\"";
        }
        try {
            return handler.apply(arguments);
        } catch (Exception e) {
            log.error("执行工具 {} 失败", name, e);
            return "工具执行失败：" + e.getMessage();
        }
    }

    private ObjectNode buildInputSchema(Map<String, ParamDef> params) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        for (Map.Entry<String, ParamDef> entry : params.entrySet()) {
            ObjectNode prop = objectMapper.createObjectNode();
            prop.put("type", "string");
            prop.put("description", entry.getValue().description());
            properties.set(entry.getKey(), prop);
            if (entry.getValue().required()) {
                required.add(entry.getKey());
            }
        }

        schema.set("properties", properties);
        if (required.size() > 0) {
            schema.set("required", required);
        }
        return schema;
    }

    public record ParamDef(String description, boolean required) {}
}
