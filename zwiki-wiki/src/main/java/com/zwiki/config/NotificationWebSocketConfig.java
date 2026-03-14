package com.zwiki.config;

import com.zwiki.websocket.NotificationWebSocketHandler;
import com.zwiki.websocket.NotificationWebSocketInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket配置
 * 
 * 提供实时消息推送能力，用于：
 * 1. 任务状态实时更新
 * 2. 系统通知推送
 * 3. 进度实时推送
 *
 * WebSocket端点：
 * - /ws/notification/{userId}
 *
 * @author zwiki
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class NotificationWebSocketConfig implements WebSocketConfigurer {

    private static final String NOTIFICATION_WEBSOCKET_PATH = "/ws/notification/{userId}";

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler(), NOTIFICATION_WEBSOCKET_PATH)
                .setAllowedOrigins("*")
                .addInterceptors(new NotificationWebSocketInterceptor());
    }

    @Bean
    public NotificationWebSocketHandler notificationWebSocketHandler() {
        return new NotificationWebSocketHandler();
    }
}
