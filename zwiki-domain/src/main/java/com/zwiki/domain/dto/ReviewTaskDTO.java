package com.zwiki.domain.dto;

/**
 * 瀹℃煡浠诲姟 DTO
 * 
 * 鏍稿績浠诲姟瀵硅薄锛屽寘repoFullName, prNumber, diffUrl 绛変俊鎭紝鍦ㄧ郴缁熷唴閮ㄦ祦 */
public record ReviewTaskDTO(
        String repositoryFullName,
        Integer prNumber,
        String diffUrl,
        String prTitle,
        String prAuthor,
        String headSha,
        String baseSha,
        String cloneUrl,
        String githubToken
) {
    
    /**
     * 鏋勫缓鍣ㄦā寮忓垱寤篟eviewTaskDTO
     */
    public static class Builder {
        private String repositoryFullName;
        private Integer prNumber;
        private String diffUrl;
        private String prTitle;
        private String prAuthor;
        private String headSha;
        private String baseSha;
        private String cloneUrl;
        private String githubToken;
        
        public Builder repositoryFullName(String repositoryFullName) {
            this.repositoryFullName = repositoryFullName;
            return this;
        }
        
        public Builder prNumber(Integer prNumber) {
            this.prNumber = prNumber;
            return this;
        }
        
        public Builder diffUrl(String diffUrl) {
            this.diffUrl = diffUrl;
            return this;
        }
        
        public Builder prTitle(String prTitle) {
            this.prTitle = prTitle;
            return this;
        }
        
        public Builder prAuthor(String prAuthor) {
            this.prAuthor = prAuthor;
            return this;
        }
        
        public Builder headSha(String headSha) {
            this.headSha = headSha;
            return this;
        }
        
        public Builder baseSha(String baseSha) {
            this.baseSha = baseSha;
            return this;
        }
        
        public Builder cloneUrl(String cloneUrl) {
            this.cloneUrl = cloneUrl;
            return this;
        }
        
        public Builder githubToken(String githubToken) {
            this.githubToken = githubToken;
            return this;
        }
        
        public ReviewTaskDTO build() {
            return new ReviewTaskDTO(
                repositoryFullName, 
                prNumber, 
                diffUrl, 
                prTitle, 
                prAuthor, 
                headSha, 
                baseSha, 
                cloneUrl,
                githubToken
            );
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
} 
