package com.zwiki.mcp.client.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwiki.mcp.client.McpClient;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.tool.McpToolDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class StdioMcpClient implements McpClient {

    // 初始化超时时间（秒），npx 首次运行可能需要下载包
    private static final long INIT_TIMEOUT_SECS = 60;

    private final ObjectMapper objectMapper;
    private final McpServerProfile profile;
    private final AtomicLong idGen = new AtomicLong(1);

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public StdioMcpClient(ObjectMapper objectMapper, McpServerProfile profile) {
        this.objectMapper = objectMapper;
        this.profile = profile;
    }

    @Override
    public void initialize() {
        try {
            startProcess();
            startReaderThread();

            // Send initialize request
            log.info("  正在发送 initialize 请求到 MCP 服务器 '{}'...", profile.name());
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", "2024-11-05");
            ObjectNode clientInfo = objectMapper.createObjectNode();
            clientInfo.put("name", "zwiki");
            clientInfo.put("version", "1.0");
            params.set("clientInfo", clientInfo);
            params.set("capabilities", objectMapper.createObjectNode());

            JsonNode initResult = sendRequest("initialize", params, INIT_TIMEOUT_SECS);
            log.info("  initialize 响应: {}", initResult != null ? initResult.toString().substring(0, Math.min(200, initResult.toString().length())) : "null");
            
            sendNotification("notifications/initialized", null);

            log.info("✅ Stdio MCP client '{}' initialized successfully", profile.name());
        } catch (Exception e) {
            close();
            throw new IllegalStateException("Failed to initialize stdio MCP client '" + profile.name() + "': " + e.getMessage(), e);
        }
    }

    private void startProcess() throws IOException {
        List<String> cmd = new ArrayList<>();
        String command = profile.command();
        
        // Windows 特殊处理：npx 需要使用 npx.cmd
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows && "npx".equalsIgnoreCase(command)) {
            command = "npx.cmd";
            log.debug("Windows环境，将 npx 转换为 npx.cmd");
        }
        
        cmd.add(command);
        
        // 如果是 npx 命令，添加 --yes 标志以自动确认包安装
        if ("npx".equalsIgnoreCase(profile.command()) || "npx.cmd".equalsIgnoreCase(command)) {
            cmd.add("--yes");
            log.debug("为 npx 添加 --yes 标志以自动确认包安装");
        }
        
        if (profile.args() != null) {
            cmd.addAll(profile.args());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        // Merge environment variables
        if (profile.env() != null && !profile.env().isEmpty()) {
            pb.environment().putAll(profile.env());
        }

        // 阻止 STDIO MCP 工具（如 @drawio/mcp）自动打开浏览器
        pb.environment().putIfAbsent("BROWSER", "echo");
        pb.environment().putIfAbsent("NO_OPEN", "1");

        log.info("Starting stdio MCP server '{}': {}", profile.name(), String.join(" ", cmd));
        log.info("  工作目录: {}", pb.directory() != null ? pb.directory().getAbsolutePath() : System.getProperty("user.dir"));
        try {
            process = pb.start();
            log.info("  进程已启动, PID: {}", process.pid());
        } catch (IOException e) {
            log.error("❌ 无法启动STDIO MCP进程 '{}': {}", profile.name(), e.getMessage());
            log.error("  请确保 {} 命令可用，可尝试手动运行: {} {}", 
                profile.command(), profile.command(), String.join(" ", profile.args() != null ? profile.args() : List.of()));
            throw e;
        }
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // Consume stderr in background to prevent blocking
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    // 使用 WARN 级别以便在日志中看到 MCP 进程的错误信息
                    log.warn("[stdio-mcp-stderr:{}] {}", profile.name(), line);
                }
            } catch (IOException ignored) {
            }
        }, "stdio-mcp-stderr-" + profile.name());
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode response = objectMapper.readTree(line);
                        if (response.has("id") && !response.get("id").isNull()) {
                            long id = response.get("id").asLong();
                            CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                            if (future != null) {
                                future.complete(response);
                            }
                        }
                        // Notifications from server are ignored
                    } catch (Exception e) {
                        log.debug("[stdio-mcp-reader:{}] Failed to parse line: {}", profile.name(), line);
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    log.warn("Stdio MCP reader thread for '{}' terminated: {}", profile.name(), e.getMessage());
                }
            }
        }, "stdio-mcp-reader-" + profile.name());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private JsonNode sendRequest(String method, JsonNode params) {
        long timeoutSecs = profile.timeout() != null ? profile.timeout().getSeconds() : 300;
        return sendRequest(method, params, timeoutSecs);
    }

    private JsonNode sendRequest(String method, JsonNode params, long timeoutSecs) {
        long id = idGen.getAndIncrement();
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            String json = objectMapper.writeValueAsString(req);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (Exception e) {
            pendingRequests.remove(id);
            throw new IllegalStateException("Failed to send request to stdio MCP server '" + profile.name() + "'", e);
        }

        try {
            JsonNode response = future.get(timeoutSecs, TimeUnit.SECONDS);
            if (response.hasNonNull("error")) {
                throw new IllegalStateException("MCP error: " + response.get("error").toString());
            }
            return response.has("result") ? response.get("result") : objectMapper.nullNode();
        } catch (Exception e) {
            pendingRequests.remove(id);
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("Timeout (" + timeoutSecs + "s) or error waiting for stdio MCP response from '" + profile.name() + "': " + e.getMessage(), e);
        }
    }

    private void sendNotification(String method, JsonNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        try {
            String json = objectMapper.writeValueAsString(req);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (Exception e) {
            log.warn("Failed to send notification to stdio MCP server '{}': {}", profile.name(), e.getMessage());
        }
    }

    /**
     * Check if the underlying STDIO process is still alive.
     */
    public boolean isAlive() {
        return process != null && process.isAlive() && !closed;
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        JsonNode result = sendRequest("tools/list", objectMapper.createObjectNode());
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
        } else {
            params.set("arguments", objectMapper.createObjectNode());
        }
        return sendRequest("tools/call", params);
    }

    @Override
    public void close() {
        closed = true;
        // Cancel all pending requests
        pendingRequests.forEach((id, future) -> future.cancel(true));
        pendingRequests.clear();

        if (writer != null) {
            try { writer.close(); } catch (Exception ignored) {}
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                process.destroyForcibly();
            }
            log.info("Stdio MCP server '{}' process terminated", profile.name());
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
