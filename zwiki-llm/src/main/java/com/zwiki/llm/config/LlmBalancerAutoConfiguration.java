package com.zwiki.llm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.llm.LoadBalancingChatModel;
import com.zwiki.llm.provider.ChatModelFactory;
import com.zwiki.llm.provider.ModelConfigProvider;
import com.zwiki.llm.provider.PropertiesModelConfigProvider;
import com.zwiki.llm.provider.ReasoningContentWebClientConnector;
import com.zwiki.llm.service.ModelHealthRepository;
import com.zwiki.llm.service.TokenUsageRecorder;
import com.zwiki.llm.service.UserIdProvider;
import com.zwiki.llm.strategy.ModelSelectionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@AutoConfiguration(
        afterName = "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration",
        beforeName = "org.springframework.ai.autoconfigure.chat.client.ChatClientAutoConfiguration"
)
@EnableConfigurationProperties(LlmBalancerProperties.class)
@ComponentScan(basePackages = "com.zwiki.llm")
@ConditionalOnProperty(prefix = "zwiki.llm.balancer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmBalancerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelConfigProvider.class)
    public ModelConfigProvider modelConfigProvider(LlmBalancerProperties properties) {
        return new PropertiesModelConfigProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ChatModelFactory.class)
    public ChatModelFactory chatModelFactory(ObjectProvider<OpenAiChatModel> openAiChatModels,
                                             ObjectProvider<ToolCallingManager> toolCallingManagers,
                                             ObjectProvider<RestClient.Builder> restClientBuilders,
                                             ObjectProvider<ObjectMapper> objectMappers) {
        // Use OpenAiChatModel directly to avoid circular dependency with LoadBalancingChatModel
        OpenAiChatModel delegate = resolveDefaultOpenAiChatModel(openAiChatModels);
        ToolCallingManager tcm = toolCallingManagers.getIfAvailable();
        // RestClient.Builder is a prototype bean — each call to getObject() returns a new instance
        // with all RestClientCustomizer interceptors applied (reasoning_content, token usage, etc.)
        java.util.function.Supplier<RestClient.Builder> builderSupplier = () -> {
            try {
                return restClientBuilders.getObject();
            } catch (Exception e) {
                log.debug("Failed to get Spring-managed RestClient.Builder: {}", e.getMessage());
                return null;
            }
        };
        // WebClient ClientHttpConnector with reasoning_content capture/injection for streaming
        ObjectMapper om = objectMappers.getIfAvailable(ObjectMapper::new);
        java.util.function.Supplier<ClientHttpConnector> connectorSupplier = () -> {
            try {
                ReactorClientHttpConnector reactorConnector = new ReactorClientHttpConnector();
                return new ReasoningContentWebClientConnector(reactorConnector, om);
            } catch (Exception e) {
                log.warn("Failed to create ReasoningContentWebClientConnector: {}", e.getMessage());
                return null;
            }
        };
        if (delegate == null) {
            log.warn("No OpenAiChatModel found, ChatModelFactory will create models on demand");
            return new ChatModelFactory(null, tcm, builderSupplier, connectorSupplier);
        }
        log.info("Created ChatModelFactory with default delegate: {}", delegate.getClass().getSimpleName());
        return new ChatModelFactory(delegate, tcm, builderSupplier, connectorSupplier);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(LoadBalancingChatModel.class)
    public LoadBalancingChatModel loadBalancingChatModel(
            ObjectProvider<OpenAiChatModel> openAiChatModels,
            LlmBalancerProperties properties,
            ModelConfigProvider modelConfigProvider,
            ModelHealthRepository healthRepository,
            TokenUsageRecorder tokenUsageRecorder,
            List<ModelSelectionStrategy> strategies,
            ChatModelFactory chatModelFactory,
            ObjectProvider<UserIdProvider> userIdProviders) {
        OpenAiChatModel delegate = resolveDefaultOpenAiChatModel(openAiChatModels);

        UserIdProvider userIdProvider = userIdProviders.getIfAvailable();
        log.info("Created LoadBalancingChatModel with defaultDelegate={}, strategy='{}', userIdProvider={}",
                delegate != null ? delegate.getClass().getSimpleName() : "none",
                properties.getStrategy(), 
                userIdProvider != null ? userIdProvider.getClass().getSimpleName() : "null");
        return new LoadBalancingChatModel(delegate, properties, modelConfigProvider, healthRepository, 
                tokenUsageRecorder, strategies, chatModelFactory, userIdProvider);
    }

    private OpenAiChatModel resolveDefaultOpenAiChatModel(ObjectProvider<OpenAiChatModel> openAiChatModels) {
        try {
            return openAiChatModels.getIfAvailable();
        } catch (Exception e) {
            log.warn("Default OpenAiChatModel is unavailable during startup: {}. Continuing without default delegate.", e.getMessage());
            return null;
        }
    }
}
