package com.zwiki.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 *  任务状态枚举
 *
 * @author: CYM-pai
 * @date: 2026/01/28 17:20
 **/
@Getter
@RequiredArgsConstructor
public enum TaskStatusEnum {
    IN_PROGRESS("pending", "进行中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "处理失败");

    @JsonValue
    private final String code;

    private final String desc;
}
