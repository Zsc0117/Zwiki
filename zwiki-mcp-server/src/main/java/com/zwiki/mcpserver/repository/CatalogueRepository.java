package com.zwiki.mcpserver.repository;

import com.zwiki.mcpserver.entity.Catalogue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CatalogueRepository extends JpaRepository<Catalogue, Long> {

    List<Catalogue> findByTaskId(String taskId);

    Optional<Catalogue> findFirstByCatalogueId(String catalogueId);

    @Query("SELECT c FROM Catalogue c WHERE c.taskId = :taskId AND (LOWER(c.title) LIKE LOWER(CONCAT('%',:keyword,'%')) OR LOWER(c.content) LIKE LOWER(CONCAT('%',:keyword,'%')))")
    List<Catalogue> searchByTaskIdAndKeyword(@Param("taskId") String taskId, @Param("keyword") String keyword);

    @Query("SELECT c FROM Catalogue c WHERE LOWER(c.title) LIKE LOWER(CONCAT('%',:keyword,'%')) OR LOWER(c.content) LIKE LOWER(CONCAT('%',:keyword,'%'))")
    List<Catalogue> searchByKeyword(@Param("keyword") String keyword);

    long countByTaskId(String taskId);
}
