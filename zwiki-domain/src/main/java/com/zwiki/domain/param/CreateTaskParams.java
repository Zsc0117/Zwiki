package com.zwiki.domain.param;

import lombok.Data;

/**
 * @author pai
 * @description: TODO
 * @date 2025/7/20 18:58
 */
@Data
public class CreateTaskParams {

    private String projectName;

    private String projectUrl;

    private String branch;

    private String userName;

    private String password;

    private String creatorUserId;

    private String gitUserName;

    private String gitPassword;

    // git或zip
    private String sourceType;

}
