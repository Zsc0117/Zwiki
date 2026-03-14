package com.zwiki.domain.param;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * @author pai
 * @description: 分页查询参数
 * @date 2025/7/23 16:11
 */
@Data
public class ListPageParams {

    @JsonAlias("page")
    private Integer pageIndex = 1;

    @JsonAlias("size")
    private Integer pageSize = 10;

    @JsonAlias("keyword")
    private String projectName;

    private String taskId;

    private String userId;

    private String userName;

}
