package com.zwiki.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SymbolReference {

    /** Relative file path within the project */
    private String filePath;

    /** 1-based line number */
    private int lineNumber;

    /** The full line content (trimmed) */
    private String lineContent;
}
