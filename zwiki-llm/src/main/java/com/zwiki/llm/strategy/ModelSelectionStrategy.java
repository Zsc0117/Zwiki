package com.zwiki.llm.strategy;

import com.zwiki.llm.config.LlmBalancerProperties;

import java.util.List;

public interface ModelSelectionStrategy {

    String selectModel(List<LlmBalancerProperties.ModelConfig> availableModels);

    String getName();
}
