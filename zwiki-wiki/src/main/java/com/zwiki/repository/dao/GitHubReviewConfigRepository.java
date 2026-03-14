package com.zwiki.repository.dao;

import com.zwiki.repository.entity.GitHubReviewConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitHubReviewConfigRepository extends JpaRepository<GitHubReviewConfig, Long> {

    List<GitHubReviewConfig> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<GitHubReviewConfig> findByUserIdAndRepoFullName(String userId, String repoFullName);

    Optional<GitHubReviewConfig> findByIdAndUserId(Long id, String userId);

    boolean existsByUserIdAndRepoFullName(String userId, String repoFullName);

    @Modifying
    @Query("UPDATE GitHubReviewConfig c SET c.lastReviewAt = CURRENT_TIMESTAMP, c.reviewCount = c.reviewCount + 1 WHERE c.repoFullName = :repoFullName AND c.enabled = true")
    int incrementReviewCount(@Param("repoFullName") String repoFullName);

    void deleteByIdAndUserId(Long id, String userId);
}
