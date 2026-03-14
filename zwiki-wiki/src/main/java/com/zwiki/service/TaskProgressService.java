package com.zwiki.service;

import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskProgressService {

    private final Map<String, Progress> progressMap = new ConcurrentHashMap<>();

    public void start(String taskId, String step) {
        update(taskId, "processing", 0, step);
    }

    public void update(String taskId, int progress, String step) {
        update(taskId, "processing", progress, step);
    }

    public void update(String taskId, String status, int progress, String step) {
        Progress prev = progressMap.get(taskId);
        int safeTotal = prev != null ? prev.getDocTotal() : 0;
        int safeCompleted = prev != null ? prev.getDocCompleted() : 0;
        progressMap.put(taskId, new Progress(status, clamp(progress), step, System.currentTimeMillis(), safeTotal, safeCompleted));
    }

    public void initDocGeneration(String taskId, int docTotal, String step) {
        Progress prev = progressMap.get(taskId);
        String status = prev != null ? prev.getStatus() : "processing";
        int progress = prev != null ? prev.getProgress() : 0;
        progressMap.put(taskId, new Progress(status, clamp(progress), step, System.currentTimeMillis(), Math.max(docTotal, 0), 0));
    }

    public Progress incrementDocCompleted(String taskId, String step, int baseProgress, int maxProgress) {
        Progress prev = progressMap.get(taskId);
        int total = prev != null ? prev.getDocTotal() : 0;
        int completed = prev != null ? prev.getDocCompleted() : 0;
        if (total <= 0) {
            update(taskId, clamp(maxProgress), step);
            return progressMap.get(taskId);
        }

        int nextCompleted = Math.min(total, completed + 1);
        int mapped = baseProgress;
        if (maxProgress > baseProgress) {
            double ratio = (nextCompleted * 1.0) / total;
            mapped = baseProgress + (int) Math.round(ratio * (maxProgress - baseProgress));
        }
        Progress next = new Progress("processing", clamp(mapped), step, System.currentTimeMillis(), total, nextCompleted);
        progressMap.put(taskId, next);
        return next;
    }

    public void complete(String taskId, String step) {
        Progress prev = progressMap.get(taskId);
        int total = prev != null ? prev.getDocTotal() : 0;
        progressMap.put(taskId, new Progress("completed", 100, step, System.currentTimeMillis(), total, total));
    }

    public void fail(String taskId, String step) {
        Progress prev = progressMap.get(taskId);
        int total = prev != null ? prev.getDocTotal() : 0;
        int completed = prev != null ? prev.getDocCompleted() : 0;
        progressMap.put(taskId, new Progress("failed", 100, step, System.currentTimeMillis(), total, completed));
    }

    public Optional<Progress> get(String taskId) {
        return Optional.ofNullable(progressMap.get(taskId));
    }

    public void clear(String taskId) {
        progressMap.remove(taskId);
    }

    private int clamp(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 100) {
            return 100;
        }
        return v;
    }

    @Getter
    @ToString
    public static class Progress {
        private final String status;
        private final int progress;
        private final String currentStep;
        private final long updatedAt;
        private final int docTotal;
        private final int docCompleted;

        public Progress(String status, int progress, String currentStep, long updatedAt, int docTotal, int docCompleted) {
            this.status = status;
            this.progress = progress;
            this.currentStep = currentStep;
            this.updatedAt = updatedAt;
            this.docTotal = docTotal;
            this.docCompleted = docCompleted;
        }
    }
}
