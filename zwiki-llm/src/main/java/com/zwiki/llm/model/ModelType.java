package com.zwiki.llm.model;

public enum ModelType {
    TEXT,
    IMAGE,
    VOICE,
    VIDEO,
    MULTIMODAL;

    public static ModelType fromString(String s) {
        if (s == null || s.isBlank()) {
            return TEXT;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEXT;
        }
    }
}
