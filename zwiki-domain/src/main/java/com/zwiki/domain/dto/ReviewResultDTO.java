package com.zwiki.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 瀹℃煡缁撴灉 DTO
 * 
 * 鍖呭惈涓€List<ReviewCommentDTO>锛岀敤BeanOutputParser 瑙ｆ瀽LLM鐨勭粨鏋勫寲杈撳嚭
 */
public record ReviewResultDTO(
        @JsonProperty("comments")
        List<ReviewCommentDTO> comments,
        
        @JsonProperty("summary")
        String summary,
        
        @JsonProperty("overall_rating")
        String overallRating
) {
    
    /**
     * 鏁翠綋璇勭骇鏋氫妇
     */
    public enum OverallRating {
        EXCELLENT("excellent"),
        GOOD("good"),
        NEEDS_IMPROVEMENT("needs_improvement"),
        POOR("poor");
        
        private final String value;
        
        OverallRating(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    /**
     * 鏋勫缓鍣ㄦā寮忓垱寤篟eviewResultDTO
     */
    public static class Builder {
        private List<ReviewCommentDTO> comments;
        private String summary;
        private String overallRating = OverallRating.GOOD.getValue();
        
        public Builder comments(List<ReviewCommentDTO> comments) {
            this.comments = comments;
            return this;
        }
        
        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }
        
        public Builder overallRating(String overallRating) {
            this.overallRating = overallRating;
            return this;
        }
        
        public Builder overallRating(OverallRating overallRating) {
            this.overallRating = overallRating.getValue();
            return this;
        }
        
        public ReviewResultDTO build() {
            return new ReviewResultDTO(comments, summary, overallRating);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
} 
