package com.zwiki.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelHealthState {

    private String modelName;

    private boolean healthy;

    private Long unhealthyUntilEpochMillis;

    private Long lastUsedEpochMillis;

    private long callCount;

    private long errorCount;

    private Long tokensActual;

    public boolean isCurrentlyHealthy() {
        if (healthy) {
            return true;
        }
        if (unhealthyUntilEpochMillis == null) {
            return true;
        }
        return System.currentTimeMillis() > unhealthyUntilEpochMillis;
    }

    public long getRemainingCooldownMillis() {
        if (unhealthyUntilEpochMillis == null) {
            return 0;
        }
        long remaining = unhealthyUntilEpochMillis - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
}
