package com.zwiki.mcpserver.entity;

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
@Table(name = "zwiki_catalogue")
public class Catalogue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "catalogue_id")
    private String catalogueId;

    @Column(name = "parent_catalogue_id")
    private String parentCatalogueId;

    private String name;

    private String title;

    private String prompt;

    @Column(name = "dependent_file")
    private String dependentFile;

    private String children;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    private Integer status;

    @Column(name = "fail_reason")
    private String failReason;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
