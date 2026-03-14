package com.zwiki.llm;

import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.llm.model.ModelType;
import com.zwiki.llm.model.ProviderContext;
import com.zwiki.llm.provider.ChatModelFactory;
import com.zwiki.llm.provider.ModelConfigProvider;
import com.zwiki.llm.service.ModelHealthRepository;
import com.zwiki.llm.service.TokenUsageRecorder;
import com.zwiki.llm.service.UserIdProvider;
import com.zwiki.llm.strategy.ModelSelectionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class LoadBalancingChatModel implements ChatModel {

    private static final ThreadLocal<HttpUsageSnapshot> HTTP_USAGE_SNAPSHOT = new ThreadLocal<>();
    private static final ThreadLocal<ModelType> REQUESTED_MODEL_TYPE = new ThreadLocal<>();
    private static final ThreadLocal<String> REASONING_CONTENT = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> PENDING_REASONING_CONTENTS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<String> HTTP_CONTENT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_API_KEY = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ENABLE_WEB_SEARCH = new ThreadLocal<>();
    private static final ThreadLocal<String> EXPLICIT_MODEL = new ThreadLocal<>();
    private static final ThreadLocal<Long> SELECTED_KEY_ID = new ThreadLocal<>();
    /**
     * 缓存已加载的模型配置列表，用于 Reactor 线程上的流式重试。
     * 在 HTTP 请求线程上加载模型后缓存，Reactor 线程无法通过 AuthUtil 获取 userId 时使用此缓存。
     */
    private static final ThreadLocal<List<LlmBalancerProperties.ModelConfig>> CACHED_MODELS = new ThreadLocal<>();

    /**
     * 构建健康状态的唯一标识符。
     * 格式: keyId:modelName（当有 keyId 时）或 modelName（向后兼容）
     */
    private static String buildHealthKey(Long keyId, String modelName) {
        if (keyId != null) {
            return keyId + ":" + modelName;
        }
        return modelName;
    }

    private final ChatModel delegate;
    private final ChatModelFactory chatModelFactory;
    private final LlmBalancerProperties properties;
    private final ModelConfigProvider modelConfigProvider;
    private final ModelHealthRepository healthRepository;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final UserIdProvider userIdProvider;
    private final Map<String, ModelSelectionStrategy> strategies;

    public LoadBalancingChatModel(
            ChatModel delegate,
            LlmBalancerProperties properties,
            ModelConfigProvider modelConfigProvider,
            ModelHealthRepository healthRepository,
            TokenUsageRecorder tokenUsageRecorder,
            List<ModelSelectionStrategy> strategyList,
            ChatModelFactory chatModelFactory,
            UserIdProvider userIdProvider) {
        this.delegate = delegate;
        this.chatModelFactory = chatModelFactory;
        this.properties = properties;
        this.modelConfigProvider = modelConfigProvider;
        this.healthRepository = healthRepository;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.userIdProvider = userIdProvider;
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ModelSelectionStrategy::getName, s -> s));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String selectedModel = selectHealthyModel(prompt);
        if (selectedModel == null) {
            throw new RuntimeException("No healthy model available for load balancing");
        }

        int maxAttempts = properties.getMaxAttemptsPerRequest();
        Exception lastException = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                log.info("LLM call attempt {} using model: {}", attempt + 1, selectedModel);
                long callStart = System.currentTimeMillis();
                healthRepository.recordCall(buildHealthKey(SELECTED_KEY_ID.get(), selectedModel));

                Prompt modelPrompt = createPromptWithModel(prompt, selectedModel);
                ChatModel activeChatModel = resolveActiveChatModel(selectedModel);
                ChatResponse response = activeChatModel.call(modelPrompt);
                long callElapsed = System.currentTimeMillis() - callStart;
                log.info("LLM call completed: model={}, elapsed={}ms", selectedModel, callElapsed);

                Long selectedKeyId = SELECTED_KEY_ID.get();
                String capturedUserId = resolveCurrentUserId();
                boolean recorded = extractAndRecordTokenUsage(selectedKeyId, selectedModel, capturedUserId, response);
                if (!recorded) {
                    recordCallCountOnly(selectedKeyId, selectedModel, capturedUserId);
                }
                return response;

            } catch (Exception e) {
                lastException = e;
                log.warn("Model {} failed on attempt {}: {}", selectedModel, attempt + 1, e.getMessage());
                healthRepository.recordError(buildHealthKey(SELECTED_KEY_ID.get(), selectedModel));
                recordErrorToDb(selectedModel);

                if (isMissingToolNameError(e) && isToolCallingPrompt(prompt)) {
                    log.warn("Model {} appears incompatible with tool-calling (blank toolName). Marking unhealthy and retrying.", selectedModel);
                    markModelUnhealthy(selectedModel);
                    selectedModel = selectHealthyModel(prompt);
                    if (selectedModel == null) {
                        throw new RuntimeException("All models failed tool-calling compatibility checks", e);
                    }
                    continue;
                }

                if (isModelUnavailableError(e) || isChatModelCreationError(e) || isBadRequestError(e) || isTimeoutOrIOError(e)) {
                    logResponseBody(selectedModel, e);
                    markModelUnhealthy(selectedModel);
                    if (isModelUnavailableError(e)) {
                        persistentlyDisableModel(SELECTED_KEY_ID.get(), selectedModel);
                    }
                    selectedModel = selectHealthyModel(prompt);
                    if (selectedModel == null) {
                        throw new RuntimeException("All models unavailable", e);
                    }
                } else {
                    logResponseBody(selectedModel, e);
                    throw new RuntimeException("Model call failed: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("Max attempts exceeded", lastException);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamWithRetry(prompt, 0);
    }

    private Flux<ChatResponse> streamWithRetry(Prompt prompt, int attempt) {
        String selectedModel = selectHealthyModel(prompt);
        if (selectedModel == null) {
            return Flux.error(new RuntimeException("No healthy model available for load balancing"));
        }

        int maxAttempts = properties.getMaxAttemptsPerRequest();
        Prompt modelPrompt = createPromptWithModel(prompt, selectedModel);
        ChatModel activeChatModel;
        try {
            activeChatModel = resolveActiveChatModel(selectedModel);
        } catch (Exception e) {
            log.warn("Streaming model initialization failed for model {}: {}", selectedModel, e.getMessage());
            healthRepository.recordError(buildHealthKey(SELECTED_KEY_ID.get(), selectedModel));
            recordErrorToDb(selectedModel);
            if (attempt < maxAttempts - 1 && (isModelUnavailableError(e) || isChatModelCreationError(e))) {
                log.info("Streaming retry attempt {} after model {} initialization failure: {}",
                        attempt + 1, selectedModel, e.getMessage());
                return streamWithRetry(prompt, attempt + 1);
            }
            return Flux.error(e);
        }
        // Capture keyId and userId into local variables BEFORE entering reactive chain.
        // Reactor callbacks (doOnNext/doOnError/doOnComplete) run on different threads
        // where ThreadLocals would be empty.
        final Long capturedKeyId = SELECTED_KEY_ID.get();
        final String capturedUserId = resolveCurrentUserId();
        healthRepository.recordCall(buildHealthKey(capturedKeyId, selectedModel));

        // Capture cached models for propagation to reactor threads during retry
        final List<LlmBalancerProperties.ModelConfig> modelsForRetry = CACHED_MODELS.get();

        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        return activeChatModel.stream(modelPrompt)
                .doOnNext(response -> {
                    // 流式响应中，token usage 通常在最后一个 chunk 中返回
                    if (!usageRecorded.get() && extractAndRecordTokenUsage(capturedKeyId, selectedModel, capturedUserId, response)) {
                        usageRecorded.set(true);
                    }
                })
                .doOnError(e -> {
                    log.warn("Streaming failed for model {}: {}", selectedModel, e.getMessage());
                    logResponseBody(selectedModel, e);
                    healthRepository.recordError(buildHealthKey(capturedKeyId, selectedModel));
                    recordErrorToDb(capturedKeyId, selectedModel);
                    if (isMissingToolNameError(e) && isToolCallingPrompt(prompt)) {
                        log.warn("Model {} appears incompatible with tool-calling (blank toolName). Marking unhealthy.", selectedModel);
                        markModelUnhealthy(selectedModel);
                    } else if (isModelUnavailableError(e)) {
                        markModelUnhealthy(selectedModel);
                        persistentlyDisableModel(capturedKeyId, selectedModel);
                    } else if (isBadRequestError(e)) {
                        log.warn("Model {} returned 400 Bad Request (possibly unsupported features). Marking unhealthy for fallback.", selectedModel);
                        markModelUnhealthy(selectedModel);
                    } else if (isChatModelCreationError(e)) {
                        log.warn("Model {} ChatModel creation failed. Marking unhealthy.", selectedModel);
                        markModelUnhealthy(selectedModel);
                    } else if (isTimeoutOrIOError(e)) {
                        log.warn("Model {} timed out or had I/O error. Marking unhealthy for fallback.", selectedModel);
                        markModelUnhealthy(selectedModel);
                    }
                })
                .doOnComplete(() -> {
                    if (!usageRecorded.get()) {
                        recordCallCountOnly(capturedKeyId, selectedModel, capturedUserId);
                    }
                    log.debug("Streaming completed for model: {}", selectedModel);
                })
                .onErrorResume(e -> {
                    if (attempt < maxAttempts - 1 && (isModelUnavailableError(e) ||
                            isChatModelCreationError(e) ||
                            isBadRequestError(e) ||
                            isTimeoutOrIOError(e) ||
                            (isMissingToolNameError(e) && isToolCallingPrompt(prompt)))) {
                        log.info("Streaming retry attempt {} after model {} failure: {}",
                                attempt + 1, selectedModel, e.getMessage());
                        // Propagate cached models to reactor thread so selectHealthyModel can find fallback models
                        if (modelsForRetry != null && !modelsForRetry.isEmpty()) {
                            CACHED_MODELS.set(modelsForRetry);
                        }
                        return streamWithRetry(prompt, attempt + 1);
                    }
                    return Flux.error(e);
                });
    }

    private String normalizeStrategyName(String strategy) {
        if (strategy == null) {
            return null;
        }

        String normalized = strategy.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }

        return switch (normalized) {
            case "roundrobin", "round-robin" -> "round_robin";
            case "weighted_round_robin", "weighted-round-robin", "weightedroundrobin", "weighted_rr", "weight_rr" -> "weighted_rr";
            default -> normalized;
        };
    }

    /**
     * Set the requested model type for the current thread.
     * The load balancer will filter candidate models by this type.
     * Consumed (cleared) after model selection.
     */
    public static void setRequestedModelType(ModelType type) {
        if (type != null) {
            REQUESTED_MODEL_TYPE.set(type);
        }
    }

    public static void clearRequestedModelType() {
        REQUESTED_MODEL_TYPE.remove();
    }

    /**
     * 指定当前线程使用的模型名称，绕过负载均衡策略。
     * 如果该模型 healthy 且存在于用户模型列表中，直接使用；否则回退到负载均衡。
     * 在 selectHealthyModel() 中被消费（清除）。
     */
    public static void setExplicitModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            EXPLICIT_MODEL.set(modelName);
        }
    }

    public static void clearExplicitModel() {
        EXPLICIT_MODEL.remove();
    }

    private String selectHealthyModel(Prompt prompt) {
        // Consume the explicit model override (scene-based selection)
        String explicitModel = EXPLICIT_MODEL.get();
        EXPLICIT_MODEL.remove();

        // Consume the requested model type (default TEXT)
        ModelType requestedType = REQUESTED_MODEL_TYPE.get();
        REQUESTED_MODEL_TYPE.remove();
        if (requestedType == null) {
            requestedType = ModelType.TEXT;
        }

        List<LlmBalancerProperties.ModelConfig> configuredModels = modelConfigProvider != null
                ? modelConfigProvider.getModels()
                : null;

        // Reactor 线程上 AuthUtil.getCurrentUserId() 返回 null，导致 provider 返回空列表。
        // 此时回退到缓存的模型列表（由 HTTP 请求线程加载并缓存）。
        if ((configuredModels == null || configuredModels.isEmpty()) && CACHED_MODELS.get() != null) {
            configuredModels = CACHED_MODELS.get();
            if (!configuredModels.isEmpty()) {
                log.debug("Using cached models (count={}) as provider returned empty (likely reactor thread without userId context)", configuredModels.size());
            }
        }

        if (configuredModels == null) {
            configuredModels = List.of();
        }

        // Cache non-empty results for future use on reactor threads
        if (!configuredModels.isEmpty()) {
            CACHED_MODELS.set(configuredModels);
        }

        // If explicit model is requested, try to use it directly
        // Format: "keyId:modelName" for precise matching, or just "modelName" for backward compatibility
        if (explicitModel != null && !explicitModel.isBlank()) {
            Long explicitKeyId = null;
            String explicitModelName = explicitModel;
            
            // Parse keyId:name format
            int colonIdx = explicitModel.indexOf(':');
            if (colonIdx > 0) {
                try {
                    explicitKeyId = Long.parseLong(explicitModel.substring(0, colonIdx));
                    explicitModelName = explicitModel.substring(colonIdx + 1);
                } catch (NumberFormatException e) {
                    // Not a valid keyId:name format, treat as plain model name
                    log.debug("Explicit model '{}' is not in keyId:name format, treating as plain name", explicitModel);
                }
            }
            
            final Long targetKeyId = explicitKeyId;
            final String targetModelName = explicitModelName;
            
            // Try to find matching model: prioritize keyId+name match, then fall back to name-only
            LlmBalancerProperties.ModelConfig matched = null;
            
            if (targetKeyId != null) {
                // Precise match by keyId and name
                matched = configuredModels.stream()
                        .filter(m -> targetKeyId.equals(m.getLlmKeyId()) && targetModelName.equals(m.getName()))
                        .filter(LlmBalancerProperties.ModelConfig::isEnabled)
                        .filter(m -> healthRepository.isHealthy(buildHealthKey(m.getLlmKeyId(), m.getName())))
                        .findFirst()
                        .orElse(null);
                
                if (matched != null) {
                    log.info("Using explicit model '{}' with keyId={} (precise scene-based selection)", 
                            targetModelName, targetKeyId);
                }
            }
            
            // Backward compatibility: name-only match if no keyId or precise match failed
            if (matched == null) {
                matched = configuredModels.stream()
                        .filter(m -> targetModelName.equals(m.getName()))
                        .filter(LlmBalancerProperties.ModelConfig::isEnabled)
                        .filter(m -> healthRepository.isHealthy(buildHealthKey(m.getLlmKeyId(), m.getName())))
                        .findFirst()
                        .orElse(null);
                
                if (matched != null) {
                    log.info("Using explicit model '{}' (name-only scene-based selection, keyId={})", 
                            targetModelName, matched.getLlmKeyId());
                }
            }
            
            if (matched != null) {
                if (matched.getApiKey() != null && !matched.getApiKey().isBlank()) {
                    CURRENT_API_KEY.set(matched.getApiKey());
                } else {
                    CURRENT_API_KEY.remove();
                }
                // Store keyId for resolveActiveChatModel to get correct config
                if (matched.getLlmKeyId() != null) {
                    SELECTED_KEY_ID.set(matched.getLlmKeyId());
                }
                return matched.getName();
            }
            
            // Explicit model not available — check fallback policy
            if (!properties.isAllowFallbackOnExplicitModel()) {
                log.error("Explicit model '{}' is unavailable and allowFallbackOnExplicitModel=false, aborting", explicitModel);
                return null;
            }
            log.warn("Explicit model '{}' not found or unhealthy, falling back to load balancing (allowFallbackOnExplicitModel=true)", explicitModel);
        }

        List<LlmBalancerProperties.ModelConfig> healthyModels = configuredModels.stream()
                .filter(LlmBalancerProperties.ModelConfig::isEnabled)
                .filter(m -> healthRepository.isHealthy(buildHealthKey(m.getLlmKeyId(), m.getName())))
                .collect(Collectors.toList());

        // Filter by model type
        final ModelType filterType = requestedType;
        List<LlmBalancerProperties.ModelConfig> typedModels = healthyModels.stream()
                .filter(m -> ModelType.fromString(m.getModelType()) == filterType)
                .collect(Collectors.toList());

        if (typedModels.isEmpty() && filterType != ModelType.MULTIMODAL) {
            // Fallback: try MULTIMODAL models (they can handle text too)
            typedModels = healthyModels.stream()
                    .filter(m -> ModelType.fromString(m.getModelType()) == ModelType.MULTIMODAL)
                    .collect(Collectors.toList());
            if (!typedModels.isEmpty()) {
                log.info("No {} models available, falling back to MULTIMODAL models", filterType);
            }
        }

        if (!typedModels.isEmpty()) {
            healthyModels = typedModels;
        } else {
            log.warn("No models matching type {} found, using all healthy models as fallback", filterType);
        }

        // Filter by tool-calling capability if needed
        if (!healthyModels.isEmpty() && isToolCallingPrompt(prompt)) {
            List<LlmBalancerProperties.ModelConfig> toolCapable = healthyModels.stream()
                    .filter(this::supportsToolCalling)
                    .collect(Collectors.toList());
            if (!toolCapable.isEmpty()) {
                healthyModels = toolCapable;
            } else {
                log.warn("Tool-calling prompt detected but no models are marked as tool-capable via capabilities; falling back to all healthy models");
            }
        }

        if (healthyModels.isEmpty()) {
            log.error("No healthy models available for type {}", filterType);
            return null;
        }

        String strategyName = normalizeStrategyName(properties.getStrategy());
        if (strategyName == null || strategyName.isBlank()) {
            log.error("Load balancer strategy is not configured");
            return null;
        }

        ModelSelectionStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            log.error("Unknown load balancer strategy '{}', available: {}", strategyName, strategies.keySet());
            return null;
        }

        String selected = strategy.selectModel(healthyModels);
        if (selected != null) {
            healthyModels.stream()
                    .filter(m -> selected.equals(m.getName()))
                    .findFirst()
                    .ifPresent(m -> {
                        if (m.getApiKey() != null && !m.getApiKey().isBlank()) {
                            CURRENT_API_KEY.set(m.getApiKey());
                        } else {
                            CURRENT_API_KEY.remove();
                        }
                        // Store keyId for resolveActiveChatModel to get correct config
                        if (m.getLlmKeyId() != null) {
                            SELECTED_KEY_ID.set(m.getLlmKeyId());
                        }
                    });
        }
        log.info("Strategy '{}' selected model '{}' from {} candidates (type={})",
                strategyName, selected, healthyModels.size(), filterType);
        return selected;
    }

    private boolean supportsToolCalling(LlmBalancerProperties.ModelConfig model) {
        if (model == null) {
            return false;
        }
        String capabilities = model.getCapabilities();
        if (capabilities == null || capabilities.isBlank()) {
            return false;
        }
        String c = capabilities.toLowerCase(Locale.ROOT);
        return c.contains("tool")
                || c.contains("tools")
                || c.contains("function")
                || c.contains("function_call")
                || c.contains("function-calling")
                || c.contains("tool_call")
                || c.contains("tool-calling");
    }

    /**
     * 检查模型是否为思考/推理模型，这类模型要求 temperature=1
     * 例如 Moonshot 的 kimi-k2.5, kimi-k2 等
     */
    private boolean isThinkingModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        // Moonshot kimi thinking models require temperature=1
        return lower.contains("kimi-k2") || lower.contains("kimi-k1.5");
    }

    private Prompt createPromptWithModel(Prompt original, String modelName) {
        List<Message> messages = original.getInstructions();
        
        ModelOptions originalOptions = original.getOptions();
        ChatOptions newOptions;

        if (originalOptions instanceof ToolCallingChatOptions toolOptions) {
            var builder = ToolCallingChatOptions.builder().model(modelName);

            if (toolOptions.getToolCallbacks() != null) {
                builder.toolCallbacks(toolOptions.getToolCallbacks());
            }
            if (toolOptions.getToolNames() != null) {
                builder.toolNames(toolOptions.getToolNames());
            }
            if (toolOptions.getInternalToolExecutionEnabled() != null) {
                builder.internalToolExecutionEnabled(toolOptions.getInternalToolExecutionEnabled());
            }
            if (toolOptions.getToolContext() != null) {
                builder.toolContext(toolOptions.getToolContext());
            }

            // 思考模型（如kimi-k2.5）要求temperature=1，覆盖原始设置
            if (isThinkingModel(modelName)) {
                builder.temperature(1.0);
                log.debug("Forcing temperature=1.0 for thinking model: {}", modelName);
            } else if (toolOptions.getTemperature() != null) {
                builder.temperature(toolOptions.getTemperature());
            }
            if (toolOptions.getTopP() != null) {
                builder.topP(toolOptions.getTopP());
            }
            if (toolOptions.getTopK() != null) {
                builder.topK(toolOptions.getTopK());
            }
            if (toolOptions.getMaxTokens() != null) {
                builder.maxTokens(toolOptions.getMaxTokens());
            }
            if (toolOptions.getPresencePenalty() != null) {
                builder.presencePenalty(toolOptions.getPresencePenalty());
            }
            if (toolOptions.getFrequencyPenalty() != null) {
                builder.frequencyPenalty(toolOptions.getFrequencyPenalty());
            }
            if (toolOptions.getStopSequences() != null) {
                builder.stopSequences(toolOptions.getStopSequences());
            }

            newOptions = builder.build();
        } else if (originalOptions instanceof ChatOptions chatOptions) {
            var builder = ChatOptions.builder().model(modelName);

            // 思考模型（如kimi-k2.5）要求temperature=1，覆盖原始设置
            if (isThinkingModel(modelName)) {
                builder.temperature(1.0);
                log.debug("Forcing temperature=1.0 for thinking model: {}", modelName);
            } else if (chatOptions.getTemperature() != null) {
                builder.temperature(chatOptions.getTemperature());
            }
            if (chatOptions.getTopP() != null) {
                builder.topP(chatOptions.getTopP());
            }
            if (chatOptions.getTopK() != null) {
                builder.topK(chatOptions.getTopK());
            }
            if (chatOptions.getMaxTokens() != null) {
                builder.maxTokens(chatOptions.getMaxTokens());
            }
            if (chatOptions.getPresencePenalty() != null) {
                builder.presencePenalty(chatOptions.getPresencePenalty());
            }
            if (chatOptions.getFrequencyPenalty() != null) {
                builder.frequencyPenalty(chatOptions.getFrequencyPenalty());
            }
            if (chatOptions.getStopSequences() != null) {
                builder.stopSequences(chatOptions.getStopSequences());
            }

            newOptions = builder.build();
        } else {
            newOptions = ChatOptions.builder().model(modelName).build();
        }

        return new Prompt(messages, newOptions);
    }

    private void markModelUnhealthy(String modelName) {
        Long keyId = SELECTED_KEY_ID.get();
        String healthKey = buildHealthKey(keyId, modelName);
        long cooldownMillis = properties.getUnhealthyCooldownSeconds() * 1000L;
        long unhealthyUntil = System.currentTimeMillis() + cooldownMillis;
        healthRepository.markUnhealthy(healthKey, unhealthyUntil);
    }

    private void persistentlyDisableModel(Long keyId, String modelName) {
        try {
            if (tokenUsageRecorder != null) {
                tokenUsageRecorder.disableModel(keyId, modelName);
                log.warn("Model '{}' (keyId={}) persistently disabled in DB due to fatal error", modelName, keyId);
            }
        } catch (Exception e) {
            log.warn("Failed to persistently disable model '{}' (keyId={}): {}", modelName, keyId, e.getMessage());
        }
    }

    private boolean isToolCallingPrompt(Prompt prompt) {
        if (prompt == null) {
            return false;
        }
        ModelOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return false;
        }
        return (toolOptions.getToolCallbacks() != null && !toolOptions.getToolCallbacks().isEmpty())
                || (toolOptions.getToolNames() != null && !toolOptions.getToolNames().isEmpty());
    }

    private boolean isMissingToolNameError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("toolName cannot be null or empty")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isBadRequestError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getSimpleName();
            if ("BadRequest".equalsIgnoreCase(className)) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("400 bad request")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 提取并记录 WebClientResponseException 的响应体，用于诊断 API 返回的错误详情。
     */
    private void logResponseBody(String modelName, Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof WebClientResponseException wcre) {
                String body = wcre.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    String truncated = body.length() > 1000 ? body.substring(0, 1000) + "..." : body;
                    log.warn("API error response for model '{}' [HTTP {}]: {}",
                            modelName, wcre.getStatusCode().value(), truncated);
                }
                return;
            }
            current = current.getCause();
        }
    }

    private boolean isChatModelCreationError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("Failed to create ChatModel")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeoutOrIOError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getName();
            // Netty ReadTimeoutException / WriteTimeoutException
            if (className.contains("TimeoutException")) {
                return true;
            }
            // Spring ResourceAccessException wrapping IO errors
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timeout") || lower.contains("timed out")
                        || lower.contains("connection reset") || lower.contains("connection refused")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isModelUnavailableError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            // Check exception class name (e.g. WebClientResponseException$TooManyRequests)
            String className = current.getClass().getSimpleName();
            if ("TooManyRequests".equalsIgnoreCase(className) ||
                    "Unauthorized".equalsIgnoreCase(className) ||
                    "Forbidden".equalsIgnoreCase(className) ||
                    "NotFound".equalsIgnoreCase(className)) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                // Quota / rate-limit
                if (lowerMessage.contains("quota") ||
                        lowerMessage.contains("rate limit") ||
                        lowerMessage.contains("ratelimit") ||
                        lowerMessage.contains("too many requests") ||
                        lowerMessage.contains("429") ||
                        lowerMessage.contains("exceeded") ||
                        lowerMessage.contains("limit exceeded")) {
                    return true;
                }
                // Authentication / authorization (model not enabled for this API key)
                if (lowerMessage.contains("access_denied") ||
                        lowerMessage.contains("access denied") ||
                        lowerMessage.contains("unauthorized") ||
                        lowerMessage.contains("forbidden") ||
                        lowerMessage.contains("http 401") ||
                        lowerMessage.contains("http 403")) {
                    return true;
                }
                // Model not found / not available
                if (lowerMessage.contains("model_not_found") ||
                        lowerMessage.contains("model not found") ||
                        lowerMessage.contains("does not exist") ||
                        lowerMessage.contains("not available") ||
                        lowerMessage.contains("http 404")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean extractAndRecordTokenUsage(Long keyId, String modelName, String userId, ChatResponse response) {
        try {
            if (response == null || response.getMetadata() == null) {
                return false;
            }

            // Try Spring AI metadata first
            var usage = response.getMetadata().getUsage();
            long inputTokens = 0, outputTokens = 0, totalTokens = 0;
            boolean hasSpringAiUsage = false;

            if (usage != null) {
                try {
                    Number pt = usage.getPromptTokens();
                    Number ct = usage.getCompletionTokens();
                    Number tt = usage.getTotalTokens();
                    inputTokens = pt != null ? pt.longValue() : 0;
                    outputTokens = ct != null ? ct.longValue() : 0;
                    totalTokens = tt != null ? tt.longValue() : 0;
                } catch (Exception e) {
                    log.debug("Failed to extract tokens from Usage object for model {}: {}", modelName, e.getMessage());
                }
                long computedTotal = totalTokens > 0 ? totalTokens : Math.max(0, inputTokens) + Math.max(0, outputTokens);
                hasSpringAiUsage = computedTotal > 0;
            }

            if (hasSpringAiUsage) {
                // Spring AI gave us valid usage, consume (discard) HTTP snapshot
                consumeHttpUsageSnapshot();
                long computedTotal = totalTokens > 0 ? totalTokens : inputTokens + outputTokens;
                healthRepository.recordDetailedTokens(buildHealthKey(keyId, modelName), inputTokens, outputTokens, computedTotal);
                if (tokenUsageRecorder != null) {
                    tokenUsageRecorder.recordTokenUsage(keyId, modelName, userId, inputTokens, outputTokens, computedTotal);
                }
                log.info("Recorded tokens for model {} (keyId={}, userId={}) from Spring AI metadata: input={}, output={}, total={}",
                        modelName, keyId, userId, inputTokens, outputTokens, computedTotal);
                return true;
            }

            // Spring AI metadata empty/zero, try HTTP fallback
            log.debug("No meaningful usage in Spring AI metadata for model {}, trying HTTP fallback", modelName);
            HttpUsageSnapshot snapshot = consumeHttpUsageSnapshot();
            if (snapshot != null && snapshot.totalTokens > 0) {
                healthRepository.recordDetailedTokens(buildHealthKey(keyId, modelName), snapshot.inputTokens, snapshot.outputTokens, snapshot.totalTokens);
                if (tokenUsageRecorder != null) {
                    tokenUsageRecorder.recordTokenUsage(keyId, modelName, userId, snapshot.inputTokens, snapshot.outputTokens, snapshot.totalTokens);
                }
                log.info("Recorded tokens for model {} (keyId={}, userId={}) from HTTP fallback: input={}, output={}, total={}",
                        modelName, keyId, userId, snapshot.inputTokens, snapshot.outputTokens, snapshot.totalTokens);
                return true;
            }

            log.debug("No token usage available for model {} from any source", modelName);
            return false;
        } catch (Exception e) {
            log.warn("Failed to extract token usage for model {}: {}", modelName, e.getMessage());
            return false;
        }
    }

    private void recordCallCountOnly(Long keyId, String modelName, String userId) {
        try {
            if (tokenUsageRecorder != null) {
                tokenUsageRecorder.recordTokenUsage(keyId, modelName, userId, 0, 0, 0);
            }
        } catch (Exception e) {
            log.warn("Failed to record call count for model {} (keyId={}, userId={}): {}", modelName, keyId, userId, e.getMessage());
        }
    }

    /**
     * 从 UserIdProvider 获取当前用户ID。
     * 在进入 Reactor 响应链之前调用，捕获到局部变量中以便回调中使用。
     */
    private String resolveCurrentUserId() {
        if (userIdProvider == null) {
            return null;
        }
        try {
            return userIdProvider.getCurrentUserId();
        } catch (Exception e) {
            log.debug("Failed to resolve current userId: {}", e.getMessage());
            return null;
        }
    }

    private void recordErrorToDb(String modelName) {
        recordErrorToDb(SELECTED_KEY_ID.get(), modelName);
    }

    private void recordErrorToDb(Long keyId, String modelName) {
        try {
            if (tokenUsageRecorder != null) {
                tokenUsageRecorder.recordError(keyId, modelName, null);
            }
        } catch (Exception e) {
            log.warn("Failed to record error for model {} (keyId={}): {}", modelName, keyId, e.getMessage());
        }
    }

    public static void setHttpUsageSnapshot(String modelName, long inputTokens, long outputTokens, long totalTokens) {
        if (modelName == null || modelName.isBlank()) {
            return;
        }
        long safeInput = Math.max(0, inputTokens);
        long safeOutput = Math.max(0, outputTokens);
        long computedTotal = totalTokens > 0 ? totalTokens : safeInput + safeOutput;
        if (computedTotal <= 0) {
            return;
        }
        // Accumulate tokens across multiple HTTP requests (e.g. tool-calling involves 2+ requests)
        HttpUsageSnapshot existing = HTTP_USAGE_SNAPSHOT.get();
        if (existing != null) {
            HTTP_USAGE_SNAPSHOT.set(new HttpUsageSnapshot(
                    modelName,
                    existing.inputTokens + safeInput,
                    existing.outputTokens + safeOutput,
                    existing.totalTokens + computedTotal));
        } else {
            HTTP_USAGE_SNAPSHOT.set(new HttpUsageSnapshot(modelName, safeInput, safeOutput, computedTotal));
        }
    }

    /**
     * 存储思考模型的 reasoning_content（由 HTTP 拦截器调用）。
     * reasoning_content 是模型的内部思考过程（CoT），不是最终回答。
     * 最终回答在 content 字段中。
     */
    public static void setReasoningContent(String content) {
        if (content != null && !content.isBlank()) {
            REASONING_CONTENT.set(content);
        }
    }

    /**
     * 消费 reasoning_content（读取后清除 ThreadLocal）。
     */
    public static String consumeReasoningContent() {
        String content = REASONING_CONTENT.get();
        REASONING_CONTENT.remove();
        return content;
    }

    /**
     * 追加待注入的 reasoning_content（用于思考模型的工具调用场景）。
     * 每轮 tool_call 返回的 reasoning_content 都追加到列表中，
     * 后续请求按顺序注入到对应的 assistant 消息中。
     */
    public static void addPendingReasoningContent(String content) {
        if (content != null && !content.isBlank()) {
            PENDING_REASONING_CONTENTS.get().add(content);
        }
    }

    /**
     * 获取全部待注入的 reasoning_content 列表（不清除，多轮工具调用需要重复注入）。
     */
    public static List<String> getPendingReasoningContents() {
        return PENDING_REASONING_CONTENTS.get();
    }

    /**
     * 清除全部待注入的 reasoning_content。
     */
    public static void clearPendingReasoningContents() {
        PENDING_REASONING_CONTENTS.get().clear();
    }

    /**
     * 存储 HTTP 响应中的 content 字段（由 HTTP 拦截器调用）。
     * 当 Spring AI 的 getText() 返回空时，可从此处获取实际回答内容作为后备。
     * 对于 tool-calling 场景，每次 HTTP 请求都会覆盖，最终保留的是最后一次（即最终回答）。
     */
    public static void setHttpContent(String content) {
        if (content != null && !content.isBlank()) {
            HTTP_CONTENT.set(content);
        }
    }

    /**
     * 消费 HTTP 响应中的 content（读取后清除 ThreadLocal）。
     */
    public static String consumeHttpContent() {
        String content = HTTP_CONTENT.get();
        HTTP_CONTENT.remove();
        return content;
    }

    /**
     * 启用 DashScope 原生联网搜索（enable_search）。
     * 由 HTTP 拦截器在发送请求时注入 enable_search=true 到请求体。
     */
    public static void setEnableWebSearch(boolean enable) {
        ENABLE_WEB_SEARCH.set(enable);
    }

    public static boolean isWebSearchEnabled() {
        Boolean val = ENABLE_WEB_SEARCH.get();
        return val != null && val;
    }

    public static void clearEnableWebSearch() {
        ENABLE_WEB_SEARCH.remove();
    }

    /**
     * 获取当前线程选中模型对应的 API Key（由负载均衡选模时设置）。
     */
    public static String getCurrentApiKey() {
        return CURRENT_API_KEY.get();
    }

    /**
     * 清除当前线程上所有 ThreadLocal 状态。
     * 应在每次 LLM 调用入口的 finally 块中调用，防止线程池复用时泄漏。
     */
    public static void clearAllThreadLocals() {
        HTTP_USAGE_SNAPSHOT.remove();
        REQUESTED_MODEL_TYPE.remove();
        REASONING_CONTENT.remove();
        PENDING_REASONING_CONTENTS.get().clear();
        PENDING_REASONING_CONTENTS.remove();
        HTTP_CONTENT.remove();
        CURRENT_API_KEY.remove();
        ENABLE_WEB_SEARCH.remove();
        EXPLICIT_MODEL.remove();
        SELECTED_KEY_ID.remove();
        CACHED_MODELS.remove();
        ProviderContext.clear();
    }

    private static HttpUsageSnapshot consumeHttpUsageSnapshot() {
        HttpUsageSnapshot snapshot = HTTP_USAGE_SNAPSHOT.get();
        if (snapshot == null) {
            return null;
        }
        HTTP_USAGE_SNAPSHOT.remove();
        return snapshot;
    }

    private record HttpUsageSnapshot(String modelName, long inputTokens, long outputTokens, long totalTokens) {
    }

    /**
     * 根据选中的模型名称获取对应的 ModelConfig。
     * 如果 SELECTED_KEY_ID 有值，优先按 keyId + name 精确匹配；否则回退到 name-only 匹配。
     */
    private LlmBalancerProperties.ModelConfig getModelConfig(String modelName) {
        if (modelName == null) return null;
        List<LlmBalancerProperties.ModelConfig> models = modelConfigProvider != null
                ? modelConfigProvider.getModels() : List.of();
        // Fallback to cached models on reactor threads
        if (models.isEmpty() && CACHED_MODELS.get() != null) {
            models = CACHED_MODELS.get();
        }
        
        // Try precise match by keyId + name first
        Long selectedKeyId = SELECTED_KEY_ID.get();
        if (selectedKeyId != null) {
            LlmBalancerProperties.ModelConfig preciseMatch = models.stream()
                    .filter(m -> selectedKeyId.equals(m.getLlmKeyId()) && modelName.equals(m.getName()))
                    .findFirst()
                    .orElse(null);
            if (preciseMatch != null) {
                return preciseMatch;
            }
        }
        
        // Fallback to name-only match
        return models.stream()
                .filter(m -> modelName.equals(m.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据选中的模型名称解析实际要使用的 ChatModel 实例。
     * 如果模型配置了自定义 baseUrl/apiKey，则通过 ChatModelFactory 获取对应的 ChatModel；
     * 否则使用默认的 delegate。
     * 
     * 注意：如果 ChatModel 创建失败，会抛出异常而不是静默回退到 delegate，
     * 这样调用方的重试逻辑可以选择下一个健康模型。
     */
    private ChatModel resolveActiveChatModel(String selectedModel) {
        if (chatModelFactory == null) {
            if (delegate != null) {
                return delegate;
            }
            throw new IllegalStateException("No ChatModelFactory or default ChatModel delegate available for model '" + selectedModel + "'");
        }
        LlmBalancerProperties.ModelConfig config = getModelConfig(selectedModel);
        if (config == null) {
            // No custom config, use delegate (which may be the default Spring AI ChatModel)
            // This is acceptable since the model was selected from healthy models
            if (delegate != null) {
                return delegate;
            }
            throw new IllegalStateException("No model config found and no default ChatModel delegate available for model '" + selectedModel + "'");
        }
        try {
            return chatModelFactory.getOrCreate(config);
        } catch (Exception e) {
            // Don't silently fall back to delegate - throw so retry logic can select another healthy model
            log.error("Failed to create ChatModel for model '{}': {}", selectedModel, e.getMessage());
            markModelUnhealthy(selectedModel);
            throw new RuntimeException("Failed to create ChatModel for model '" + selectedModel + "': " + e.getMessage(), e);
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        if (delegate != null) {
            return (ChatOptions) delegate.getDefaultOptions();
        }
        return ChatOptions.builder().build();
    }
}
