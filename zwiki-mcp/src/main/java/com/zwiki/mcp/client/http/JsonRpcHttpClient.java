package com.zwiki.mcp.client.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class JsonRpcHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;
    private final Map<String, String> headers;

    private final AtomicLong idGen = new AtomicLong(1);
    private volatile String sessionId;

    public JsonRpcHttpClient(ObjectMapper objectMapper,
                             URI endpoint,
                             Duration timeout,
                             Map<String, String> headers,
                             ProxySelector proxySelector) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.headers = headers;

        HttpClient.Builder builder = HttpClient.newBuilder();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
    }

    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public JsonNode sendRequest(String method, JsonNode params) {
        long id = idGen.getAndIncrement();
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        JsonNode resp = post(req);
        if (resp.hasNonNull("error")) {
            throw new IllegalStateException("MCP error: " + resp.get("error").toString());
        }
        if (!resp.has("result")) {
            return objectMapper.nullNode();
        }
        return resp.get("result");
    }

    public void sendNotification(String method, JsonNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        post(req);
    }

    private JsonNode post(JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");

            if (headers != null) {
                headers.forEach((k, v) -> {
                    if (k != null && v != null) {
                        builder.header(k, v);
                    }
                });
            }

            if (sessionId != null) {
                builder.header("MCP-Session-Id", sessionId);
            }

            HttpRequest request = builder.build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            response.headers().firstValue("MCP-Session-Id").ifPresent(v -> sessionId = v);

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("text/event-stream")) {
                return readFirstJsonRpcResponseFromSse(response.body(), body.has("id") ? body.get("id").asLong() : null);
            }

            try (InputStream is = response.body()) {
                return objectMapper.readTree(is);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("HTTP MCP request failed: " + endpoint, e);
        }
    }

    private JsonNode readFirstJsonRpcResponseFromSse(InputStream body, Long expectedId) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBuf = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    dataBuf.append(line.substring("data:".length()).trim());
                    continue;
                }
                if (line.isEmpty()) {
                    if (dataBuf.length() > 0) {
                        String data = dataBuf.toString();
                        dataBuf.setLength(0);
                        if (!data.isBlank()) {
                            JsonNode node = objectMapper.readTree(data);
                            if (node.has("id") && expectedId != null) {
                                if (node.get("id").asLong() == expectedId) {
                                    return node;
                                }
                            }
                            if (expectedId == null && node.has("result")) {
                                return node;
                            }
                        }
                    }
                }
            }
            throw new IllegalStateException("SSE stream ended without JSON-RPC response");
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to parse SSE response", e);
        }
    }
}
