package com.zwiki.repository.dao;

import com.zwiki.repository.entity.DocChange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocChangeRepository extends JpaRepository<DocChange, Long> {

    /**
     * 查询指定任务的变更历史（分页）
     */
    Page<DocChange> findByTaskIdOrderByCreatedAtDesc(String taskId, Pageable pageable);

    /**
     * 查询指定章节的变更历史
     */
    List<DocChange> findByTaskIdAndCatalogueIdOrderByCreatedAtDesc(String taskId, String catalogueId);

    /**
     * 查询指定任务的最新变更
     */
    @Query("SELECT dc FROM DocChange dc WHERE dc.taskId = :taskId ORDER BY dc.createdAt DESC")
    List<DocChange> findLatestChangesByTaskId(@Param("taskId") String taskId, Pageable pageable);

    /**
     * 统计指定任务的变更数量
     */
    @Query("SELECT dc.changeType, COUNT(dc) FROM DocChange dc WHERE dc.taskId = :taskId GROUP BY dc.changeType")
    List<Object[]> countChangesByType(@Param("taskId") String taskId);

    /**
     * 查询指定时间范围内的变更
     */
    @Query("SELECT dc FROM DocChange dc WHERE dc.taskId = :taskId AND dc.createdAt BETWEEN :start AND :end ORDER BY dc.createdAt DESC")
    List<DocChange> findChangesByTimeRange(@Param("taskId") String taskId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 删除指定任务的变更记录
     */
    void deleteByTaskId(String taskId);
}
