package com.zwiki.service;

import com.zwiki.config.ToolRegistration;
import com.zwiki.llm.LoadBalancingChatModel;
import com.zwiki.llm.model.ModelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * @author pai
 * @description: LLM 服务类
 * @date 2026/1/20 00:42
 */
@Service
@Slf4j
public class LlmService {
    private final ChatClient chatClient;
    private final ToolRegistration toolRegistration;

    public LlmService(ChatClient.Builder chatClientBuilder, ToolRegistration toolRegistration) {
        this.chatClient = chatClientBuilder.build();
        this.toolRegistration = toolRegistration;
    }

    public String callWithTools(String query){
        return chatClient
                .prompt(query)
                .advisors(
                    a->a.param(CONVERSATION_ID, UUID.randomUUID().toString())
                )
                .options(ToolCallingChatOptions.builder().toolCallbacks(toolRegistration.getAllTools()).build())
                .call()
                .content();
    }

    public String callWithoutTools(String query) {
        return chatClient
                .prompt(query)
                .advisors(
                        a -> a.param(CONVERSATION_ID, cn.hutool.core.lang.UUID.randomUUID().toString())
                )
                .call()
                .content();
    }
    public Flux<String> chatWithTools(String query, String conversationId) {
        return chatClient
                .prompt(query)
                .advisors(a -> a.param(CONVERSATION_ID,
                        StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString()))
                .options(
                        ToolCallingChatOptions.builder().toolCallbacks(toolRegistration.getAllTools()).build())
                .stream()
                .content();
    }

    /**
     * 指定模型调用（带工具）。modelName 为 null 时走负载均衡。
     */
    public String callWithToolsUsingModel(String query, String modelName) {
        if (StringUtils.hasText(modelName)) {
            LoadBalancingChatModel.setExplicitModel(modelName);
        }
        return callWithTools(query);
    }

    /**
     * 指定模型调用（不带工具）。modelName 为 null 时走负载均衡。
     */
    public String callWithoutToolsUsingModel(String query, String modelName) {
        if (StringUtils.hasText(modelName)) {
            LoadBalancingChatModel.setExplicitModel(modelName);
        }
        return callWithoutTools(query);
    }

    /**
     * 按模型类型调用LLM，通过负载均衡策略自动选择该类型下的模型
     *
     * @param query 用户输入的查询内容
     * @param type 模型类型(TEXT/IMAGE/VOICE/VIDEO/MULTIMODAL)
     * @param conversationId 会话标识，为空时自动生成
     * @return Flux<String> 流式响应数据
     */
    public Flux<String> chatWithType(String query, ModelType type, String conversationId) {
        LoadBalancingChatModel.setRequestedModelType(type);
        return chatClient
                .prompt(query)
                .advisors(a -> a.param(CONVERSATION_ID,
                        StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString()))
                .options(
                        ToolCallingChatOptions.builder()
                                .toolCallbacks(toolRegistration.getAllTools())
                                .build()
                )
                .stream()
                .content();
    }

}
