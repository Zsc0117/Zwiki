package com.zwiki.llm.strategy;

import com.zwiki.llm.config.LlmBalancerProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RandomStrategy implements ModelSelectionStrategy {

    @Override
    public String selectModel(List<LlmBalancerProperties.ModelConfig> availableModels) {
        if (availableModels == null || availableModels.isEmpty()) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(availableModels.size());
        return availableModels.get(index).getName();
    }

    @Override
    public String getName() {
        return "random";
    }
}
