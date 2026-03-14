package com.zwiki.llm.provider;

import com.zwiki.llm.config.LlmBalancerProperties;

import java.util.List;

public class PropertiesModelConfigProvider implements ModelConfigProvider {

    private final LlmBalancerProperties properties;

    public PropertiesModelConfigProvider(LlmBalancerProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<LlmBalancerProperties.ModelConfig> getModels() {
        return properties.getModels();
    }
}
