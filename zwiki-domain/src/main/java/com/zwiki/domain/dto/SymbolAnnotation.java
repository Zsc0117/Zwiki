package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SymbolAnnotation {

    /** Symbol name (method, class, field, etc.) */
    private String symbol;

    /** 1-based line number of the definition */
    private int line;

    /** Number of usages across the project (excluding the definition itself) */
    private int usageCount;

    /** KIND: METHOD, FIELD, CLASS, CONSTRUCTOR, FUNCTION */
    private String kind;
}
