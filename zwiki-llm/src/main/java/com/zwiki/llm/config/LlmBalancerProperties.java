package com.zwiki.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "zwiki.llm.balancer")
public class LlmBalancerProperties {

    private boolean enabled = true;

    private String modelsSource = "properties";

    private String strategy = "round_robin";

    private int maxAttemptsPerRequest = 3;

    private int unhealthyCooldownSeconds = 300;

    private boolean allowFallbackOnExplicitModel = true;

    private List<ModelConfig> models = new ArrayList<>();

    @Data
    public static class ModelConfig {
        private Long llmKeyId;
        private String name;
        private String displayName;
        private String provider;
        private String apiKey;
        private String baseUrl;
        private String apiVersion;
        private Map<String, String> extraHeaders;
        private String modelType = "TEXT";
        private String capabilities;
        private boolean enabled = true;
        private int weight = 1;
        private int priority = 0;
    }
}
