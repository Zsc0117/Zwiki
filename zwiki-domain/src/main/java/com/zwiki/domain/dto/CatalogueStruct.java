package com.zwiki.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * @author pai
 * @description: 目录结构
 * @date 2026/1/22 22:37
 */
@Data
public class CatalogueStruct {
    private List<Item> items;

    @Data
    public static class Item {
        private String title;
        private String name;
        private List<String> dependent_file;
        private String prompt;
        private List<Item> children; // 修改为支持递归嵌套的 Item 类型
    }
}