package com.zwiki.repository.dao;

import com.zwiki.repository.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通知数据访问层
 *
 * @author zwiki
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long>, JpaSpecificationExecutor<NotificationEntity> {

    /**
     * 根据通知ID查询
     */
    Optional<NotificationEntity> findByNotificationId(String notificationId);

    /**
     * 根据用户ID分页查询通知列表（按创建时间倒序）
     */
    Page<NotificationEntity> findByUserIdOrderByCreateTimeDesc(String userId, Pageable pageable);

    /**
     * 根据用户ID查询通知列表（按创建时间倒序）
     */
    List<NotificationEntity> findByUserIdOrderByCreateTimeDesc(String userId);

    /**
     * 根据用户ID查询未读通知列表
     */
    List<NotificationEntity> findByUserIdAndIsReadFalseOrderByCreateTimeDesc(String userId);

    /**
     * 统计用户未读通知数量
     */
    long countByUserIdAndIsReadFalse(String userId);

    /**
     * 根据任务ID查询通知列表
     */
    List<NotificationEntity> findByTaskIdOrderByCreateTimeDesc(String taskId);

    /**
     * 标记用户所有通知为已读
     */
    @Modifying
    @Transactional
    @Query("UPDATE NotificationEntity n SET n.isRead = true, n.readTime = :readTime WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") String userId, @Param("readTime") LocalDateTime readTime);

    /**
     * 标记单个通知为已读
     */
    @Modifying
    @Transactional
    @Query("UPDATE NotificationEntity n SET n.isRead = true, n.readTime = :readTime WHERE n.notificationId = :notificationId AND n.isRead = false")
    int markAsReadByNotificationId(@Param("notificationId") String notificationId, @Param("readTime") LocalDateTime readTime);

    /**
     * 删除用户所有通知
     */
    @Modifying
    @Transactional
    void deleteByUserId(String userId);

    /**
     * 删除指定时间之前的通知（用于清理过期通知）
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotificationEntity n WHERE n.createTime < :beforeTime")
    int deleteByCreateTimeBefore(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 删除用户指定时间之前的已读通知
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM NotificationEntity n WHERE n.userId = :userId AND n.isRead = true AND n.createTime < :beforeTime")
    int deleteReadNotificationsByUserIdAndCreateTimeBefore(@Param("userId") String userId, @Param("beforeTime") LocalDateTime beforeTime);
}
