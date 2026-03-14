package com.zwiki.repository.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zwiki.domain.dto.CatalogueStruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author pai
 * @description: 文档生成任务模型
 * @date 2026/2/5
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentGenerationTask {
    /**
     * 任务唯一ID
     */
    private String taskId;
    
    /**
     * 目录ID
     */
    private String catalogueId;
    
    /**
     * 目录名称
     */
    private String catalogueName;
    
    /**
     * 生成提示词
     */
    private String prompt;
    
    /**
     * 项目本地路径
     */
    private String localPath;
    
    /**
     * 文件树结构
     */
    private String fileTree;
    
    /**
     * 目录结构
     */
    private CatalogueStruct catalogueStruct;
    
    /**
     * 处理失败重试次数
     */
    private Integer retryCount = 0;
    
    /**
     * 许可获取重试次数（与处理失败重试分开计数）
     */
    private Integer permitRetryCount = 0;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 任务优先级
     */
    private String priority = "NORMAL";
    
    /**
     * 项目名称（用于Memory服务索引）
     */
    private String projectName;
    
    /**
     * 全部章节名列表（用于v5 prompt跨章节上下文）
     */
    private List<String> allCatalogueNames;
    
    /**
     * 用户ID（用于在异步线程中恢复用户上下文）
     */
    private String userId;
    
    /**
     * 创建文档生成任务
     * @param catalogue 目录实体
     * @param fileTree 文件树
     * @param catalogueStruct 目录结构
     * @param localPath 本地路径
     * @return 文档生成任务
     */
    public static DocumentGenerationTask create(Catalogue catalogue, String fileTree, 
                                              CatalogueStruct catalogueStruct, String localPath) {
        DocumentGenerationTask task = new DocumentGenerationTask();
        task.setTaskId(catalogue.getTaskId());
        task.setCatalogueId(catalogue.getCatalogueId());
        task.setCatalogueName(catalogue.getName());
        task.setPrompt(catalogue.getPrompt());
        task.setLocalPath(localPath);
        task.setFileTree(fileTree);
        task.setCatalogueStruct(catalogueStruct);
        task.setRetryCount(0);
        task.setCreateTime(LocalDateTime.now());
        task.setPriority("NORMAL");
        // projectName将在CatalogueServiceImpl中设置
        return task;
    }
    
    /**
     * 增加重试次数
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    /**
     * 是否超过最大重试次数
     */
    public boolean exceedsMaxRetries(int maxRetries) {
        return this.retryCount > maxRetries;
    }
    
    /**
     * 增加许可获取重试次数
     */
    public void incrementPermitRetryCount() {
        this.permitRetryCount++;
    }
    
    /**
     * 是否超过最大许可获取重试次数
     */
    public boolean exceedsMaxPermitRetries(int maxPermitRetries) {
        return this.permitRetryCount > maxPermitRetries;
    }
}