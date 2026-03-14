package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewContextRequest {

    private String repositoryId;

    private String diffContent;

    private String prTitle;

    private String prDescription;

    private List<String> changedFiles;
}
