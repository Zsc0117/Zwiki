package com.zwiki.common.result;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应结果封装
 */
@Data
@NoArgsConstructor
public class ResultVo<T> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private int code;
    private String message;
    private T data;
    private long timestamp;

    public ResultVo(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ResultVo<T> success() {
        return new ResultVo<>(200, "success", null);
    }

    public static <T> ResultVo<T> success(T data) {
        return new ResultVo<>(200, "success", data);
    }

    public static <T> ResultVo<T> success(String message, T data) {
        return new ResultVo<>(200, message, data);
    }

    public static <T> ResultVo<T> error(String message) {
        return new ResultVo<>(500, message, null);
    }

    public static <T> ResultVo<T> error(int code, String message) {
        return new ResultVo<>(code, message, null);
    }

    public static <T> ResultVo<T> error(ResultCode resultCode) {
        return new ResultVo<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
}
