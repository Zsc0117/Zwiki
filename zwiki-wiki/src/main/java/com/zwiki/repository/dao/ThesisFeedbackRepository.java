package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ThesisFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 论文反馈数据访问层
 */
@Repository
public interface ThesisFeedbackRepository extends JpaRepository<ThesisFeedbackEntity, Long> {

    /**
     * 通过任务ID查找所有反馈
     */
    List<ThesisFeedbackEntity> findByTaskIdOrderByCreatedAtDesc(String taskId);

    /**
     * 通过任务ID和版本号查找反馈
     */
    List<ThesisFeedbackEntity> findByTaskIdAndVersion(String taskId, Integer version);

    /**
     * 通过任务ID、文档类型和版本号查找反馈
     */
    List<ThesisFeedbackEntity> findByTaskIdAndDocTypeAndVersion(String taskId, String docType, Integer version);

    /**
     * 查找未处理的反馈
     */
    List<ThesisFeedbackEntity> findByProcessedFalseOrderByCreatedAtAsc();

    /**
     * 删除指定任务的所有反馈
     */
    void deleteByTaskId(String taskId);

    /**
     * 删除指定任务指定文档类型的所有反馈
     */
    void deleteByTaskIdAndDocType(String taskId, String docType);
}
