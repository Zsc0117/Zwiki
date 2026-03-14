package com.zwiki.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket握手拦截器
 * 
 * 功能：
 * 1. 在WebSocket握手前提取路径参数
 * 2. 验证参数有效性
 * 3. 将参数存储到WebSocket会话属性中
 *
 * @author zwiki
 */
@Slf4j
public class NotificationWebSocketInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, 
                                  WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        String path = request.getURI().getPath();
        log.debug("WebSocket握手请求. path: {}", path);

        try {
            // 解析路径，提取userId
            // 路径格式：/ws/notification/{userId}
            String[] pathSegments = path.split("/");
            
            if (pathSegments.length < 3) {
                log.warn("WebSocket路径格式错误. path: {}", path);
                return false;
            }

            String userId = pathSegments[pathSegments.length - 1];

            if (userId == null || userId.trim().isEmpty()) {
                log.warn("WebSocket连接缺少userId参数. path: {}", path);
                return false;
            }

            attributes.put("userId", userId);
            log.info("WebSocket握手验证通过. userId: {}, path: {}", userId, path);
            return true;

        } catch (Exception e) {
            log.error("WebSocket握手处理失败. path: {}", path, e);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, 
                              WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("WebSocket握手异常. path: {}", request.getURI().getPath(), exception);
        } else {
            log.debug("WebSocket握手完成. path: {}", request.getURI().getPath());
        }
    }
}
