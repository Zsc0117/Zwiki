package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.domain.dto.NotificationMessage;
import com.zwiki.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知控制器
 * 提供通知相关的REST API
 *
 * @author zwiki
 */
@Slf4j
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 获取用户通知列表
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getNotifications(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null) {
                return buildErrorResponse(401, "未登录");
            }

            List<NotificationMessage> notifications = notificationService.getNotifications(userId, page, size);
            int unreadCount = notificationService.getUnreadCount(userId);
            long totalCount = notificationService.getTotalCount(userId);

            Map<String, Object> data = new HashMap<>();
            data.put("notifications", notifications);
            data.put("unreadCount", unreadCount);
            data.put("totalCount", totalCount);
            data.put("page", page);
            data.put("size", size);

            return buildSuccessResponse("获取成功", data);

        } catch (Exception e) {
            log.error("获取通知列表失败", e);
            return buildErrorResponse(500, "获取通知列表失败");
        }
    }

    /**
     * 获取未读通知数量
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount() {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null) {
                return buildErrorResponse(401, "未登录");
            }

            int unreadCount = notificationService.getUnreadCount(userId);
            return buildSuccessResponse("获取成功", Map.of("unreadCount", unreadCount));

        } catch (Exception e) {
            log.error("获取未读数量失败", e);
            return buildErrorResponse(500, "获取未读数量失败");
        }
    }

    /**
     * 标记所有通知为已读
     */
    @PostMapping("/mark-all-read")
    public ResponseEntity<Map<String, Object>> markAllAsRead() {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null) {
                return buildErrorResponse(401, "未登录");
            }

            notificationService.markAllAsRead(userId);
            return buildSuccessResponse("标记成功", null);

        } catch (Exception e) {
            log.error("标记已读失败", e);
            return buildErrorResponse(500, "标记已读失败");
        }
    }

    /**
     * 标记单个通知为已读
     */
    @PostMapping("/mark-read/{notificationId}")
    public ResponseEntity<Map<String, Object>> markAsRead(@PathVariable("notificationId") String notificationId) {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null) {
                return buildErrorResponse(401, "未登录");
            }

            notificationService.markAsRead(userId, notificationId);
            return buildSuccessResponse("标记成功", null);

        } catch (Exception e) {
            log.error("标记已读失败", e);
            return buildErrorResponse(500, "标记已读失败");
        }
    }

    /**
     * 清空所有通知
     */
    @DeleteMapping("/clear-all")
    public ResponseEntity<Map<String, Object>> clearAllNotifications() {
        try {
            String userId = AuthUtil.getCurrentUserId();
            if (userId == null) {
                return buildErrorResponse(401, "未登录");
            }

            notificationService.clearAllNotifications(userId);
            return buildSuccessResponse("清空成功", null);

        } catch (Exception e) {
            log.error("清空通知失败", e);
            return buildErrorResponse(500, "清空通知失败");
        }
    }

    /**
     * 获取WebSocket状态（调试用）
     */
    @GetMapping("/ws-status")
    public ResponseEntity<Map<String, Object>> getWebSocketStatus() {
        try {
            String userId = AuthUtil.getCurrentUserId();
            
            Map<String, Object> data = new HashMap<>();
            data.put("onlineUserCount", notificationService.getOnlineUserCount());
            if (userId != null) {
                data.put("isOnline", notificationService.isUserOnline(userId));
            }

            return buildSuccessResponse("获取成功", data);

        } catch (Exception e) {
            log.error("获取WebSocket状态失败", e);
            return buildErrorResponse(500, "获取状态失败");
        }
    }

    private ResponseEntity<Map<String, Object>> buildSuccessResponse(String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", message);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(int code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("message", message);
        return ResponseEntity.status(code >= 500 ? 500 : code).body(result);
    }
}
