package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ReviewHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewHistoryRepository extends JpaRepository<ReviewHistory, Long> {

    Page<ReviewHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReviewHistory> findByRepoFullNameOrderByCreatedAtDesc(String repoFullName, Pageable pageable);
}
