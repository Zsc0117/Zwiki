package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    List<ApiKey> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<ApiKey> findByKeyIdAndUserId(String keyId, String userId);

    Optional<ApiKey> findByApiKey(String apiKey);

    long countByUserId(String userId);

    @Modifying
    @Transactional
    @Query("UPDATE ApiKey a SET a.lastUsedAt = :now WHERE a.apiKey = :apiKey")
    void updateLastUsedAt(@Param("apiKey") String apiKey, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    void deleteByKeyIdAndUserId(String keyId, String userId);
}
