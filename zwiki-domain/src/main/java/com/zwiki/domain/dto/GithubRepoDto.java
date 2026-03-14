package com.zwiki.domain.dto;

import lombok.Data;

@Data
public class GithubRepoDto {

    private String id;

    private String name;

    private String fullName;

    private String ownerLogin;

    private boolean isPrivate;

    private String description;

    private String language;

    private Integer stargazersCount;

    private String cloneUrl;

    private String updatedAt;
}
