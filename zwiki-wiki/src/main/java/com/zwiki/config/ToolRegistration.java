package com.zwiki.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.util.FileSystemTool;
import com.zwiki.service.mcp.McpToolCallbackProvider;
// import com.zwiki.llm.tool.TerminalTool;  // 暂时禁用，Wiki文档生成不需要命令行工具
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author pai
 * @description: Tool注册配置
 * @date 2026/1/18 00:26
 */
@Component
@Slf4j
public class ToolRegistration {

    // Moonshot/Kimi API requires: start with letter, only letters/numbers/underscores/dashes
    private static final Pattern VALID_TOOL_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]*$");

    private final ObjectMapper objectMapper;
    private final FileSystemTool fileSystemTool;
    private final McpToolCallbackProvider mcpToolCallbackProvider;

    private ToolCallback[] localTools;

    public ToolRegistration(ObjectMapper objectMapper,
                            FileSystemTool fileSystemTool,
                            McpToolCallbackProvider mcpToolCallbackProvider) {
        this.objectMapper = objectMapper;
        this.fileSystemTool = fileSystemTool;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    @PostConstruct
    public void init() {
        this.localTools = new ToolCallback[] {
                buildReadFileTool(objectMapper, fileSystemTool),
                buildReadFileLinesTool(objectMapper, fileSystemTool),
                buildSearchContentTool(objectMapper, fileSystemTool),
                buildListTreeTool(objectMapper, fileSystemTool)
        };
    }

    public ToolCallback[] getMcpTools() {
        ToolCallback[] mcpTools = mcpToolCallbackProvider.getToolCallbacks();
        List<ToolCallback> result = new ArrayList<>();
        Set<String> seenToolNames = new HashSet<>();
        for (ToolCallback t : mcpTools) {
            addIfValid(result, t, seenToolNames);
        }
        return result.toArray(new ToolCallback[0]);
    }

    public boolean hasToolNamed(String name) {
        ToolCallback[] all = getAllTools();
        for (ToolCallback t : all) {
            try {
                ToolDefinition def = t.getToolDefinition();
                if (def != null && name.equals(def.name())) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public ToolCallback[] getAllTools() {
        ToolCallback[] mcpTools = mcpToolCallbackProvider.getToolCallbacks();

        List<ToolCallback> all = new ArrayList<>();
        Set<String> seenToolNames = new HashSet<>();
        for (ToolCallback t : localTools) {
            addIfValid(all, t, seenToolNames);
        }
        for (ToolCallback t : mcpTools) {
            addIfValid(all, t, seenToolNames);
        }
        return all.toArray(new ToolCallback[0]);
    }

    private void addIfValid(List<ToolCallback> all, ToolCallback toolCallback, Set<String> seenToolNames) {
        if (toolCallback == null) {
            return;
        }
        ToolDefinition definition = null;
        try {
            definition = toolCallback.getToolDefinition();
        }
        catch (Exception ignored) {
        }

        String toolName = null;
        try {
            toolName = definition != null ? definition.name() : null;
        }
        catch (Exception ignored) {
        }

        if (toolName == null || toolName.isBlank()) {
            log.warn("Skipping ToolCallback with blank tool name: {}", toolCallback.getClass().getName());
            return;
        }

        // Validate tool name for LLM API compatibility (e.g., Moonshot requires specific format)
        if (!isValidToolName(toolName)) {
            log.warn("Skipping ToolCallback with invalid name '{}' (must start with letter, contain only letters/numbers/underscores/dashes): {}",
                    toolName, toolCallback.getClass().getName());
            return;
        }

        if (seenToolNames != null && !seenToolNames.add(toolName)) {
            log.warn("Skipping duplicate ToolCallback name '{}': {}", toolName, toolCallback.getClass().getName());
            return;
        }

        all.add(toolCallback);
    }

    /**
     * Check if tool name is valid for LLM APIs (Moonshot/Kimi requirement):
     * Must start with a letter, can contain letters, numbers, underscores, and dashes.
     */
    private boolean isValidToolName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return VALID_TOOL_NAME.matcher(name).matches();
    }

    private ToolCallback buildReadFileTool(ObjectMapper objectMapper, FileSystemTool fileSystemTool) {
        String schemaJson = "{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"filePath\":{\"type\":\"string\",\"description\":\"file path\"}" +
                "}," +
                "\"required\":[\"filePath\"]" +
                "}";

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("readFile")
                .description("Read the content of a file, or list directory contents if given a directory path. Supports relative paths resolved against the project root.")
                .inputSchema(schemaJson)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    JsonNode args = (toolInput == null || toolInput.isBlank())
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(toolInput);

                    JsonNode filePathNode = args.get("filePath");
                    String filePath = filePathNode != null ? filePathNode.asText(null) : null;
                    if (filePath == null || filePath.isBlank()) {
                        return "错误：readFile 参数缺失或为空：filePath";
                    }

                    return fileSystemTool.readFile(filePath);
                }
                catch (Exception e) {
                    return "错误：readFile 调用失败: " + e.getMessage();
                }
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private ToolCallback buildSearchContentTool(ObjectMapper objectMapper, FileSystemTool fileSystemTool) {
        String schemaJson = "{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"keyword\":{\"type\":\"string\",\"description\":\"keyword to search for in file contents\"}," +
                "\"directory\":{\"type\":\"string\",\"description\":\"subdirectory to search in (relative to project root), leave empty for whole project\"}," +
                "\"fileExtension\":{\"type\":\"string\",\"description\":\"file extension filter (e.g. java, xml, py), leave empty for all text files\"}" +
                "}," +
                "\"required\":[\"keyword\"]" +
                "}";

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("searchContent")
                .description("Search for a keyword across all project files (like grep). Returns matching file paths with line numbers and content. Use this to find relevant code files before reading them. Supports case-insensitive search.")
                .inputSchema(schemaJson)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    JsonNode args = (toolInput == null || toolInput.isBlank())
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(toolInput);

                    JsonNode keywordNode = args.get("keyword");
                    String keyword = keywordNode != null ? keywordNode.asText(null) : null;
                    if (keyword == null || keyword.isBlank()) {
                        return "错误：searchContent 参数缺失或为空：keyword";
                    }

                    JsonNode dirNode = args.get("directory");
                    String directory = dirNode != null ? dirNode.asText("") : "";

                    JsonNode extNode = args.get("fileExtension");
                    String fileExtension = extNode != null ? extNode.asText("") : "";

                    return fileSystemTool.searchContent(keyword, directory, fileExtension);
                }
                catch (Exception e) {
                    return "错误：searchContent 调用失败: " + e.getMessage();
                }
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private ToolCallback buildListTreeTool(ObjectMapper objectMapper, FileSystemTool fileSystemTool) {
        String schemaJson = "{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"directory\":{\"type\":\"string\",\"description\":\"directory path relative to project root, leave empty for project root\"}," +
                "\"maxDepth\":{\"type\":\"integer\",\"description\":\"max recursion depth (1-5), default 3\"}" +
                "}," +
                "\"required\":[]" +
                "}";

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("listTree")
                .description("List the project directory tree recursively up to a specified depth. Use this to understand the project structure and find relevant directories and files.")
                .inputSchema(schemaJson)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    JsonNode args = (toolInput == null || toolInput.isBlank())
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(toolInput);

                    JsonNode dirNode = args.get("directory");
                    String directory = dirNode != null ? dirNode.asText("") : "";

                    JsonNode depthNode = args.get("maxDepth");
                    int maxDepth = (depthNode != null && depthNode.canConvertToInt()) ? depthNode.asInt(3) : 3;

                    return fileSystemTool.listTree(directory, maxDepth);
                }
                catch (Exception e) {
                    return "错误：listTree 调用失败: " + e.getMessage();
                }
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private ToolCallback buildReadFileLinesTool(ObjectMapper objectMapper, FileSystemTool fileSystemTool) {
        String schemaJson = "{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"filePath\":{\"type\":\"string\",\"description\":\"file path\"}," +
                "\"startLine\":{\"type\":\"integer\",\"description\":\"start line\"}," +
                "\"endLine\":{\"type\":\"integer\",\"description\":\"end line\"}" +
                "}," +
                "\"required\":[\"filePath\",\"startLine\",\"endLine\"]" +
                "}";

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("readFileLines")
                .description("Read a specific line range of a file. Line numbers are 1-indexed. Supports relative paths resolved against the project root.")
                .inputSchema(schemaJson)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    JsonNode args = (toolInput == null || toolInput.isBlank())
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(toolInput);

                    JsonNode filePathNode = args.get("filePath");
                    String filePath = filePathNode != null ? filePathNode.asText(null) : null;
                    if (filePath == null || filePath.isBlank()) {
                        return "错误：readFileLines 参数缺失或为空：filePath";
                    }

                    JsonNode startLineNode = args.get("startLine");
                    JsonNode endLineNode = args.get("endLine");
                    if (startLineNode == null || !startLineNode.canConvertToInt()) {
                        return "错误：readFileLines 参数缺失或非法：startLine";
                    }
                    if (endLineNode == null || !endLineNode.canConvertToInt()) {
                        return "错误：readFileLines 参数缺失或非法：endLine";
                    }

                    return fileSystemTool.readFileLines(filePath, startLineNode.asInt(), endLineNode.asInt());
                }
                catch (Exception e) {
                    return "错误：readFileLines 调用失败: " + e.getMessage();
                }
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }
}
