package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ThesisVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 论文版本数据访问层
 */
@Repository
public interface ThesisVersionRepository extends JpaRepository<ThesisVersionEntity, Long> {

    /**
     * 通过任务ID查找所有版本
     */
    List<ThesisVersionEntity> findByTaskIdOrderByVersionDesc(String taskId);

    /**
     * 通过任务ID和文档类型查找所有版本
     */
    List<ThesisVersionEntity> findByTaskIdAndDocTypeOrderByVersionDesc(String taskId, String docType);

    /**
     * 通过任务ID和版本号查找
     */
    Optional<ThesisVersionEntity> findByTaskIdAndVersion(String taskId, Integer version);

    /**
     * 通过任务ID、文档类型和版本号查找
     */
    Optional<ThesisVersionEntity> findByTaskIdAndDocTypeAndVersion(String taskId, String docType, Integer version);

    /**
     * 查找当前版本
     */
    Optional<ThesisVersionEntity> findByTaskIdAndIsCurrentTrue(String taskId);

    /**
     * 查找指定文档类型的当前版本
     */
    Optional<ThesisVersionEntity> findByTaskIdAndDocTypeAndIsCurrentTrue(String taskId, String docType);

    /**
     * 获取最新版本号
     */
    default Integer getLatestVersion(String taskId) {
        return findByTaskIdOrderByVersionDesc(taskId)
                .stream()
                .findFirst()
                .map(ThesisVersionEntity::getVersion)
                .orElse(0);
    }

    /**
     * 获取指定文档类型的最新版本号
     */
    default Integer getLatestVersion(String taskId, String docType) {
        return findByTaskIdAndDocTypeOrderByVersionDesc(taskId, docType)
                .stream()
                .findFirst()
                .map(ThesisVersionEntity::getVersion)
                .orElse(0);
    }

    /**
     * 删除指定任务的所有论文版本
     */
    void deleteByTaskId(String taskId);

    /**
     * 删除指定任务指定文档类型的版本
     */
    void deleteByTaskIdAndDocType(String taskId, String docType);

    /**
     * 统计指定任务的版本数量
     */
    long countByTaskId(String taskId);

    /**
     * 统计指定任务指定文档类型的版本数量
     */
    long countByTaskIdAndDocType(String taskId, String docType);
}
