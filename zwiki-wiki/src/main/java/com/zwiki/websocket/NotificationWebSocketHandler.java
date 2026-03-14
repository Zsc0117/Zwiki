package com.zwiki.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知WebSocket处理器
 * 
 * 核心功能：
 * 1. 管理用户的WebSocket连接会话
 * 2. 处理客户端发送的消息（如心跳检测）
 * 3. 向客户端推送实时通知
 * 4. 处理连接建立、断开、异常
 *
 * @author zwiki
 */
@Slf4j
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    /**
     * 存储所有用户的WebSocket会话
     * KEY: userId（用户ID）
     * VALUE: WebSocketSession（WebSocket会话对象）
     */
    private static final Map<String, WebSocketSession> USER_SESSIONS = new ConcurrentHashMap<>();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        String userId = (String) attributes.get("userId");

        if (StringUtils.isBlank(userId)) {
            log.warn("WebSocket连接缺少userId参数，关闭连接. sessionId: {}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // 如果用户已有连接，关闭旧连接
        WebSocketSession oldSession = USER_SESSIONS.get(userId);
        if (oldSession != null && oldSession.isOpen() && !StringUtils.equals(oldSession.getId(), session.getId())) {
            try {
                log.warn("检测到重复的WebSocket连接，关闭旧连接. userId: {}, oldSessionId: {}", userId, oldSession.getId());
                oldSession.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.warn("关闭旧WebSocket会话失败. userId: {}", userId, e);
            }
        }

        USER_SESSIONS.put(userId, session);
        log.info("✅ WebSocket连接建立成功. userId: {}, sessionId: {}, 当前在线用户数: {}", 
                userId, session.getId(), USER_SESSIONS.size());

        // 发送欢迎消息
        sendWelcomeMessage(session, userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到WebSocket消息: {}", payload);

        // 处理心跳检测
        if ("ping".equals(payload)) {
            session.sendMessage(new TextMessage("pong"));
            return;
        }

        // 处理订阅请求
        if (payload.startsWith("subscribe:")) {
            String taskId = payload.substring("subscribe:".length());
            session.getAttributes().put("subscribedTaskId", taskId);
            String response = String.format("{\"type\":\"subscribed\",\"taskId\":\"%s\",\"message\":\"订阅成功\"}", taskId);
            session.sendMessage(new TextMessage(response));
            log.info("用户订阅任务: userId={}, taskId={}", session.getAttributes().get("userId"), taskId);
            return;
        }

        // 处理取消订阅
        if (payload.startsWith("unsubscribe:")) {
            session.getAttributes().remove("subscribedTaskId");
            session.sendMessage(new TextMessage("{\"type\":\"unsubscribed\",\"message\":\"取消订阅成功\"}"));
            return;
        }

        log.debug("未识别的消息类型: {}", payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        if (StringUtils.isNotBlank(userId)) {
            WebSocketSession currentSession = USER_SESSIONS.get(userId);
            if (currentSession != null && StringUtils.equals(currentSession.getId(), session.getId())) {
                USER_SESSIONS.remove(userId);
            }
        }
        log.info("WebSocket连接关闭. userId: {}, sessionId: {}, status: {}, 当前在线用户数: {}", 
                userId, session.getId(), status, USER_SESSIONS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        log.error("WebSocket传输错误. userId: {}, sessionId: {}", userId, session.getId(), exception);
        
        if (session.isOpen()) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception e) {
                log.warn("关闭异常WebSocket会话失败", e);
            }
        }
    }

    private void sendWelcomeMessage(WebSocketSession session, String userId) {
        try {
            Map<String, Object> welcome = Map.of(
                    "type", "CONNECTED",
                    "message", "WebSocket连接成功",
                    "userId", userId,
                    "timestamp", System.currentTimeMillis()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(welcome)));
        } catch (Exception e) {
            log.warn("发送欢迎消息失败. userId: {}", userId, e);
        }
    }

    /**
     * 向指定用户发送消息
     */
    public static boolean sendToUser(String userId, String message) {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(message)) {
            return false;
        }

        WebSocketSession session = USER_SESSIONS.get(userId);
        if (session == null || !session.isOpen()) {
            log.debug("用户不在线，无法推送消息. userId: {}", userId);
            return false;
        }

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(message));
            }
            log.debug("消息推送成功. userId: {}", userId);
            return true;
        } catch (IOException e) {
            log.error("消息推送失败. userId: {}", userId, e);
            return false;
        }
    }

    /**
     * 广播消息给所有在线用户
     */
    public static int broadcastToAll(String message) {
        int successCount = 0;
        for (Map.Entry<String, WebSocketSession> entry : USER_SESSIONS.entrySet()) {
            if (sendToUser(entry.getKey(), message)) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * 检查用户是否在线
     */
    public static boolean isUserOnline(String userId) {
        if (StringUtils.isBlank(userId)) {
            return false;
        }
        WebSocketSession session = USER_SESSIONS.get(userId);
        return session != null && session.isOpen();
    }

    /**
     * 获取在线用户数
     */
    public static int getOnlineUserCount() {
        return USER_SESSIONS.size();
    }
}
