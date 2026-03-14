package com.zwiki.mcp.client.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwiki.mcp.client.McpClient;
import com.zwiki.mcp.model.McpServerProfile;
import com.zwiki.mcp.tool.McpToolDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client implementing the legacy SSE transport protocol.
 *
 * SSE transport flow:
 * 1. Client opens GET to SSE URL → server sends 'endpoint' event with message URL
 * 2. Client POSTs JSON-RPC to message URL (server returns 2xx acknowledgment)
 * 3. Server sends JSON-RPC responses on the SSE stream (NOT on POST body)
 *
 * Auto-reconnects if the SSE connection dies between requests.
 */
public final class SseMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(SseMcpClient.class);

    private static final int MAX_429_RETRIES = 2;
    private static final long BASE_429_RETRY_DELAY_MS = 1000L;
    private static final long MAX_429_RETRY_DELAY_MS = 10_000L;

    private final ObjectMapper objectMapper;
    private final McpServerProfile profile;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final AtomicLong idGen = new AtomicLong(1);

    private volatile String messageEndpointUrl;
    private volatile Thread sseReaderThread;
    private volatile boolean running;
    private volatile boolean sseConnected;
    private volatile CompletableFuture<String> endpointFuture = new CompletableFuture<>();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    public SseMcpClient(ObjectMapper objectMapper, McpServerProfile profile) {
        this.objectMapper = objectMapper;
        this.profile = profile;
        this.timeout = profile.timeout() != null ? profile.timeout() : Duration.ofSeconds(90);

        HttpClient.Builder builder = HttpClient.newBuilder();
        if (profile.proxyHost() != null && profile.proxyPort() != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(profile.proxyHost(), profile.proxyPort())));
        }
        this.httpClient = builder.build();
    }

    @Override
    public void initialize() {
        connectAndInit();
    }

    @Override
    public void close() {
        running = false;
        sseConnected = false;
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
        }
        // Fail all pending requests
        pendingRequests.forEach((id, f) -> {
            if (!f.isDone()) {
                f.completeExceptionally(new IllegalStateException("MCP client closed"));
            }
        });
        pendingRequests.clear();
        log.info("MCP SSE '{}': closed", profile.name());
    }

    private synchronized void connectAndInit() {
        // Stop old reader if still running
        running = false;
        sseConnected = false;
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
        }

        // Fail any pending requests from the old connection so they don't hang until timeout
        pendingRequests.forEach((id, f) -> {
            if (!f.isDone()) {
                log.warn("MCP SSE '{}': failing stale pending request id={} before reconnect", profile.name(), id);
                f.completeExceptionally(new IllegalStateException("MCP SSE reconnecting, old request discarded"));
            }
        });
        pendingRequests.clear();

        // Fresh endpoint future for this connection
        endpointFuture = new CompletableFuture<>();

        // Step 1: Start SSE reader thread
        running = true;
        sseReaderThread = new Thread(this::sseReaderLoop, "mcp-sse-" + profile.name());
        sseReaderThread.setDaemon(true);
        sseReaderThread.start();

        // Step 2: Wait for the endpoint URL to be discovered
        try {
            messageEndpointUrl = endpointFuture.get(30, TimeUnit.SECONDS);
            log.info("MCP SSE '{}': discovered message endpoint: {}", profile.name(), messageEndpointUrl);
        } catch (Exception e) {
            running = false;
            throw new IllegalStateException("Failed to discover MCP SSE endpoint from " + profile.url(), e);
        }

        // Step 3: Send initialize request
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2025-03-26");
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "zwiki");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);
        params.set("capabilities", objectMapper.createObjectNode());

        sendRequest("initialize", params);
        sendNotification("notifications/initialized", null);
        log.info("MCP SSE '{}': initialized successfully", profile.name());
    }

    /**
     * Ensures the SSE stream is alive before sending a request.
     * If the SSE reader thread has died (server idle timeout, network issue, etc.),
     * reconnects automatically.
     */
    private void ensureSseAlive() {
        boolean alive = sseReaderThread != null && sseReaderThread.isAlive() && sseConnected;
        if (alive) {
            return;
        }
        log.warn("MCP SSE '{}': SSE connection not alive (thread={}, connected={}), reconnecting...",
                profile.name(),
                sseReaderThread != null ? sseReaderThread.isAlive() : "null",
                sseConnected);
        connectAndInit();
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        ensureSseAlive();
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
        ensureSseAlive();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments != null ? arguments : objectMapper.createObjectNode());
        return sendRequest("tools/call", params);
    }

    /**
     * Sends a JSON-RPC request via POST to the message endpoint and waits for
     * the response on the SSE stream.
     *
     * Per MCP SSE spec, the POST returns 2xx acknowledgment and the actual
     * JSON-RPC response comes as an SSE event on the stream.
     */
    private JsonNode sendRequest(String method, JsonNode params) {
        long id = idGen.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            if (params != null) {
                req.set("params", params);
            }
            log.info("MCP SSE '{}': sending {} (id={}) to {}", profile.name(), method, id, messageEndpointUrl);
            postToEndpoint(req, id);

            JsonNode response = future.get(timeout.getSeconds(), TimeUnit.SECONDS);
            if (response.hasNonNull("error")) {
                log.error("MCP SSE '{}': {} returned error: {}", profile.name(), method, response.get("error"));
                throw new IllegalStateException("MCP error: " + response.get("error"));
            }
            JsonNode result = response.has("result") ? response.get("result") : objectMapper.nullNode();
            log.info("MCP SSE '{}': {} completed, result length={}", profile.name(), method,
                    result != null ? result.toString().length() : 0);
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP SSE request '" + method + "' failed", e);
        } finally {
            pendingRequests.remove(id);
        }
    }

    private long computeRetryDelayMs(HttpResponse<?> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfter.get().trim());
                if (seconds > 0) {
                    return Math.min(seconds * 1000L, MAX_429_RETRY_DELAY_MS);
                }
            } catch (NumberFormatException ignored) {
                // ignore invalid retry-after value
            }
        }
        long delay = BASE_429_RETRY_DELAY_MS * (1L << Math.min(attempt, 6));
        return Math.min(delay, MAX_429_RETRY_DELAY_MS);
    }

    private void sendNotification(String method, JsonNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        try {
            postToEndpoint(req, -1);
        } catch (Exception ignored) {
            // notifications are fire-and-forget
        }
    }

    /**
     * POSTs a JSON-RPC message to the message endpoint.
     *
     * Per MCP SSE spec, the POST response is just an acknowledgment (typically 202).
     * The actual JSON-RPC response arrives on the SSE stream.
     * We log the POST response for diagnostics but do NOT complete the future from it.
     * If the POST body does contain a valid JSON-RPC response (non-standard behavior),
     * we store it as a fallback that the SSE handler can use.
     *
     * If the server returns a non-2xx status (e.g. 429 rate-limit), we immediately
     * fail the pending future instead of waiting for the SSE timeout.
     */
    private void postToEndpoint(JsonNode body, long requestId) {
        int attempt = 0;
        while (true) {
            try {
                Duration postTimeout = Duration.ofSeconds(Math.min(30, Math.max(1, timeout.toSeconds())));
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(messageEndpointUrl))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                        .timeout(postTimeout)
                        .header("Content-Type", "application/json");

                applyHeaders(builder);

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                String responseBody = response.body();
                if (requestId > 0) {
                    log.info("MCP SSE '{}': POST status={}, bodyLength={}",
                            profile.name(), status, responseBody != null ? responseBody.length() : 0);
                } else {
                    log.debug("MCP SSE '{}': POST status={}, bodyLength={}",
                            profile.name(), status, responseBody != null ? responseBody.length() : 0);
                }

                if (status == 429 && attempt < MAX_429_RETRIES) {
                    long delayMs = computeRetryDelayMs(response, attempt);
                    int nextAttempt = attempt + 1;
                    log.warn("MCP SSE '{}': POST rate-limited (429). Retrying in {}ms (attempt {}/{})", profile.name(),
                            delayMs, nextAttempt, MAX_429_RETRIES);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Retry interrupted while posting to MCP endpoint",
                                interruptedException);
                    }
                    attempt = nextAttempt;
                    continue;
                }

                // Non-2xx → immediately fail the pending request
                if (status < 200 || status >= 300) {
                    String errMsg = String.format("MCP server returned HTTP %d: %s", status,
                            responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 300)) : "");
                    log.error("MCP SSE '{}': {}", profile.name(), errMsg);
                    if (requestId > 0) {
                        CompletableFuture<JsonNode> future = pendingRequests.get(requestId);
                        if (future != null && !future.isDone()) {
                            future.completeExceptionally(new IllegalStateException(errMsg));
                        }
                    }
                    return;
                }

                if (responseBody != null && !responseBody.isBlank()) {
                    log.debug("MCP SSE '{}': POST response body: {}", profile.name(),
                            responseBody.substring(0, Math.min(responseBody.length(), 500)));

                    // Per MCP SSE spec, response should come on SSE stream.
                    // However, some servers return the JSON-RPC response directly on POST.
                    // We use it as a fallback: schedule it to complete ONLY if SSE hasn't
                    // delivered within 5 seconds.
                    if (requestId > 0) {
                        try {
                            JsonNode node = objectMapper.readTree(responseBody);
                            if (node.has("id") && node.has("result")) {
                                // Schedule fallback: if SSE doesn't deliver in 5s, use POST response
                                CompletableFuture<JsonNode> future = pendingRequests.get(requestId);
                                if (future != null && !future.isDone()) {
                                    final JsonNode fallbackNode = node;
                                    CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> {
                                        if (!future.isDone()) {
                                            log.warn("MCP SSE '{}': SSE stream did not deliver response for id={} within 5s, " +
                                                    "using POST response as fallback", profile.name(), requestId);
                                            future.complete(fallbackNode);
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            log.debug("MCP SSE '{}': POST response is not JSON: {}", profile.name(), e.getMessage());
                        }
                    }
                }
                return;
            } catch (java.net.http.HttpTimeoutException e) {
                Duration postTimeout = Duration.ofSeconds(Math.min(30, Math.max(1, timeout.toSeconds())));
                log.warn("MCP SSE '{}': POST timed out ({}s) to {}, will continue waiting SSE response for requestId={}",
                        profile.name(), postTimeout.toSeconds(), messageEndpointUrl, requestId);
                return;
            } catch (Exception e) {
                // If POST itself threw, also fail the future
                if (requestId > 0) {
                    CompletableFuture<JsonNode> future = pendingRequests.get(requestId);
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(e);
                    }
                }
                throw new IllegalStateException("Failed to POST to MCP endpoint " + messageEndpointUrl, e);
            }
        }
    }

    /**
     * Background SSE reader: GETs the SSE URL and continuously reads events.
     * - 'endpoint' event → resolves the message posting URL
     * - 'message' event → dispatches JSON-RPC response to pending request
     */
    private void sseReaderLoop() {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(profile.url()))
                    .GET()
                    .header("Accept", "text/event-stream");

            applyHeaders(reqBuilder);

            log.info("MCP SSE '{}': opening SSE connection to {}", profile.name(), profile.url());
            HttpResponse<InputStream> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            log.info("MCP SSE '{}': SSE connection established, status={}", profile.name(), status);

            // Fail fast on non-2xx — no point reading a stream that will never send an endpoint event
            if (status < 200 || status >= 300) {
                String errMsg = String.format("MCP SSE '%s': server returned HTTP %d on SSE connection to %s",
                        profile.name(), status, profile.url());
                log.error(errMsg);
                endpointFuture.completeExceptionally(new IllegalStateException(errMsg));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                sseConnected = true;
                String line;
                String eventName = null;
                StringBuilder dataBuf = new StringBuilder();

                while (running && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        dataBuf.append(line.substring("data:".length()).trim());
                        continue;
                    }
                    // empty line = end of event
                    if (line.isEmpty() && dataBuf.length() > 0) {
                        String data = dataBuf.toString();
                        dataBuf.setLength(0);
                        handleSseEvent(eventName, data);
                        eventName = null;
                    }
                }
            }
            log.warn("MCP SSE '{}': SSE stream ended normally (server closed connection)", profile.name());
            // Fail all pending requests — SSE stream is gone so responses will never arrive
            pendingRequests.forEach((id, f) -> {
                if (!f.isDone()) {
                    log.warn("MCP SSE '{}': failing pending request id={} due to SSE stream close", profile.name(), id);
                    f.completeExceptionally(new IllegalStateException(
                            "MCP SSE connection closed by server while request was pending"));
                }
            });
        } catch (Exception e) {
            if (running) {
                log.warn("MCP SSE '{}': SSE reader error: {}", profile.name(), e.getMessage());
                endpointFuture.completeExceptionally(
                        new IllegalStateException("SSE connection to " + profile.url() + " failed", e));
                // Fail all pending requests
                pendingRequests.forEach((id, f) -> {
                    if (!f.isDone()) {
                        f.completeExceptionally(new IllegalStateException("SSE connection lost"));
                    }
                });
            }
        } finally {
            sseConnected = false;
            log.info("MCP SSE '{}': SSE reader thread exiting (connected was={})", profile.name(), running);
        }
    }

    private void handleSseEvent(String eventName, String data) {
        if (data == null || data.isBlank()) return;

        if ("endpoint".equals(eventName)) {
            String resolved = resolveEndpointUrl(data);
            log.info("MCP SSE '{}': received endpoint event: {}", profile.name(), resolved);
            endpointFuture.complete(resolved);
            return;
        }

        // 'message' event or unnamed event — try to parse as JSON-RPC
        try {
            JsonNode node = objectMapper.readTree(data);

            // Handle server-initiated ping requests → respond with pong
            if (node.has("method") && "ping".equals(node.get("method").asText())) {
                log.debug("MCP SSE '{}': received ping, sending pong", profile.name());
                if (node.has("id")) {
                    sendPong(node.get("id"));
                }
                return;
            }

            // Log non-ping events at INFO
            log.info("MCP SSE '{}': SSE event='{}', data length={}, preview={}",
                    profile.name(), eventName, data.length(),
                    data.substring(0, Math.min(data.length(), 300)));

            if (node.has("id")) {
                long id = node.get("id").asLong(-1);
                if (id > 0) {
                    CompletableFuture<JsonNode> future = pendingRequests.get(id);
                    if (future != null && !future.isDone()) {
                        log.info("MCP SSE '{}': completing request id={} from SSE stream", profile.name(), id);
                        future.complete(node);
                    } else {
                        log.debug("MCP SSE '{}': SSE response for id={} (no pending future)",
                                profile.name(), id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP SSE '{}': failed to parse SSE data as JSON: {}", profile.name(), e.getMessage());
        }
    }

    /**
     * Responds to a server ping with a JSON-RPC result (pong) via POST.
     */
    private void sendPong(JsonNode pingId) {
        try {
            ObjectNode pong = objectMapper.createObjectNode();
            pong.put("jsonrpc", "2.0");
            pong.set("id", pingId);
            pong.set("result", objectMapper.createObjectNode());
            postToEndpoint(pong, -1);
        } catch (Exception e) {
            log.debug("MCP SSE '{}': failed to send pong: {}", profile.name(), e.getMessage());
        }
    }

    private String resolveEndpointUrl(String endpointPath) {
        if (endpointPath.startsWith("http://") || endpointPath.startsWith("https://")) {
            return endpointPath;
        }
        URI baseUri = URI.create(profile.url());
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        int port = baseUri.getPort();
        String base = scheme + "://" + host + (port > 0 ? ":" + port : "");
        if (endpointPath.startsWith("/")) {
            return base + endpointPath;
        }
        // relative path — resolve against base path
        String basePath = baseUri.getPath();
        int lastSlash = basePath.lastIndexOf('/');
        return base + basePath.substring(0, lastSlash + 1) + endpointPath;
    }

    public boolean isConnected() {
        return sseConnected && sseReaderThread != null && sseReaderThread.isAlive();
    }

    private void applyHeaders(HttpRequest.Builder builder) {
        Map<String, String> headers = profile.headers();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) {
                    builder.header(k, v);
                }
            });
        }
    }
}
