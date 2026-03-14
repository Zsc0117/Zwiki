package com.zwiki.repository.dao;

import com.zwiki.repository.entity.WikiWebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WikiWebhookConfigRepository extends JpaRepository<WikiWebhookConfig, Long> {

    List<WikiWebhookConfig> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<WikiWebhookConfig> findByUserIdAndTaskId(String userId, String taskId);

    Optional<WikiWebhookConfig> findByTaskId(String taskId);

    List<WikiWebhookConfig> findByRepoFullNameAndEnabledTrue(String repoFullName);

    Optional<WikiWebhookConfig> findByIdAndUserId(Long id, String userId);

    void deleteByIdAndUserId(Long id, String userId);

    @Modifying
    @Query("UPDATE WikiWebhookConfig c SET c.lastTriggerAt = CURRENT_TIMESTAMP, c.triggerCount = c.triggerCount + 1 WHERE c.id = :id")
    int incrementTriggerCount(@Param("id") Long id);
}
