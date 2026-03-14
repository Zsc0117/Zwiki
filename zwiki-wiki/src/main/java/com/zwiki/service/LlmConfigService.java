package com.zwiki.service;
import com.zwiki.domain.dto.LlmBalancerConfigDto;
import com.zwiki.llm.config.LlmBalancerProperties;
import com.zwiki.repository.dao.LlmBalancerConfigRepository;
import com.zwiki.repository.entity.LlmBalancerConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmConfigService {

    private final LlmBalancerProperties properties;
    private final LlmBalancerConfigRepository llmBalancerConfigRepository;

    private static final long CONFIG_ID = 1L;

    @PostConstruct
    public void init() {
        loadBalancerConfigFromDatabase();
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        loadBalancerConfigFromDatabase();
    }

    @Transactional(readOnly = true)
    public void loadBalancerConfigFromDatabase() {
        LlmBalancerConfig config = llmBalancerConfigRepository.findById(CONFIG_ID).orElse(null);
        if (config == null) {
            return;
        }

        if (config.getStrategy() != null) {
            properties.setStrategy(config.getStrategy());
        }
        if (config.getMaxAttemptsPerRequest() != null && config.getMaxAttemptsPerRequest() > 0) {
            properties.setMaxAttemptsPerRequest(config.getMaxAttemptsPerRequest());
        }
        if (config.getUnhealthyCooldownSeconds() != null && config.getUnhealthyCooldownSeconds() > 0) {
            properties.setUnhealthyCooldownSeconds(config.getUnhealthyCooldownSeconds());
        }
        if (config.getAllowFallbackOnExplicitModel() != null) {
            properties.setAllowFallbackOnExplicitModel(config.getAllowFallbackOnExplicitModel());
        }
        if (config.getEnabled() != null) {
            properties.setEnabled(config.getEnabled());
        }

        log.info("Loaded balancer config from database: enabled={}, strategy={}, maxAttempts={}, cooldown={}s, fallback={}",
                properties.isEnabled(),
                properties.getStrategy(),
                properties.getMaxAttemptsPerRequest(),
                properties.getUnhealthyCooldownSeconds(),
                properties.isAllowFallbackOnExplicitModel());
    }

    @Transactional
    public void updateBalancerConfig(LlmBalancerConfigDto configDto) {
        LlmBalancerConfig config = llmBalancerConfigRepository.findById(CONFIG_ID)
                .orElseGet(() -> {
                    LlmBalancerConfig c = new LlmBalancerConfig();
                    c.setId(CONFIG_ID);
                    return c;
                });

        if (configDto.getStrategy() != null) {
            properties.setStrategy(configDto.getStrategy());
            config.setStrategy(configDto.getStrategy());
        }
        if (configDto.getMaxAttemptsPerRequest() > 0) {
            properties.setMaxAttemptsPerRequest(configDto.getMaxAttemptsPerRequest());
            config.setMaxAttemptsPerRequest(configDto.getMaxAttemptsPerRequest());
        }
        if (configDto.getUnhealthyCooldownSeconds() > 0) {
            properties.setUnhealthyCooldownSeconds(configDto.getUnhealthyCooldownSeconds());
            config.setUnhealthyCooldownSeconds(configDto.getUnhealthyCooldownSeconds());
        }
        properties.setAllowFallbackOnExplicitModel(configDto.isAllowFallbackOnExplicitModel());
        properties.setEnabled(configDto.isEnabled());

        config.setAllowFallbackOnExplicitModel(configDto.isAllowFallbackOnExplicitModel());
        config.setEnabled(configDto.isEnabled());

        llmBalancerConfigRepository.save(config);
        
        log.info("Balancer config updated: strategy={}, maxAttempts={}, cooldown={}s, fallback={}",
                properties.getStrategy(),
                properties.getMaxAttemptsPerRequest(),
                properties.getUnhealthyCooldownSeconds(),
                properties.isAllowFallbackOnExplicitModel());
    }
}
