package com.zwiki.domain.vo;
import lombok.Data;

/**
 * @author pai
 * @description: TODO
 * @date 2026/1/23 16:10
 */
@Data
public class TaskVo {
    private Long id;
    private String taskId;
    private String userId;
    private String projectName;
    private String projectUrl;
    private String userName;
    private String sourceType;
    private String projectPath;
    private String status;
    private String failReason;
    private String createTime;
    private String updateTime;
}
