package com.zwiki.mcpserver.repository;

import com.zwiki.mcpserver.entity.McpApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface McpApiKeyRepository extends JpaRepository<McpApiKey, Long> {

    boolean existsByApiKey(String apiKey);

    @Modifying
    @Transactional
    @Query("UPDATE McpApiKey a SET a.lastUsedAt = :now WHERE a.apiKey = :apiKey")
    void updateLastUsedAt(@Param("apiKey") String apiKey, @Param("now") LocalDateTime now);
}
