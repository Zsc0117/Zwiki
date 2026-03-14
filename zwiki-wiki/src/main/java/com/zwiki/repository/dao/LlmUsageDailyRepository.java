package com.zwiki.repository.dao;

import com.zwiki.repository.entity.LlmUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LlmUsageDailyRepository extends JpaRepository<LlmUsageDaily, Long> {

    List<LlmUsageDaily> findByUserIdAndDimensionTypeAndDimensionIdAndStatDateBetweenOrderByStatDateAsc(
            String userId, String dimensionType, String dimensionId, LocalDate startDate, LocalDate endDate);

    List<LlmUsageDaily> findByUserIdAndDimensionTypeAndStatDateBetweenOrderByStatDateAsc(
            String userId, String dimensionType, LocalDate startDate, LocalDate endDate);

    @Modifying
    @Query(value = "INSERT INTO zwiki_llm_usage_daily (user_id, stat_date, dimension_type, dimension_id, call_count, error_count, input_tokens, output_tokens, total_tokens, create_time, update_time) " +
           "VALUES (:userId, :statDate, :dimensionType, :dimensionId, :callCount, :errorCount, :inputTokens, :outputTokens, :totalTokens, NOW(), NOW()) " +
           "ON DUPLICATE KEY UPDATE " +
           "call_count = call_count + :callCount, " +
           "error_count = error_count + :errorCount, " +
           "input_tokens = input_tokens + :inputTokens, " +
           "output_tokens = output_tokens + :outputTokens, " +
           "total_tokens = total_tokens + :totalTokens, " +
           "update_time = NOW()", nativeQuery = true)
    int upsertDaily(@Param("userId") String userId,
                    @Param("statDate") LocalDate statDate,
                    @Param("dimensionType") String dimensionType,
                    @Param("dimensionId") String dimensionId,
                    @Param("callCount") long callCount,
                    @Param("errorCount") long errorCount,
                    @Param("inputTokens") long inputTokens,
                    @Param("outputTokens") long outputTokens,
                    @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "INSERT INTO zwiki_llm_usage_daily (user_id, stat_date, dimension_type, dimension_id, call_count, input_tokens, output_tokens, total_tokens, create_time, update_time) " +
           "VALUES (:userId, :statDate, :dimensionType, :dimensionId, 1, :inputTokens, :outputTokens, :totalTokens, NOW(), NOW()) " +
           "ON DUPLICATE KEY UPDATE " +
           "call_count = call_count + 1, " +
           "input_tokens = input_tokens + :inputTokens, " +
           "output_tokens = output_tokens + :outputTokens, " +
           "total_tokens = total_tokens + :totalTokens, " +
           "update_time = NOW()", nativeQuery = true)
    int recordTokenUsage(@Param("userId") String userId,
                         @Param("statDate") LocalDate statDate,
                         @Param("dimensionType") String dimensionType,
                         @Param("dimensionId") String dimensionId,
                         @Param("inputTokens") long inputTokens,
                         @Param("outputTokens") long outputTokens,
                         @Param("totalTokens") long totalTokens);

    @Modifying
    @Query(value = "INSERT INTO zwiki_llm_usage_daily (user_id, stat_date, dimension_type, dimension_id, error_count, create_time, update_time) " +
           "VALUES (:userId, :statDate, :dimensionType, :dimensionId, 1, NOW(), NOW()) " +
           "ON DUPLICATE KEY UPDATE " +
           "error_count = error_count + 1, " +
           "update_time = NOW()", nativeQuery = true)
    int recordError(@Param("userId") String userId,
                    @Param("statDate") LocalDate statDate,
                    @Param("dimensionType") String dimensionType,
                    @Param("dimensionId") String dimensionId);
}
