package com.zwiki.llm.provider;

import com.zwiki.llm.config.LlmBalancerProperties;

import java.util.List;

public interface ModelConfigProvider {

    List<LlmBalancerProperties.ModelConfig> getModels();
}
