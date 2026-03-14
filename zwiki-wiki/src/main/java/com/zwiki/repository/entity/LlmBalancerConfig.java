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
@Table(name = "zwiki_llm_balancer_config")
public class LlmBalancerConfig {

    @Id
    private Long id;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "strategy", length = 64)
    private String strategy;

    @Column(name = "max_attempts_per_request")
    private Integer maxAttemptsPerRequest;

    @Column(name = "unhealthy_cooldown_seconds")
    private Integer unhealthyCooldownSeconds;

    @Column(name = "allow_fallback_on_explicit_model")
    private Boolean allowFallbackOnExplicitModel;

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
