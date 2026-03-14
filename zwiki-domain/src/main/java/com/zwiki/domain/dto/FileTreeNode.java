package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileTreeNode {

    private String name;

    private String path;

    /** "file" or "directory" */
    private String type;

    private Long size;

    private List<FileTreeNode> children;
}
