package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchResultDto {

    private String id;

    private String content;

    private Double score;

    private String type;

    private String name;

    private Map<String, Object> metadata;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResponse {
        private List<MemorySearchResultDto> results;
        private int totalCount;
    }
}
