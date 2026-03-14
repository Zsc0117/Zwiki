package com.zwiki.repository.dao;

import com.zwiki.repository.entity.Diagram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiagramRepository extends JpaRepository<Diagram, Long> {

    List<Diagram> findByTaskIdOrderByCreatedAtDesc(String taskId);

    Optional<Diagram> findByDiagramId(String diagramId);

    void deleteByDiagramId(String diagramId);

    List<Diagram> findByTaskIdAndUserIdOrderByCreatedAtDesc(String taskId, String userId);

    Optional<Diagram> findFirstByTaskIdAndUserIdAndSourceUrlOrderByCreatedAtDesc(String taskId, String userId, String sourceUrl);
}
