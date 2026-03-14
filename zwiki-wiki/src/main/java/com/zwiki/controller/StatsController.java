package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.dao.LlmModelRepository;
import com.zwiki.repository.dao.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pai
 * @description: 统计信息控制器，提供统计信息的接口
 * @date 2026/2/3
 */
@Slf4j
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final TaskRepository taskMapper;
    private final LlmModelRepository llmModelRepository;
    private final ChatMessageRepository chatMessageRepository;

    @GetMapping("/overview")
    public ResultVo<Map<String, Object>> getOverview() {
        Map<String, Object> stats = new HashMap<>();

        // Task stats
        long totalTasks = taskMapper.count();
        stats.put("totalTasks", totalTasks);

        // LLM model stats
        List<Map<String, Object>> modelStats = llmModelRepository.getModelStats();
        stats.put("modelStats", modelStats);

        long totalTokens = llmModelRepository.getTotalTokensUsed();
        stats.put("totalTokensUsed", totalTokens);

        long totalModels = llmModelRepository.count();
        stats.put("totalModels", totalModels);

        // Chat stats
        long totalChatMessages = chatMessageRepository.count();
        stats.put("totalChatMessages", totalChatMessages);

        return ResultVo.success(stats);
    }
}
