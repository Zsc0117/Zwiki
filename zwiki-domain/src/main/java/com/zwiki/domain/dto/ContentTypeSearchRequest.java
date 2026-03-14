package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentTypeSearchRequest {

    private String repositoryId;

    private String userId;

    private String query;

    private String contentType;

    private int limit = 10;
}
