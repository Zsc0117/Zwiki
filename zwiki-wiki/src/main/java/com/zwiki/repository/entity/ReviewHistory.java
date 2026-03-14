package com.zwiki.repository.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "zwiki_review_history")
public class ReviewHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "repo_full_name")
    private String repoFullName;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "overall_rating")
    private String overallRating;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "comment_count")
    private Integer commentCount;

    @Column(name = "error_count")
    private Integer errorCount;

    @Column(name = "warning_count")
    private Integer warningCount;

    @Column(name = "info_count")
    private Integer infoCount;

    @Column(name = "review_detail", columnDefinition = "JSON")
    private String reviewDetail;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
