package com.zwiki.repository.dao;

import com.zwiki.repository.entity.LlmKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmKeyRepository extends JpaRepository<LlmKey, Long> {

    List<LlmKey> findByUserIdOrderByCreateTimeDesc(String userId);

    List<LlmKey> findByUserIdAndEnabledTrueOrderByCreateTimeDesc(String userId);

    Optional<LlmKey> findByIdAndUserId(Long id, String userId);

    boolean existsByUserIdAndName(String userId, String name);

    boolean existsByUserIdAndNameAndIdNot(String userId, String name, Long id);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_key SET " +
           "call_count = COALESCE(call_count, 0) + 1, " +
           "input_tokens = COALESCE(input_tokens, 0) + :inputTokens, " +
           "output_tokens = COALESCE(output_tokens, 0) + :outputTokens, " +
           "total_tokens = COALESCE(total_tokens, 0) + :totalTokens, " +
           "last_used_time = NOW() " +
           "WHERE id = :keyId", nativeQuery = true)
    int recordTokenUsageForKey(@Param("keyId") Long keyId,
                               @Param("inputTokens") long inputTokens,
                               @Param("outputTokens") long outputTokens,
                               @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_key SET " +
           "error_count = COALESCE(error_count, 0) + 1 " +
           "WHERE id = :keyId", nativeQuery = true)
    int recordErrorForKey(@Param("keyId") Long keyId);
}
