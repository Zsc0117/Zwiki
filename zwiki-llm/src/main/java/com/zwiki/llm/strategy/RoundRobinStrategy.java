package com.zwiki.llm.strategy;

import com.zwiki.llm.config.LlmBalancerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RoundRobinStrategy implements ModelSelectionStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public String selectModel(List<LlmBalancerProperties.ModelConfig> availableModels) {
        if (availableModels == null || availableModels.isEmpty()) {
            return null;
        }
        int current = counter.getAndIncrement();
        int index = Math.floorMod(current, availableModels.size());
        String selected = availableModels.get(index).getName();
        log.debug("RoundRobin: counter={}, index={}/{}, selected={}", current, index, availableModels.size(), selected);
        return selected;
    }

    @Override
    public String getName() {
        return "round_robin";
    }
}
