package com.zwiki.config;

import com.zwiki.queue.consumer.DocumentGenerationConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * @author pai
 * @description: Redis发布订阅配置
 * @date 2026/2/5
 */
@Configuration
@Slf4j
public class RedisQueueConfig {

    @Value("${project.wiki.redis.topics.doc-generation}")
    private String docGenerationTopic;

    @Value("${project.wiki.redis.topics.doc-retry}")
    private String docRetryTopic;

    @Value("${project.wiki.redis.topics.doc-dlq}")
    private String docDlqTopic;

    @Bean
    public MessageListenerAdapter docGenerationListenerAdapter(DocumentGenerationConsumer consumer) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(consumer, "handleDocGenerationMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    @Bean
    public MessageListenerAdapter docRetryListenerAdapter(DocumentGenerationConsumer consumer) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(consumer, "handleDocRetryMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    @Bean
    public MessageListenerAdapter docDlqListenerAdapter(DocumentGenerationConsumer consumer) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(consumer, "handleDocDlqMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            @Qualifier("docGenerationListenerAdapter") MessageListenerAdapter docGenerationListenerAdapter,
            @Qualifier("docRetryListenerAdapter") MessageListenerAdapter docRetryListenerAdapter,
            @Qualifier("docDlqListenerAdapter") MessageListenerAdapter docDlqListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(docGenerationListenerAdapter, new ChannelTopic(docGenerationTopic));
        container.addMessageListener(docRetryListenerAdapter, new ChannelTopic(docRetryTopic));
        container.addMessageListener(docDlqListenerAdapter, new ChannelTopic(docDlqTopic));
        log.info("Redis pub/sub listeners registered for channels: {}, {}, {}", docGenerationTopic, docRetryTopic, docDlqTopic);
        return container;
    }
}