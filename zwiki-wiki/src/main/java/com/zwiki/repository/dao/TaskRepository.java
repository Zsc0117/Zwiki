package com.zwiki.repository.dao;
import com.zwiki.domain.enums.TaskStatusEnum;
import com.zwiki.repository.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * @author pai
 * @description: 任务映射类
 * @date 2026/1/23 16:05
 */
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {
    Optional<Task> findFirstByTaskId(String taskId);

    Optional<Task> findFirstByUserIdAndProjectUrlOrderByIdDesc(String userId, String projectUrl);

    Optional<Task> findFirstByProjectUrlOrderByIdDesc(String projectUrl);

    void deleteByTaskId(String taskId);
    
    /**
     * 查找用户的失败任务
     */
    List<Task> findByUserIdAndStatusOrderByUpdateTimeDesc(String userId, TaskStatusEnum status);
    
    /**
     * 查找用户指定状态的任务（支持多状态）
     */
    List<Task> findByUserIdAndStatusInOrderByUpdateTimeDesc(String userId, List<TaskStatusEnum> statuses);
    
    /**
     * 查找用户的所有任务（按更新时间倒序）
     */
    List<Task> findByUserIdOrderByUpdateTimeDesc(String userId);
}