package com.zwiki.common.result;

import lombok.Getter;

/**
 * 响应状态码枚举
 */
@Getter
public enum ResultCode {
    
    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    
    // 参数错误 4xx
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    
    // 业务错误 5xx
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),
    
    // 业务自定义错误 1xxx
    TASK_NOT_FOUND(1001, "任务不存在"),
    TASK_CREATE_FAILED(1002, "任务创建失败"),
    FILE_UPLOAD_FAILED(1003, "文件上传失败"),
    GIT_CLONE_FAILED(1004, "Git仓库克隆失败"),
    
    // AI相关错误 2xxx
    AI_CALL_FAILED(2001, "AI调用失败"),
    AI_TIMEOUT(2002, "AI调用超时"),
    
    // 记忆系统错误 3xxx
    MEMORY_INDEX_FAILED(3001, "记忆索引失败"),
    MEMORY_SEARCH_FAILED(3002, "记忆检索失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
