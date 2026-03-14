package com.zwiki.llm.strategy;

import com.zwiki.llm.config.LlmBalancerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Nginx-style smooth weighted round-robin that honours both weight and priority.
 * <p>
 * Effective weight = weight × (1 + priority).
 * Higher-priority / higher-weight models are selected proportionally more often,
 * and selections are distributed smoothly (no bursts).
 */
@Slf4j
@Component
public class WeightedRoundRobinStrategy implements ModelSelectionStrategy {

    // current_weight state per model name – guarded by synchronized selectModel
    private final Map<String, Integer> currentWeights = new HashMap<>();

    @Override
    public synchronized String selectModel(List<LlmBalancerProperties.ModelConfig> availableModels) {
        if (availableModels == null || availableModels.isEmpty()) {
            return null;
        }

        // Sort by priority desc → name asc for deterministic ordering
        List<LlmBalancerProperties.ModelConfig> sorted = new ArrayList<>(availableModels);
        sorted.sort(Comparator.comparingInt(LlmBalancerProperties.ModelConfig::getPriority).reversed()
                .thenComparing(LlmBalancerProperties.ModelConfig::getName));

        int totalWeight = 0;
        String bestModel = null;
        int bestCw = Integer.MIN_VALUE;

        for (LlmBalancerProperties.ModelConfig model : sorted) {
            int ew = effectiveWeight(model);
            totalWeight += ew;

            int cw = currentWeights.getOrDefault(model.getName(), 0) + ew;
            currentWeights.put(model.getName(), cw);

            if (cw > bestCw) {
                bestCw = cw;
                bestModel = model.getName();
            }
        }

        if (bestModel != null) {
            currentWeights.put(bestModel, currentWeights.get(bestModel) - totalWeight);
        }

        // Evict models no longer in the candidate list
        Set<String> active = sorted.stream()
                .map(LlmBalancerProperties.ModelConfig::getName)
                .collect(Collectors.toSet());
        currentWeights.keySet().retainAll(active);

        if (log.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder();
            sorted.forEach(m -> sb.append(m.getName()).append("(w=").append(m.getWeight())
                    .append(",p=").append(m.getPriority())
                    .append(",ew=").append(effectiveWeight(m))
                    .append(",cw=").append(currentWeights.getOrDefault(m.getName(), 0))
                    .append(") "));
            log.info("WeightedRR(smooth): selected={}, totalEW={}, state=[{}]",
                    bestModel, totalWeight, sb.toString().trim());
        }
        return bestModel;
    }

    private static int effectiveWeight(LlmBalancerProperties.ModelConfig model) {
        int weight = Math.max(1, model.getWeight());
        int priority = Math.max(0, model.getPriority());
        return weight * (1 + priority);
    }

    @Override
    public String getName() {
        return "weighted_rr";
    }
}
