package com.zwiki.exception;

/**
 * API寮傚父 * 
 * 鐢ㄤ簬澶勭悊API璋冪敤鐩稿叧鐨勫紓 */
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    public ApiException(String message) {
        super(message);
        this.errorCode = "API_ERROR";
        this.httpStatus = 500;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "API_ERROR";
        this.httpStatus = 500;
    }

    public ApiException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ApiException(String errorCode, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
} 
