package com.zwiki.repository.dao;

import com.zwiki.repository.entity.Catalogue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author pai
 * @description: 目录Mapper
 * @date 2026/1/23 00:30
 */
public interface CatalogueRepository extends JpaRepository<Catalogue, Long> {
    Optional<Catalogue> findFirstByCatalogueId(String catalogueId);

    List<Catalogue> findByTaskId(String taskId);

    long countByTaskId(String taskId);

    long countByTaskIdAndStatus(String taskId, Integer status);

    void deleteByTaskId(String taskId);
    
    /**
     * 查找未完成的目录（status != 1，即非完成状态）
     */
    List<Catalogue> findByTaskIdAndStatusNot(String taskId, Integer status);
    
    /**
     * 查找失败的目录（status = 2）
     */
    List<Catalogue> findByTaskIdAndStatus(String taskId, Integer status);
}