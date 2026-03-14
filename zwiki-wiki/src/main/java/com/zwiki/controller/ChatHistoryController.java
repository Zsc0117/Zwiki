package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.ChatMessage;
import com.zwiki.repository.dao.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * @author pai
 * @description: 聊天历史控制器
 * @date 2026/1/22
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatMessageRepository chatMessageRepository;

    /**
     * 获取聊天历史记录
     * - 有 taskId：返回该用户+该任务的对话
     * - 无 taskId：返回该用户的通用聊天对话
     */
    @GetMapping
    public ResultVo<List<ChatMessage>> getHistory(
            @RequestParam("userId") String userId,
            @RequestParam(value = "taskId", required = false) String taskId) {
        if (!StringUtils.hasText(userId)) {
            return ResultVo.error("userId is required");
        }
        List<ChatMessage> messages;
        if (StringUtils.hasText(taskId)) {
            messages = chatMessageRepository.findByUserIdAndTaskIdOrderByCreatedAtAsc(userId, taskId);
        } else {
            messages = chatMessageRepository.findByUserIdAndTaskIdIsNullOrderByCreatedAtAsc(userId);
        }
        return ResultVo.success(messages);
    }

    /**
     * 保存一条聊天消息
     */
    @PostMapping
    public ResultVo<ChatMessage> saveMessage(@RequestBody ChatMessage message) {
        if (!StringUtils.hasText(message.getUserId())) {
            return ResultVo.error("userId is required");
        }
        if (!StringUtils.hasText(message.getRole()) || !StringUtils.hasText(message.getContent())) {
            return ResultVo.error("role and content are required");
        }

        if (!StringUtils.hasText(message.getConversationId())) {
            message.setConversationId(newConversationId());
        }

        ChatMessage saved = chatMessageRepository.save(message);
        return ResultVo.success(saved);
    }

    /**
     * 批量保存聊天消息（用于前端一次性同步多条）
     */
    @PostMapping("/batch")
    public ResultVo<Void> saveBatch(@RequestBody List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return ResultVo.success();
        }

        String batchConversationId = null;
        for (ChatMessage m : messages) {
            if (m == null) {
                continue;
            }
            if (!StringUtils.hasText(m.getConversationId())) {
                if (batchConversationId == null) {
                    batchConversationId = newConversationId();
                }
                m.setConversationId(batchConversationId);
            }
        }

        chatMessageRepository.saveAll(messages);
        return ResultVo.success();
    }

    private String newConversationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 清除聊天历史
     */
    @DeleteMapping
    public ResultVo<Void> clearHistory(
            @RequestParam("userId") String userId,
            @RequestParam(value = "taskId", required = false) String taskId) {
        if (!StringUtils.hasText(userId)) {
            return ResultVo.error("userId is required");
        }
        if (StringUtils.hasText(taskId)) {
            chatMessageRepository.deleteByUserIdAndTaskId(userId, taskId);
        } else {
            chatMessageRepository.deleteByUserIdAndTaskIdIsNull(userId);
        }
        log.info("Chat history cleared: userId={}, taskId={}", userId, taskId);
        return ResultVo.success();
    }
}
