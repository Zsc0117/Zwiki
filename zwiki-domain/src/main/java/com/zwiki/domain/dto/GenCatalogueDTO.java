package com.zwiki.domain.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author pai
 * @description: 目录生成结果
 * @date 2026/1/20 23:21
 */
@Data
@AllArgsConstructor
public class GenCatalogueDTO<T> {
    private CatalogueStruct catalogueStruct;

    private List<T> catalogueList;
}
