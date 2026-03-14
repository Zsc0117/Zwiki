package com.zwiki.exception;

/**
 * 瀹℃煡澶勭悊寮傚父 * 
 * 鐢ㄤ簬澶勭悊浠ｇ爜瀹℃煡杩囩▼涓殑寮傚父
 */
public class ReviewProcessingException extends RuntimeException {

    private final String repositoryName;
    private final Integer prNumber;

    public ReviewProcessingException(String message) {
        super(message);
        this.repositoryName = null;
        this.prNumber = null;
    }

    public ReviewProcessingException(String message, Throwable cause) {
        super(message, cause);
        this.repositoryName = null;
        this.prNumber = null;
    }

    public ReviewProcessingException(String repositoryName, Integer prNumber, String message) {
        super(message);
        this.repositoryName = repositoryName;
        this.prNumber = prNumber;
    }

    public ReviewProcessingException(String repositoryName, Integer prNumber, String message, Throwable cause) {
        super(message, cause);
        this.repositoryName = repositoryName;
        this.prNumber = prNumber;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public Integer getPrNumber() {
        return prNumber;
    }

    @Override
    public String getMessage() {
        if (repositoryName != null && prNumber != null) {
            return String.format("[%s#%d] %s", repositoryName, prNumber, super.getMessage());
        }
        return super.getMessage();
    }
} 
