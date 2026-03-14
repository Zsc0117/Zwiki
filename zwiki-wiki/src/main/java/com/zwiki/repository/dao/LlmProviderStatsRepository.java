package com.zwiki.repository.dao;

import com.zwiki.repository.entity.LlmProviderStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmProviderStatsRepository extends JpaRepository<LlmProviderStats, Long> {

    List<LlmProviderStats> findByUserIdOrderByTotalTokensDesc(String userId);

    Optional<LlmProviderStats> findByUserIdAndProvider(String userId, String provider);

    @Modifying
    @Query(value = "INSERT INTO zwiki_llm_provider_stats (user_id, provider, call_count, error_count, input_tokens, output_tokens, total_tokens, last_used_time, create_time, update_time) " +
           "VALUES (:userId, :provider, :callCount, :errorCount, :inputTokens, :outputTokens, :totalTokens, NOW(), NOW(), NOW()) " +
           "ON DUPLICATE KEY UPDATE " +
           "call_count = call_count + :callCount, " +
           "error_count = error_count + :errorCount, " +
           "input_tokens = input_tokens + :inputTokens, " +
           "output_tokens = output_tokens + :outputTokens, " +
           "total_tokens = total_tokens + :totalTokens, " +
           "last_used_time = NOW(), " +
           "update_time = NOW()", nativeQuery = true)
    int upsertStats(@Param("userId") String userId,
                    @Param("provider") String provider,
                    @Param("callCount") long callCount,
                    @Param("errorCount") long errorCount,
                    @Param("inputTokens") long inputTokens,
                    @Param("outputTokens") long outputTokens,
                    @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "INSERT INTO zwiki_llm_provider_stats (user_id, provider, call_count, input_tokens, output_tokens, total_tokens, last_used_time, create_time, update_time) " +
           "VALUES (:userId, :provider, 1, :inputTokens, :outputTokens, :totalTokens, NOW(), NOW(), NOW()) " +
           "ON DUPLICATE KEY UPDATE " +
           "call_count = call_count + 1, " +
           "input_tokens = input_tokens + :inputTokens, " +
           "output_tokens = output_tokens + :outputTokens, " +
           "total_tokens = total_tokens + :totalTokens, " +
           "last_used_time = NOW(), " +
           "update_time = NOW()", nativeQuery = true)
    int recordTokenUsage(@Param("userId") String userId,
                         @Param("provider") String provider,
                         @Param("inputTokens") long inputTokens,
                         @Param("outputTokens") long outputTokens,
                         @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "INSERT INTO zwiki_llm_provider_stats (user_id, provider, error_count, create_time, update_time) " +
           "VALUES (:userId, :provider, 1, NOW(), NOW()) " +
           "ON DUPLICATE KEY UPDATE " +
           "error_count = error_count + 1, " +
           "update_time = NOW()", nativeQuery = true)
    int recordError(@Param("userId") String userId, @Param("provider") String provider);
}
