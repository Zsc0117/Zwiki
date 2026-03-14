package com.zwiki.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;

/**
 * @author pai
 * @description: 配置 WebClient 确保流式响应使用 UTF-8 编码。Spring AI OpenAI starter 使用 WebClient 进行流式请求，需要确保中文内容正确编码。
 * @date 2026/1/19
 */
@Configuration
public class WebClientEncodingConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        // 配置 Reactor Netty HttpClient，确保使用 UTF-8
        HttpClient httpClient = HttpClient.create()
                .headers(headers -> headers.add("Accept-Charset", StandardCharsets.UTF_8.name()));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024); // 16MB
                    configurer.defaultCodecs().enableLoggingRequestDetails(true);
                    // 使用 UTF-8 的 Jackson 编解码器
                    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder());
                    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder());
                })
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);
    }
}
