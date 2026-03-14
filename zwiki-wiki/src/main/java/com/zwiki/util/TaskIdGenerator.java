package com.zwiki.util;

/**
 * @author pai
 * @description: 任务id生成
 * @date 2026/1/15 16:57
 */
public class TaskIdGenerator {
    public static String generate() {
        return "TASK_" + System.currentTimeMillis();
    }

}
