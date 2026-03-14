package com.zwiki.mcpserver.repository;

import com.zwiki.mcpserver.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findFirstByTaskId(String taskId);

    List<Task> findByProjectNameContainingIgnoreCase(String keyword);

    List<Task> findByStatus(String status);
}
