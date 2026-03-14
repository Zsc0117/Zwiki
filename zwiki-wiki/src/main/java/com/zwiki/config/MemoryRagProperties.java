package com.zwiki.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "zwiki.rag")
public class MemoryRagProperties {

    private boolean enabled = false;

    private String isolationMode = "PROJECT";

    private int topK = 6;

    private boolean includeDocuments = true;

    private boolean includeCodeFiles = true;

    private int maxChars = 12000;
}
