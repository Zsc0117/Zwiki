package com.zwiki.repository.dao;

import com.zwiki.repository.entity.LlmModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface LlmModelRepository extends JpaRepository<LlmModel, Long> {

    List<LlmModel> findByEnabledTrueOrderByPriorityDescWeightDesc();

    List<LlmModel> findByProviderOrderByPriorityDescWeightDesc(String provider);

    Optional<LlmModel> findByName(String name);

    Optional<LlmModel> findFirstByNameOrderByIdAsc(String name);

    boolean existsByName(String name);

    List<LlmModel> findByLlmKeyIdOrderByPriorityDescWeightDesc(Long llmKeyId);

    List<LlmModel> findByLlmKeyIdInAndEnabledTrueOrderByPriorityDescWeightDesc(List<Long> llmKeyIds);

    List<LlmModel> findByLlmKeyIdInOrderByPriorityDescWeightDesc(List<Long> llmKeyIds);

    Optional<LlmModel> findByLlmKeyIdAndName(Long llmKeyId, String name);

    boolean existsByLlmKeyIdAndName(Long llmKeyId, String name);

    boolean existsByLlmKeyIdAndNameAndIdNot(Long llmKeyId, String name, Long id);

    long countByLlmKeyId(Long llmKeyId);

    @Query(value = "SELECT user_id AS userId, provider FROM zwiki_llm_model " +
            "WHERE llm_key_id IS NULL AND user_id IS NOT NULL " +
            "GROUP BY user_id, provider", nativeQuery = true)
    List<Map<String, Object>> findLegacyUserProviderPairsWithoutKey();

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET llm_key_id = :llmKeyId " +
            "WHERE llm_key_id IS NULL AND user_id = :userId AND provider = :provider", nativeQuery = true)
    int bindLegacyModelsToKey(@Param("userId") String userId,
                              @Param("provider") String provider,
                              @Param("llmKeyId") Long llmKeyId);

    // Precise match by keyId + modelName (preferred when keyId is available)
    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "input_tokens = COALESCE(input_tokens, 0) + :inputTokens, " +
           "output_tokens = COALESCE(output_tokens, 0) + :outputTokens, " +
           "total_tokens = COALESCE(total_tokens, 0) + :totalTokens, " +
           "call_count = COALESCE(call_count, 0) + 1, " +
           "last_used_time = NOW() " +
           "WHERE llm_key_id = :keyId AND name = :modelName", nativeQuery = true)
    int recordTokenUsageByKeyId(@Param("keyId") Long keyId,
                                @Param("modelName") String modelName,
                                @Param("inputTokens") long inputTokens,
                                @Param("outputTokens") long outputTokens,
                                @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "input_tokens = COALESCE(input_tokens, 0) + :inputTokens, " +
           "output_tokens = COALESCE(output_tokens, 0) + :outputTokens, " +
           "total_tokens = COALESCE(total_tokens, 0) + :totalTokens, " +
           "call_count = COALESCE(call_count, 0) + 1, " +
           "last_used_time = NOW() " +
           "WHERE name = :modelName AND user_id = :userId LIMIT 1", nativeQuery = true)
    int recordTokenUsageForUser(@Param("modelName") String modelName,
                                @Param("userId") String userId,
                                @Param("inputTokens") long inputTokens,
                                @Param("outputTokens") long outputTokens,
                                @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model m SET " +
           "m.input_tokens = COALESCE(m.input_tokens, 0) + :inputTokens, " +
           "m.output_tokens = COALESCE(m.output_tokens, 0) + :outputTokens, " +
           "m.total_tokens = COALESCE(m.total_tokens, 0) + :totalTokens, " +
           "m.call_count = COALESCE(m.call_count, 0) + 1, " +
           "m.last_used_time = NOW() " +
           "WHERE m.name = :modelName " +
           "AND EXISTS (SELECT 1 FROM zwiki_llm_key k WHERE k.id = m.llm_key_id AND k.user_id = :userId) " +
           "LIMIT 1", nativeQuery = true)
    int recordTokenUsageForUserByKey(@Param("modelName") String modelName,
                                     @Param("userId") String userId,
                                     @Param("inputTokens") long inputTokens,
                                     @Param("outputTokens") long outputTokens,
                                     @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "input_tokens = COALESCE(input_tokens, 0) + :inputTokens, " +
           "output_tokens = COALESCE(output_tokens, 0) + :outputTokens, " +
           "total_tokens = COALESCE(total_tokens, 0) + :totalTokens, " +
           "call_count = COALESCE(call_count, 0) + 1, " +
           "last_used_time = NOW() " +
           "WHERE name = :modelName LIMIT 1", nativeQuery = true)
    int recordTokenUsageForPublic(@Param("modelName") String modelName,
                                  @Param("inputTokens") long inputTokens,
                                  @Param("outputTokens") long outputTokens,
                                  @Param("totalTokens") long totalTokens);

    // Precise match by keyId + modelName (preferred when keyId is available)
    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "error_count = COALESCE(error_count, 0) + 1 " +
           "WHERE llm_key_id = :keyId AND name = :modelName", nativeQuery = true)
    int recordErrorByKeyId(@Param("keyId") Long keyId, @Param("modelName") String modelName);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "error_count = COALESCE(error_count, 0) + 1 " +
           "WHERE name = :modelName AND user_id = :userId LIMIT 1", nativeQuery = true)
    int recordErrorForUser(@Param("modelName") String modelName, @Param("userId") String userId);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model m SET " +
           "m.error_count = COALESCE(m.error_count, 0) + 1 " +
           "WHERE m.name = :modelName " +
           "AND EXISTS (SELECT 1 FROM zwiki_llm_key k WHERE k.id = m.llm_key_id AND k.user_id = :userId) " +
           "LIMIT 1", nativeQuery = true)
    int recordErrorForUserByKey(@Param("modelName") String modelName, @Param("userId") String userId);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "error_count = COALESCE(error_count, 0) + 1 " +
           "WHERE name = :modelName LIMIT 1", nativeQuery = true)
    int recordErrorForPublic(@Param("modelName") String modelName);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "input_tokens = COALESCE(input_tokens, 0) + :inputTokens, " +
           "output_tokens = COALESCE(output_tokens, 0) + :outputTokens, " +
           "total_tokens = COALESCE(total_tokens, 0) + :totalTokens, " +
           "call_count = COALESCE(call_count, 0) + 1, " +
           "last_used_time = NOW() " +
           "WHERE name = :modelName LIMIT 1", nativeQuery = true)
    int recordTokenUsageByNameOnly(@Param("modelName") String modelName,
                                   @Param("inputTokens") long inputTokens,
                                   @Param("outputTokens") long outputTokens,
                                   @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET " +
           "error_count = COALESCE(error_count, 0) + 1 " +
           "WHERE name = :modelName LIMIT 1", nativeQuery = true)
    int recordErrorByNameOnly(@Param("modelName") String modelName);

    @Modifying
    @Query(value = "UPDATE zwiki_llm_model SET enabled = false WHERE llm_key_id = :keyId AND name = :modelName", nativeQuery = true)
    int disableByKeyIdAndName(@Param("keyId") Long keyId, @Param("modelName") String modelName);

    @Query(value = "SELECT name, provider, model_type AS modelType, " +
           "COALESCE(call_count, 0) AS callCount, " +
           "COALESCE(total_tokens, 0) AS totalTokens, " +
           "COALESCE(input_tokens, 0) AS inputTokens, " +
           "COALESCE(output_tokens, 0) AS outputTokens, " +
           "COALESCE(error_count, 0) AS errorCount, " +
           "enabled " +
           "FROM zwiki_llm_model ORDER BY total_tokens DESC", nativeQuery = true)
    List<Map<String, Object>> getModelStats();

    @Query(value = "SELECT COALESCE(SUM(total_tokens), 0) FROM zwiki_llm_model", nativeQuery = true)
    long getTotalTokensUsed();
}
