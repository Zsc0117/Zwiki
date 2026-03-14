package com.zwiki.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "zwiki_llm_key")
public class LlmKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "api_key_cipher", nullable = false, columnDefinition = "TEXT")
    private String apiKeyCipher;

    @Column(name = "api_key_masked", nullable = false, length = 64)
    private String apiKeyMasked;

    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(name = "api_version", length = 32)
    private String apiVersion;

    @Column(name = "extra_headers", columnDefinition = "JSON")
    private String extraHeaders;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "call_count")
    @Builder.Default
    private Long callCount = 0L;

    @Column(name = "error_count")
    @Builder.Default
    private Long errorCount = 0L;

    @Column(name = "input_tokens")
    @Builder.Default
    private Long inputTokens = 0L;

    @Column(name = "output_tokens")
    @Builder.Default
    private Long outputTokens = 0L;

    @Column(name = "total_tokens")
    @Builder.Default
    private Long totalTokens = 0L;

    @Column(name = "last_used_time")
    private LocalDateTime lastUsedTime;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
