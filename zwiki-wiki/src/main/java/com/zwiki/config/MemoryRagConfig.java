package com.zwiki.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MemoryRagProperties.class)
public class MemoryRagConfig {
}
