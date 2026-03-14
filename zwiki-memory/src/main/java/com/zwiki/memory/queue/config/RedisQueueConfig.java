package com.zwiki.memory.queue.config;

import com.zwiki.memory.queue.consumer.MemoryIndexConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisQueueConfig {

    private static final String LOG_PREFIX = "[RedisQueue]";

    @Value("${project.memory.redis.topics.mem-index}")
    private String memIndexTopic;

    @Value("${project.memory.redis.topics.mem-retry}")
    private String memRetryTopic;

    @Bean
    public MessageListenerAdapter memIndexListenerAdapter(MemoryIndexConsumer consumer) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(consumer, "handleMemIndexMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    @Bean
    public MessageListenerAdapter memRetryListenerAdapter(MemoryIndexConsumer consumer) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(consumer, "handleRetryMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            @Qualifier("memIndexListenerAdapter") MessageListenerAdapter memIndexListenerAdapter,
            @Qualifier("memRetryListenerAdapter") MessageListenerAdapter memRetryListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(memIndexListenerAdapter, new ChannelTopic(memIndexTopic));
        container.addMessageListener(memRetryListenerAdapter, new ChannelTopic(memRetryTopic));
        log.info("{} Redis pub/sub listeners registered for channels: {}, {}", LOG_PREFIX, memIndexTopic, memRetryTopic);
        return container;
    }
}


