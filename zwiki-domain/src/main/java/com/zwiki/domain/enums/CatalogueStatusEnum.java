package com.zwiki.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 *    目录构建状态枚举
 *
 * @author: CYM-pai
 * @date: 2026/01/28 17:20
 **/
@Getter
@RequiredArgsConstructor
public enum CatalogueStatusEnum {
    IN_PROGRESS(1, "进行中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "处理失败");

    private final Integer code;

    @JsonValue
    private final String desc;

}
