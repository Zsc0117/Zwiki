package com.zwiki.service;

import com.zwiki.llm.LoadBalancingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于LLM的意图识别服务，替代硬编码关键词匹配。
 * 将用户查询分类为 chat / project / code_qa 三种意图。
 */
@Slf4j
@Service
public class IntentClassifier {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private static final String INTENT_PROMPT = """
            你是一个意图分类器，负责判断用户消息属于以下哪种意图。只允许输出一个英文单词，不要输出任何解释。

            ## 意图类型

            ### chat
            通用智能助手对话。适用于：
            - 日常闲聊、问候、自我介绍
            - 通用知识问答（技术、学习、生活等）
            - 需要联网搜索的实时信息（天气、新闻、时间等）
            - 使用平台MCP工具的请求，例如：
              · 列出已分析的项目（list_projects）
              · 搜索wiki文档（search_wiki）
              · 查看项目报告（get_project_report）
              · 获取wiki文档内容（get_wiki_document）
            - 提及"zwiki"、"ZwikiAI 智能助手"但不涉及具体项目源码分析的对话
            - 绘图请求（画流程图、类图、ER图、架构图等，使用draw.io工具）
            - 图片/视频生成请求
            - 任何不涉及特定项目源码深入分析的问题

            ### project
            针对特定代码仓库的深度分析。适用于：
            - 分析某个项目的源码结构、架构、技术栈
            - 阅读/搜索项目中的具体文件和代码
            - 基于项目源码回答问题（如"这个项目的认证是怎么实现的"）
            - 需要 readFile、searchContent、listTree 等文件系统工具的请求
            - 明确指定了项目或仓库，且需要深入代码层面分析

            ### code_qa
            用户直接提供了代码片段并要求分析。适用于：
            - 用户在消息中粘贴了一段代码并要求解释、优化、找bug
            - 消息中包含明显的代码块（```代码```）并围绕该代码提问
            - 不需要访问任何外部项目，纯粹基于用户提供的代码回答

            ## 判断规则
            1. 如果用户提供了代码片段并围绕该代码提问 → code_qa
            2. 如果用户明确要求分析某个项目的源码（需要读取文件、搜索代码） → project
            3. 其他所有情况（包括使用平台工具、闲聊、绘图、搜索wiki等） → chat

            ## 重要
            - "使用zwiki"、"列出项目"、"搜索文档"等使用平台功能的请求 → chat（使用MCP工具）
            - "分析这个项目的代码"、"这个项目怎么实现的" → project（需要读源码）
            - 只输出: chat 或 project 或 code_qa
            """;

    /**
     * 使用LLM对用户查询进行意图分类。
     *
     * @param query 用户输入
     * @return "chat" / "project" / "code_qa"，失败时返回 "chat" 作为安全默认值
     */
    public String classify(String query) {
        if (!StringUtils.hasText(query)) {
            return "chat";
        }

        try {
            LoadBalancingChatModel.setEnableWebSearch(false);
            String result = chatClientBuilder
                    .build()
                    .prompt()
                    .system(INTENT_PROMPT)
                    .user(query)
                    .call()
                    .content();

            if (!StringUtils.hasText(result)) {
                log.debug("Intent classifier returned empty, defaulting to chat");
                return "chat";
            }

            String normalized = result.trim().toLowerCase();
            if (normalized.contains("project")) {
                return "project";
            }
            if (normalized.contains("code_qa")) {
                return "code_qa";
            }
            if (normalized.contains("chat")) {
                return "chat";
            }

            log.debug("Intent classifier returned unexpected value '{}', defaulting to chat", result.trim());
            return "chat";
        } catch (Exception e) {
            log.warn("Intent classification failed: {}, defaulting to chat", e.getMessage());
            return "chat";
        } finally {
            LoadBalancingChatModel.clearAllThreadLocals();
        }
    }
}
