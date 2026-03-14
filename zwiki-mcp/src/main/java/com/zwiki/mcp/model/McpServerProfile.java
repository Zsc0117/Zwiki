package com.zwiki.mcp.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record McpServerProfile(
        String name,
        String url,
        TransportType transportType,
        Map<String, String> headers,
        ProxyMode proxyMode,
        String proxyHost,
        Integer proxyPort,
        Duration timeout,
        String command,
        List<String> args,
        Map<String, String> env
) {
    public McpServerProfile(String name, String url, TransportType transportType,
                            Map<String, String> headers, ProxyMode proxyMode,
                            String proxyHost, Integer proxyPort, Duration timeout) {
        this(name, url, transportType, headers, proxyMode, proxyHost, proxyPort, timeout,
                null, List.of(), Map.of());
    }
}
