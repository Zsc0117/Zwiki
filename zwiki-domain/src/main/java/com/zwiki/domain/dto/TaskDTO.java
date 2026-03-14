package com.zwiki.domain.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDTO implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String taskId;
    private String projectName;
    private String userName;
    private String sourceType;
    private String gitUrl;
    private String status;
    private String projectPath;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
