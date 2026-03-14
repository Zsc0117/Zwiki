package com.zwiki.controller;

import com.zwiki.repository.dao.ChatMessageRepository;
import com.zwiki.util.AuthUtil;
import com.zwiki.repository.entity.ChatMessage;
import com.zwiki.service.auth.SaTokenUserContext;
import com.zwiki.util.FileSystemTool;
import com.zwiki.service.LlmService;
import com.zwiki.service.MemoryRagService;
import com.zwiki.repository.entity.Task;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.service.IntentClassifier;
import com.zwiki.service.SceneModelResolver;
import lombok.extern.slf4j.Slf4j;
import com.zwiki.llm.LoadBalancingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import com.zwiki.config.ToolRegistration;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/chat")
class ChatController {
    private static final MediaType UTF8_TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ToolRegistration toolRegistration;

    @Autowired
    private LlmService llmService;

    @Autowired
    private TaskRepository taskMapper;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private MemoryRagService memoryRagService;

    @Autowired
    private IntentClassifier intentClassifier;

    @Autowired
    private SceneModelResolver sceneModelResolver;

    @Value("${zwiki.chat.default-project-task-id:TASK_1771759644543}")
    private String defaultProjectTaskId;

    private final ScheduledExecutorService sseKeepaliveScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-keepalive");
                t.setDaemon(true);
                return t;
            });

    @GetMapping(value="/call", produces = "application/json;charset=UTF-8")
    public String callChat(
            @RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query,
            @RequestParam(value = "intent", defaultValue = "chat") String intent,
            @RequestParam(value = "projectRoot", required = false) String projectRoot,
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestParam(value = "userId", required = false) String userId) {

        String resolvedIntent = resolveIntent(intent, query);
        String resolvedTaskId = resolveTaskIdForIntent(resolvedIntent, taskId);
        boolean isAutoIntent = "auto".equalsIgnoreCase(intent != null ? intent.trim() : "");
        if ("project".equalsIgnoreCase(resolvedIntent) && !StringUtils.hasText(resolvedTaskId) && isAutoIntent) {
            log.info("Auto intent resolved to project but taskId is missing; fallback to chat. query='{}'", query);
            resolvedIntent = "chat";
            resolvedTaskId = taskId;
        }
        boolean isProjectIntent = "project".equalsIgnoreCase(resolvedIntent);

        log.info("Chat call started: intent={}, resolvedIntent={}, query length={}, taskId={}", intent, resolvedIntent, query.length(), resolvedTaskId);

        if (!isProjectIntent) {
            return handleChatIntent(query);
        }

        if (!StringUtils.hasText(resolvedTaskId)) {
            return "项目模式尚未配置固定 taskId，请在配置中设置 zwiki.chat.default-project-task-id。";
        }

        return handleProjectIntent(query, projectRoot, resolvedTaskId, userId);
    }

    private String resolveIntent(String intent, String query) {
        if (StringUtils.hasText(intent)) {
            String normalized = intent.trim().toLowerCase();
            // 前端明确指定了非auto意图（如 RepoDetail 页面固定传 project），直接使用
            if (!"auto".equals(normalized) && !"chat".equals(normalized)) {
                return normalized;
            }
        }
        // intent 为空、"chat"、"auto" 时，均通过 LLM 进行意图识别
        return intentClassifier.classify(query);
    }

    private String resolveTaskIdForIntent(String resolvedIntent, String taskId) {
        if (!"project".equalsIgnoreCase(resolvedIntent)) {
            return taskId;
        }
        if (StringUtils.hasText(taskId)) {
            return taskId;
        }
        if (StringUtils.hasText(defaultProjectTaskId)) {
            return defaultProjectTaskId.trim();
        }
        return null;
    }

    private List<Map<String, String>> normalizeHistory(Object historyObj) {
        if (!(historyObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }
        List<Map<String, String>> history = new ArrayList<>();
        int start = Math.max(0, rawList.size() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (item instanceof Map<?, ?> map) {
                Map<String, String> msg = new java.util.HashMap<>();
                Object roleObj = map.get("role");
                Object contentObj = map.get("content");
                msg.put("role", roleObj != null ? String.valueOf(roleObj) : "");
                msg.put("content", contentObj != null ? String.valueOf(contentObj) : "");
                if (StringUtils.hasText(msg.get("role")) && StringUtils.hasText(msg.get("content"))) {
                    history.add(msg);
                }
            }
        }
        return history.isEmpty() ? null : history;
    }

    private List<Map<String, String>> resolveConversationHistory(List<Map<String, String>> history, String userId, String taskId) {
        if (history != null && !history.isEmpty()) {
            return history;
        }
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        List<ChatMessage> storedMessages = StringUtils.hasText(taskId)
                ? chatMessageRepository.findTop10ByUserIdAndTaskIdOrderByCreatedAtDesc(userId, taskId)
                : chatMessageRepository.findTop10ByUserIdAndTaskIdIsNullOrderByCreatedAtDesc(userId);
        if (storedMessages == null || storedMessages.isEmpty()) {
            return null;
        }
        Collections.reverse(storedMessages);
        List<Map<String, String>> resolvedHistory = new ArrayList<>();
        for (ChatMessage storedMessage : storedMessages) {
            if (storedMessage == null || !StringUtils.hasText(storedMessage.getRole())
                    || !StringUtils.hasText(storedMessage.getContent())) {
                continue;
            }
            Map<String, String> msg = new java.util.HashMap<>();
            msg.put("role", storedMessage.getRole());
            msg.put("content", storedMessage.getContent());
            resolvedHistory.add(msg);
        }
        if (resolvedHistory.isEmpty()) {
            return null;
        }
        log.info("No history provided by client, loaded {} messages from database for context continuity", resolvedHistory.size());
        return resolvedHistory;
    }


    private String handleChatIntent(String query) {
        try {
            // 解析智能助手场景模型
            String userId = AuthUtil.getCurrentUserId();
            String sceneModel = sceneModelResolver.resolve(userId, SceneModelResolver.Scene.ASSISTANT);
            if (sceneModel != null) {
                LoadBalancingChatModel.setExplicitModel(sceneModel);
            }
            // 启用 DashScope 原生联网搜索（对支持的 Qwen 模型生效，其他模型自动忽略）
            LoadBalancingChatModel.setEnableWebSearch(true);

            ToolCallback[] mcpTools = toolRegistration.getMcpTools();
            log.info("Chat(chat intent): mcpTools={}, enableWebSearch=true", mcpTools.length);

            long startTime = System.currentTimeMillis();
            ChatClient.Builder builder = chatClientBuilder;
            if (mcpTools.length > 0) {
                builder = builder.defaultOptions(
                        ToolCallingChatOptions.builder()
                                .toolCallbacks(mcpTools)
                                .build()
                );
            }
            ChatClient.CallResponseSpec callResponse = builder
                    .build()
                    .prompt()
                    .system(buildChatSystemPrompt(toolRegistration.hasToolNamed("open_drawio_mermaid")))
                    .user(query)
                    .call();

            String content = extractContent(callResponse);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Chat(chat intent) completed: elapsed={}ms, responseLength={}", elapsed, content != null ? content.length() : 0);
            return content;
        } catch (Exception e) {
            log.error("Chat(chat intent) failed: error={}", e.getMessage(), e);
            return "抱歉，AI服务暂时出现问题，请稍后再试。(" + e.getClass().getSimpleName() + ")";
        } finally {
            LoadBalancingChatModel.clearAllThreadLocals();
        }
    }

    private String handleProjectIntent(String query, String projectRoot, String taskId, String userId) {
        // 解析项目问答场景模型
        String currentUserId = StringUtils.hasText(userId) ? userId : AuthUtil.getCurrentUserId();
        String sceneModel = sceneModelResolver.resolve(currentUserId, SceneModelResolver.Scene.CHAT);
        if (sceneModel != null) {
            LoadBalancingChatModel.setExplicitModel(sceneModel);
        }
        String rootToUse = null;
        if (projectRoot != null && !projectRoot.trim().isEmpty()) {
            rootToUse = projectRoot.trim();
        } else if (taskId != null && !taskId.trim().isEmpty()) {
            Task task = taskMapper.findFirstByTaskId(taskId.trim()).orElse(null);
            if (task != null && task.getProjectPath() != null && !task.getProjectPath().trim().isEmpty()) {
                rootToUse = task.getProjectPath().trim();
            }
        }
        if (rootToUse == null) {
            rootToUse = System.getProperty("user.dir");
        }

        try {
            FileSystemTool.setProjectRoot(rootToUse);
            log.info("Chat(project intent): taskId={}, projectRoot={}", taskId, rootToUse);

            String ragContext = memoryRagService.buildRagContextForTask(taskId, query, userId, rootToUse);
            log.info("RAG context: length={}, empty={}", ragContext != null ? ragContext.length() : 0, ragContext == null || ragContext.isEmpty());
            String finalPrompt = memoryRagService.injectContextIntoUserPrompt(query, ragContext);

            // 诊断日志：检查可用工具
            ToolCallback[] allTools = toolRegistration.getAllTools();
            boolean hasDrawio = toolRegistration.hasToolNamed("open_drawio_mermaid");
            log.info("🔧 可用工具数量: {}, hasDrawio: {}", allTools.length, hasDrawio);
            for (ToolCallback t : allTools) {
                try {
                    log.info("  - Tool: {}", t.getToolDefinition().name());
                } catch (Exception e) {
                    log.warn("  - Tool定义获取失败: {}", e.getMessage());
                }
            }

            String systemPrompt = buildProjectSystemPrompt(rootToUse, hasDrawio);

            long startTime = System.currentTimeMillis();
            ChatClient.CallResponseSpec callResponse = chatClientBuilder
                    .defaultOptions(ToolCallingChatOptions.builder()
                            .toolCallbacks(toolRegistration.getAllTools())
                            .build()
                    )
                    .build()
                    .prompt()
                    .system(systemPrompt)
                    .user(finalPrompt)
                    .call();

            String content = extractContent(callResponse);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Chat(project intent) completed: elapsed={}ms, responseLength={}", elapsed, content != null ? content.length() : 0);
            return content;
        } catch (Exception e) {
            log.error("Chat(project intent) failed: query='{}', taskId={}, error={}", query, taskId, e.getMessage(), e);
            return "抱歉，AI服务暂时出现问题，请稍后再试。(" + e.getClass().getSimpleName() + ")";
        } finally {
            FileSystemTool.clearProjectRoot();
            LoadBalancingChatModel.clearAllThreadLocals();
        }
    }


    private String buildChatSystemPrompt(boolean hasDrawioTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ZwikiAI 智能助手，负责帮助用户快速理解平台能力、项目知识、代码问题和学习内容。\n")
          .append("你熟悉 ZwikiAI 平台的核心功能，包括项目分析、代码审查、文档生成、图表生成、论文预览和知识检索。\n")
          .append("你的回答会显示在一个小型聊天窗口中，请保持专业、友好、直接、克制。\n\n")
          .append("## 可用工具\n")
          .append("你拥有联网搜索工具（WebSearch），请在以下场景主动调用：\n")
          .append("- 用户询问实时信息：当前时间、日期、天气、新闻、股价、赛事比分等\n")
          .append("- 用户询问你不确定或可能过时的信息\n")
          .append("- 用户明确要求搜索或查询最新信息\n")
          .append("遇到这些场景时，必须先调用搜索工具获取实时数据，再基于搜索结果回答。\n")
          .append("不要凭记忆猜测实时信息（如时间、日期），这样会给出错误答案。\n\n");
        if (hasDrawioTools) {
            sb.append("## Draw.io 绘图工具\n")
              .append("你拥有 Draw.io 绘图工具，可以创建各种专业图表。当用户请求绘图、画图、制图时，请主动使用：\n")
              .append("- open_drawio_mermaid(mermaid) - 用 Mermaid 语法创建图表（推荐，最简单）\n")
              .append("  支持：flowchart（流程图）、sequenceDiagram（时序图）、classDiagram（类图）、erDiagram（ER图）、gantt（甘特图）等\n")
              .append("- open_drawio_xml(xml) - 用 Draw.io XML 创建复杂图表\n")
              .append("- open_drawio_csv(csv) - 用 CSV 格式创建图表\n")
              .append("使用后工具会返回一个 draw.io 链接，你必须在回复中原样输出该链接。\n\n");
        }
        sb.append("## 工具调用规则（极其重要）\n")
          .append("- 同一个工具使用相同参数最多调用1次，如果搜索没有结果，不要用相同的关键词重试\n")
          .append("- 搜索无结果时，尝试换一个完全不同的关键词，或者直接基于你已有的知识回答\n")
          .append("- 每次对话中工具调用总次数不要超过5次\n")
          .append("- 如果工具返回错误或限流提示，立即停止调用工具，直接回答用户\n\n")
          .append("## 回答格式要求（必须严格遵守）\n")
          .append("- 使用自然、口语化的中文回答\n")
          .append("- 只输出正文，禁止输出任何 Markdown 标题（如 #、##、###）\n")
          .append("- 禁止使用 **加粗** 语法来强调\n")
          .append("- 用简短的段落和换行来组织内容，不要写大段文字\n")
          .append("- 列表用 - 即可，不要嵌套超过一层\n")
          .append("- 如果使用编号（1. 2. 3.），每一项必须单独一行，严禁在同一行拼接多个编号项\n")
          .append("- 回答要简洁精准，聚焦用户问题，不要铺垫和总结\n")
          .append("- 优先给出结论，再补充必要依据和下一步建议\n\n")
          .append("## 规则\n")
          .append("- 你可以回答各种问题：技术、学习、生活、编程等\n")
          .append("- 保持专业、友好、简洁的语气，不要过度拟人化\n")
          .append("- 如果调用了图片/视频生成工具并返回了以 http/https 开头的链接，你必须在最终回复中逐行原样输出完整链接（不要改写、不要省略、不要只说'点击链接'）\n")
          .append("- 如果不确定答案，坦诚告知");
        return sb.toString();
    }

    private String buildProjectSystemPrompt(String projectRoot, boolean hasDrawioTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能代码仓库分析助手。你正在分析位于 ").append(projectRoot).append(" 的项目。\n")
          .append("你的回答将显示在一个小型聊天窗口中，请注意回答的格式和长度。\n\n")
          .append("## 回答格式要求（必须严格遵守）\n")
          .append("- 使用自然、口语化的中文回答，像同事之间的技术讨论\n")
          .append("- 只输出正文，禁止输出任何 Markdown 标题（如 #、##、###）\n")
          .append("- 禁止使用 **加粗** 语法来强调\n")
          .append("- 用简短的段落和换行来组织内容，不要写大段文字\n")
          .append("- 列表用 - 即可，不要嵌套超过一层\n")
          .append("- 如果使用编号（1. 2. 3.），每一项必须单独一行，严禁在同一行拼接多个编号项\n")
          .append("- 代码引用用 `行内代码` 或简短的 ```代码块```\n")
          .append("- 回答要简洁精准，聚焦用户问题，不要铺垫和总结\n")
          .append("- 直接说结论和关键信息，避免\"首先...其次...最后...\"的套路\n\n")
          .append("## 可用工具\n")
          .append("1. searchContent(keyword, directory?, fileExtension?) - 搜索关键词\n")
          .append("2. listTree(directory?, maxDepth?) - 列出目录树\n")
          .append("3. readFile(filePath) - 读取文件内容\n")
          .append("4. readFileLines(filePath, startLine, endLine) - 读取指定行\n\n");
        if (hasDrawioTools) {
            sb.append("## Draw.io 绘图工具（重要）\n")
              .append("你拥有 Draw.io 绘图工具，当用户请求生成项目相关的图表时，请主动使用：\n")
              .append("- open_drawio_mermaid(mermaid) - 用 Mermaid 语法创建图表（推荐，最简单高效）\n")
              .append("  支持：flowchart/graph（流程图）、sequenceDiagram（时序图）、classDiagram（类图）、erDiagram（ER图）、gantt（甘特图）等\n")
              .append("- open_drawio_xml(xml) - 用 Draw.io XML 创建复杂图表\n")
              .append("- open_drawio_csv(csv) - 用 CSV 格式创建图表\n\n")
              .append("绘图工作流程：\n")
              .append("1. 先用 listTree/searchContent/readFile 分析项目结构和代码\n")
              .append("2. 基于实际代码生成准确的 Mermaid 图表（不要编造不存在的类或方法）\n")
              .append("3. 调用 open_drawio_mermaid 创建可编辑的 Draw.io 图表\n")
              .append("4. 工具会返回一个 draw.io 链接，你必须在回复中原样输出该链接\n\n")
              .append("常见绘图场景：\n")
              .append("- 项目架构图：分析模块依赖关系，用 flowchart 绘制\n")
              .append("- 类图/类关系图：分析核心类的继承和依赖，用 classDiagram 绘制\n")
              .append("- 接口调用时序图：分析 Controller→Service→Repository 调用链，用 sequenceDiagram 绘制\n")
              .append("- 数据库ER图：分析实体类和关联关系，用 erDiagram 绘制\n")
              .append("- 业务流程图：分析业务逻辑，用 flowchart 绘制\n")
              .append("- 部署架构图：分析微服务部署结构，用 flowchart 绘制\n\n");
        }
        sb.append("## 工具调用规则（极其重要，必须严格遵守）\n")
          .append("- 同一个文件只能 readFile 一次，重复读取会被系统拦截\n")
          .append("- 同一个搜索关键词只能 searchContent 一次，重复搜索会被系统拦截\n")
          .append("- 每次对话中工具调用总次数不要超过10次（包括所有工具）\n")
          .append("- 收集到足够信息后立即停止工具调用，开始生成回答\n")
          .append("- 如果工具返回'已在之前读取/搜索过'的提示，立即停止该工具调用\n")
          .append("- 如果用户要求绘图，在收集到必要信息后必须调用 draw.io 绘图工具，不要只输出文本\n\n")
          .append("## 工作流程\n")
          .append("1. 先检查用户消息中的【已知资料】，如果已包含足够代码内容，直接回答\n")
          .append("2. 已知资料不足时，用 searchContent 搜索补充，再用 readFile 读取\n")
          .append("3. 基于实际代码内容回答，引用文件路径和关键代码\n")
          .append("4. 如果用户要求生成图表，收集信息后必须调用 open_drawio_mermaid 工具生成可编辑图表\n\n")
          .append("## 规则\n")
          .append("- 已知资料足够时直接回答，不要重复调用工具读取同一文件\n")
          .append("- 回答必须基于实际代码，不要编造\n")
          .append("- 如果调用了图片/视频/绘图工具并返回了以 http/https 开头的链接，你必须在最终回复中逐行原样输出完整链接（不要改写、不要省略、不要只说'点击链接'）\n")
          .append("- 使用相对路径，工具自动基于项目根目录解析");
        return sb.toString();
    }

    private String buildCodeQaSystemPrompt() {
        return "你是一个代码分析助手。用户会直接提供一段代码并提出问题，请基于用户提供的代码内容直接回答。\n" +
                "你的回答将显示在一个小型聊天窗口中，请注意回答的格式和长度。\n\n" +
                "## 回答格式要求（必须严格遵守）\n" +
                "- 使用自然、口语化的中文回答\n" +
                "- 只输出正文，禁止输出任何 Markdown 标题（如 #、##、###）\n" +
                "- 用简短的段落和换行来组织内容，不要写大段文字\n" +
                "- 列表用 - 即可，不要嵌套超过一层\n" +
                "- 如果使用编号（1. 2. 3.），每一项必须单独一行，严禁在同一行拼接多个编号项\n" +
                "- 代码引用用 `行内代码` 或简短的 ```代码块```\n" +
                "- 回答要简洁精准，聚焦用户问题\n" +
                "- 直接说结论和关键信息\n\n" +
                "## 规则\n" +
                "- 用户已经提供了代码，直接基于代码内容分析和回答\n" +
                "- 不需要搜索或查找其他文件，专注于用户提供的代码片段\n" +
                "- 如果代码不完整，基于已有部分尽力回答，并指出可能需要更多上下文的地方\n" +
                "- 如果调用了图片/视频生成工具并返回了以 http/https 开头的链接，你必须在最终回复中逐行原样输出完整链接（不要改写、不要省略、不要只说‘点击链接’）\n" +
                "- 保持技术准确性，回答要有深度但不啰嗦";
    }

    /**
     * 从ChatClient响应中提取内容，兼容思考模型。
     * 
     * 思考模型（如 kimi-k2-thinking）的 reasoning_content 是内部思考过程（CoT），
     * 不是最终回答。最终回答在 content 字段中。
     * 
     * 优先级：
     * 1. Spring AI getText()（正常解析）
     * 2. HTTP 拦截器截获的 content（Spring AI 解析失败时的后备）
     * 3. 错误提示（不将 reasoning_content 作为回答返回）
     */
    private String extractContent(ChatClient.CallResponseSpec callResponse) {
        try {
            ChatResponse chatResponse = callResponse.chatResponse();
            if (chatResponse == null || chatResponse.getResult() == null) {
                log.warn("ChatResponse or result is null");
                // 尝试 HTTP 拦截器后备
                String httpContent = LoadBalancingChatModel.consumeHttpContent();
                if (StringUtils.hasText(httpContent)) {
                    log.info("ChatResponse null, using HTTP-intercepted content as fallback, length={}", httpContent.length());
                    return httpContent;
                }
                return "抱歉，AI模型未返回有效响应，请重试。";
            }

            var output = chatResponse.getResult().getOutput();
            if (output == null) {
                log.warn("ChatResponse output is null");
                String httpContent = LoadBalancingChatModel.consumeHttpContent();
                if (StringUtils.hasText(httpContent)) {
                    log.info("Output null, using HTTP-intercepted content as fallback, length={}", httpContent.length());
                    return httpContent;
                }
                return "抱歉，AI模型未返回有效响应，请重试。";
            }

            // 1. 优先使用 getText()（正常模型直接返回 content）
            String text = output.getText();
            if (StringUtils.hasText(text)) {
                LoadBalancingChatModel.consumeHttpContent(); // 清理 ThreadLocal
                return text;
            }

            // 2. Spring AI getText() 为空 → 尝试 HTTP 拦截器截获的 content 后备
            //    这是 Spring AI 解析失败时的兜底（如 tool-calling + 思考模型场景）
            String httpContent = LoadBalancingChatModel.consumeHttpContent();
            if (StringUtils.hasText(httpContent)) {
                log.info("Spring AI getText() empty, using HTTP-intercepted content, length={}", httpContent.length());
                return httpContent;
            }

            // 3. content 全部为空，记录 reasoning_content 供调试，但不作为回答返回
            String reasoning = LoadBalancingChatModel.consumeReasoningContent();
            if (StringUtils.hasText(reasoning)) {
                log.warn("LLM content empty, only reasoning_content available (length={}). " +
                        "This is the model's internal thinking process, not the answer. " +
                        "The model may have failed to generate a final response.", reasoning.length());
            }

            log.warn("LLM returned empty content. Metadata keys: {}",
                    output.getMetadata() != null ? output.getMetadata().keySet() : "null");
            return "抱歉，AI模型未能生成回答（思考模型可能未产生最终回复），请重试或切换模型。";
        } catch (Exception e) {
            log.warn("Failed to extract content from ChatResponse: {}", e.getMessage());
            LoadBalancingChatModel.consumeHttpContent(); // 清理 ThreadLocal
            return "抱歉，AI响应解析失败，请重试。";
        }
    }

    /**
     * SSE 流式聊天接口（POST），接受 JSON body，避免 GET URL 编码导致中文乱码。
     * 前端通过 fetch + ReadableStream 消费 text/event-stream。
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamChatPost(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        Object queryObj = body.get("query");
        if (queryObj == null || !StringUtils.hasText(String.valueOf(queryObj))) {
            // Backward compatibility for old frontend payloads using `message`
            queryObj = body.get("message");
        }
        Object intentObj = body.get("intent");
        String query = queryObj != null ? String.valueOf(queryObj) : "你好";
        String intent = intentObj != null ? String.valueOf(intentObj) : "chat";
        String projectRoot = body.get("projectRoot") != null ? String.valueOf(body.get("projectRoot")) : null;
        String taskId = body.get("taskId") != null ? String.valueOf(body.get("taskId")) : null;
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;

        List<Map<String, String>> history = normalizeHistory(body.get("history"));

        return doStreamChat(query, intent, projectRoot, taskId, userId, history);
    }

    /**
     * SSE 流式聊天接口（GET），保留向后兼容。
     * 注意：GET URL 编码可能导致长中文查询出现乱码，推荐使用 POST。
     */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamChatGet(
            @RequestParam(value = "query", defaultValue = "你好") String query,
            @RequestParam(value = "intent", defaultValue = "chat") String intent,
            @RequestParam(value = "projectRoot", required = false) String projectRoot,
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestParam(value = "userId", required = false) String userId,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        return doStreamChat(query, intent, projectRoot, taskId, userId, null);
    }

    private static final int MAX_HISTORY_MESSAGES = 10;

    private SseEmitter doStreamChat(String query, String intent, String projectRoot, String taskId, String userId,
                                    List<Map<String, String>> history) {

        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        String resolvedIntent0 = resolveIntent(intent, query);
        String resolvedTaskId0 = resolveTaskIdForIntent(resolvedIntent0, taskId);
        boolean isAutoIntent = "auto".equalsIgnoreCase(intent != null ? intent.trim() : "");
        if ("project".equalsIgnoreCase(resolvedIntent0) && !StringUtils.hasText(resolvedTaskId0) && isAutoIntent) {
            log.info("SSE auto intent resolved to project but taskId is missing; fallback to chat. query='{}'", query);
            resolvedIntent0 = "chat";
            resolvedTaskId0 = taskId;
        }
        String resolvedIntent = resolvedIntent0;
        String resolvedTaskId = resolvedTaskId0;
        String effectiveUserId = StringUtils.hasText(userId) ? userId : AuthUtil.getCurrentUserId();
        List<Map<String, String>> effectiveHistory = resolveConversationHistory(history, effectiveUserId, resolvedTaskId);
        boolean isProjectIntent = "project".equalsIgnoreCase(resolvedIntent);
        boolean isCodeQaIntent = "code_qa".equalsIgnoreCase(resolvedIntent);
        log.info("SSE stream started: intent={}, resolvedIntent={}, query length={}, taskId={}, hasUserId={}",
                intent, resolvedIntent, query.length(), resolvedTaskId, StringUtils.hasText(effectiveUserId));

        // Send keepalive comments every 15s to prevent proxy/client timeouts during long tool calls
        ScheduledFuture<?> keepaliveFuture = sseKeepaliveScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception ignored) {
                // emitter already completed or client disconnected
            }
        }, 15, 15, TimeUnit.SECONDS);

        // Build the streaming Flux on a separate thread to avoid blocking the servlet thread
        Thread.startVirtualThread(() -> {
            String rootToUse = null;
            try {
                if (StringUtils.hasText(effectiveUserId)) {
                    SaTokenUserContext.setUserId(effectiveUserId);
                }

                if (isProjectIntent && !StringUtils.hasText(resolvedTaskId)) {
                    keepaliveFuture.cancel(false);
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("项目模式尚未配置固定 taskId，请在配置中设置 zwiki.chat.default-project-task-id。", UTF8_TEXT));
                    emitter.send(SseEmitter.event().name("done").data("[DONE]", UTF8_TEXT));
                    emitter.complete();
                    return;
                }

                // Prepare context based on intent
                // 解析场景模型
                String sceneUserId = StringUtils.hasText(effectiveUserId) ? effectiveUserId : AuthUtil.getCurrentUserId();
                SceneModelResolver.Scene scene = isProjectIntent ? SceneModelResolver.Scene.CHAT
                        : isCodeQaIntent ? SceneModelResolver.Scene.CHAT
                        : SceneModelResolver.Scene.ASSISTANT;
                String sceneModel = sceneModelResolver.resolve(sceneUserId, scene);
                if (sceneModel != null) {
                    LoadBalancingChatModel.setExplicitModel(sceneModel);
                }

                if (isCodeQaIntent) {
                    // code_qa: user already provided the code in the query, no RAG / tools needed
                    log.info("code_qa intent: direct Q&A, no RAG or tools");
                } else if (isProjectIntent) {
                    if (projectRoot != null && !projectRoot.trim().isEmpty()) {
                        rootToUse = projectRoot.trim();
                    } else if (resolvedTaskId != null && !resolvedTaskId.trim().isEmpty()) {
                        Task task = taskMapper.findFirstByTaskId(resolvedTaskId.trim()).orElse(null);
                        if (task != null && task.getProjectPath() != null && !task.getProjectPath().trim().isEmpty()) {
                            rootToUse = task.getProjectPath().trim();
                        }
                    }
                    if (rootToUse == null) {
                        rootToUse = System.getProperty("user.dir");
                    }
                    FileSystemTool.setProjectRoot(rootToUse);
                } else {
                    LoadBalancingChatModel.setEnableWebSearch(true);
                }

                // Build system prompt and user prompt
                String systemPrompt;
                String userPrompt = query;
                ChatClient.Builder builder = chatClientBuilder;

                if (isCodeQaIntent) {
                    // code_qa: lightweight prompt, no tools, no RAG
                    systemPrompt = buildCodeQaSystemPrompt();
                } else if (isProjectIntent) {
                    // 诊断日志：检查可用工具
                    ToolCallback[] allTools = toolRegistration.getAllTools();
                    boolean hasDrawio = toolRegistration.hasToolNamed("open_drawio_mermaid");
                    log.info("🔧 [Stream] 可用工具数量: {}, hasDrawio: {}", allTools.length, hasDrawio);
                    if (log.isDebugEnabled()) {
                        for (ToolCallback t : allTools) {
                            try {
                                log.debug("  - Tool: {}", t.getToolDefinition().name());
                            } catch (Exception ignored) {}
                        }
                    }
                    
                    systemPrompt = buildProjectSystemPrompt(rootToUse, hasDrawio);
                    String ragContext = memoryRagService.buildRagContextForTask(resolvedTaskId, query, userId, rootToUse);
                    userPrompt = memoryRagService.injectContextIntoUserPrompt(query, ragContext);
                    builder = builder.defaultOptions(
                            ToolCallingChatOptions.builder()
                                    .toolCallbacks(allTools)
                                    .build()
                    );
                } else {
                    systemPrompt = buildChatSystemPrompt(toolRegistration.hasToolNamed("open_drawio_mermaid"));
                    ToolCallback[] mcpTools = toolRegistration.getMcpTools();
                    if (mcpTools.length > 0) {
                        builder = builder.defaultOptions(
                                ToolCallingChatOptions.builder()
                                        .toolCallbacks(mcpTools)
                                        .build()
                        );
                    }
                }

                // Build multi-turn message list with conversation history
                List<Message> messageList = new ArrayList<>();
                messageList.add(new SystemMessage(systemPrompt));
                if (effectiveHistory != null && !effectiveHistory.isEmpty()) {
                    for (Map<String, String> msg : effectiveHistory) {
                        String role = msg.get("role");
                        String msgContent = msg.get("content");
                        if ("user".equalsIgnoreCase(role)) {
                            messageList.add(new UserMessage(msgContent));
                        } else if ("assistant".equalsIgnoreCase(role) || "bot".equalsIgnoreCase(role)) {
                            messageList.add(new AssistantMessage(msgContent));
                        }
                    }
                    log.info("Included {} history messages for context continuity", effectiveHistory.size());
                }
                messageList.add(new UserMessage(userPrompt));

                // Execute streaming call with full conversation context
                reactor.core.publisher.Flux<String> contentFlux = builder
                        .build()
                        .prompt()
                        .messages(messageList.toArray(new Message[0]))
                        .stream()
                        .content();

                contentFlux.subscribe(
                        chunk -> {
                            try {
                                // 诊断日志：检查流式响应内容是否在Java String层面已乱码
                                if (chunk != null && chunk.length() < 200) {
                                    boolean hasNonAscii = chunk.chars().anyMatch(c -> c > 127);
                                    if (hasNonAscii) {
                                        byte[] utf8 = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                        StringBuilder hex = new StringBuilder();
                                        for (int i = 0; i < Math.min(utf8.length, 30); i++) {
                                            hex.append(String.format("%02X ", utf8[i]));
                                        }
                                        log.info("SSE chunk (non-ASCII): '{}' | UTF-8 hex: [{}]", chunk, hex.toString().trim());
                                    }
                                }
                                emitter.send(SseEmitter.event().data(chunk, UTF8_TEXT));
                            } catch (Exception e) {
                                log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
                                keepaliveFuture.cancel(false);
                                emitter.complete();
                            }
                        },
                        err -> {
                            keepaliveFuture.cancel(false);
                            log.error("SSE stream error: {}", err.getMessage());
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("AI服务出现问题: " + err.getClass().getSimpleName(), UTF8_TEXT));
                            } catch (Exception ignored) {
                            }
                            // 流结束后才清理项目根路径（不能在 finally 中清理，因为 subscribe 是非阻塞的）
                            FileSystemTool.clearProjectRoot();
                            LoadBalancingChatModel.clearAllThreadLocals();
                            emitter.complete();
                        },
                        () -> {
                            keepaliveFuture.cancel(false);
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]", UTF8_TEXT));
                            } catch (Exception ignored) {
                            }
                            // 流结束后才清理项目根路径
                            FileSystemTool.clearProjectRoot();
                            LoadBalancingChatModel.clearAllThreadLocals();
                            emitter.complete();
                            log.info("SSE stream completed");
                        }
                );

            } catch (Exception e) {
                keepaliveFuture.cancel(false);
                log.error("SSE stream setup failed: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("AI服务暂时出现问题: " + e.getClass().getSimpleName(), UTF8_TEXT));
                } catch (Exception ignored) {
                }
                FileSystemTool.clearProjectRoot();
                LoadBalancingChatModel.clearAllThreadLocals();
                emitter.complete();
            } finally {
                SaTokenUserContext.clear();
                // 注意：不在此处清理 FileSystemTool.clearProjectRoot()，
                // 因为 subscribe() 是非阻塞的，finally 会在工具回调执行前运行。
                // 清理已移到 subscribe 的 onComplete/onError 回调中。
            }
        });

        emitter.onTimeout(() -> {
            keepaliveFuture.cancel(false);
            log.warn("SSE stream timed out");
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("AI响应超时，请重试。", UTF8_TEXT));
            } catch (Exception ignored) {
            }
            FileSystemTool.clearProjectRoot();
            emitter.complete();
        });
        emitter.onError(e -> {
            keepaliveFuture.cancel(false);
            log.debug("SSE emitter error: {}", e.getMessage());
        });
        return emitter;
    }

    /**
     * 编码诊断端点：通过 SseEmitter 发送硬编码中文，用于隔离乱码问题是在 SseEmitter/Tomcat 层还是 Spring AI 层。
     * 如果此端点中文正常 → 问题在 Spring AI 流式解码；如果此端点也乱码 → 问题在 SseEmitter/Tomcat 配置。
     */
    @GetMapping(value = "/test-encoding", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter testEncoding(jakarta.servlet.http.HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        SseEmitter emitter = new SseEmitter(30_000L);
        new Thread(() -> {
            try {
                String[] testChunks = {"你好", "，我是", "AI助手", "。中文编码", "测试完成！"};
                for (String chunk : testChunks) {
                    byte[] utf8 = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    StringBuilder hex = new StringBuilder();
                    for (byte b : utf8) hex.append(String.format("%02X ", b));
                    log.info("test-encoding chunk: '{}' | hex: [{}]", chunk, hex.toString().trim());
                    emitter.send(SseEmitter.event().data(chunk, UTF8_TEXT));
                    Thread.sleep(200);
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]", UTF8_TEXT));
                emitter.complete();
            } catch (Exception e) {
                log.error("test-encoding error: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        }, "test-encoding").start();
        return emitter;
    }

    @GetMapping(value="/callTools", produces = "application/json;charset=UTF-8")
    public String callChatWithTools(@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query) {
        String result1= llmService.callWithoutTools(query);
        String result2= llmService.callWithTools(query);
        return result1 + "\n" + result2;
    }

}
