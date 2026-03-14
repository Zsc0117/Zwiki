package com.zwiki.service;

import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ThesisProgressService {

    private final Map<String, Progress> progressMap = new ConcurrentHashMap<>();

    private String keyOf(String taskId, String docType) {
        String safeTaskId = taskId != null ? taskId : "";
        String safeDocType = (docType == null || docType.isBlank()) ? "thesis" : docType.trim();
        return safeTaskId + ":" + safeDocType;
    }

    public void start(String taskId, String step) {
        update(taskId, "generating", 0, step);
    }

    public void startByDocType(String taskId, String docType, String step) {
        updateByDocType(taskId, docType, "generating", 0, step);
    }

    public void update(String taskId, int progress, String step) {
        update(taskId, "generating", progress, step);
    }

    public void updateByDocType(String taskId, String docType, int progress, String step) {
        updateByDocType(taskId, docType, "generating", progress, step);
    }

    public void update(String taskId, String status, int progress, String step) {
        Progress p = new Progress(status, clamp(progress), step, System.currentTimeMillis());
        progressMap.put(taskId, p);
    }

    public void updateByDocType(String taskId, String docType, String status, int progress, String step) {
        Progress p = new Progress(status, clamp(progress), step, System.currentTimeMillis());
        progressMap.put(keyOf(taskId, docType), p);
    }

    public void complete(String taskId, String step) {
        update(taskId, "completed", 100, step);
    }

    public void completeByDocType(String taskId, String docType, String step) {
        updateByDocType(taskId, docType, "completed", 100, step);
    }

    public void fail(String taskId, String step) {
        update(taskId, "failed", 100, step);
    }

    public void failByDocType(String taskId, String docType, String step) {
        updateByDocType(taskId, docType, "failed", 100, step);
    }

    public Optional<Progress> get(String taskId) {
        return Optional.ofNullable(progressMap.get(taskId));
    }

    public Optional<Progress> getByDocType(String taskId, String docType) {
        return Optional.ofNullable(progressMap.get(keyOf(taskId, docType)));
    }

    public void clear(String taskId) {
        progressMap.remove(taskId);
    }

    public void clearByDocType(String taskId, String docType) {
        progressMap.remove(keyOf(taskId, docType));
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

        public Progress(String status, int progress, String currentStep, long updatedAt) {
            this.status = status;
            this.progress = progress;
            this.currentStep = currentStep;
            this.updatedAt = updatedAt;
        }
    }
}
