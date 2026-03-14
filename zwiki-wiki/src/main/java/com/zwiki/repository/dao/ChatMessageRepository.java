package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByUserIdAndTaskIdOrderByCreatedAtAsc(String userId, String taskId);

    List<ChatMessage> findByUserIdAndTaskIdIsNullOrderByCreatedAtAsc(String userId);

    List<ChatMessage> findTop10ByUserIdAndTaskIdOrderByCreatedAtDesc(String userId, String taskId);

    List<ChatMessage> findTop10ByUserIdAndTaskIdIsNullOrderByCreatedAtDesc(String userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage c WHERE c.userId = :userId AND c.taskId = :taskId")
    void deleteByUserIdAndTaskId(String userId, String taskId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage c WHERE c.userId = :userId AND c.taskId IS NULL")
    void deleteByUserIdAndTaskIdIsNull(String userId);
}
