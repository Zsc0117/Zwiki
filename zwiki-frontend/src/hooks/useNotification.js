import { useState, useEffect, useCallback, useRef } from 'react';
import { NotificationApi } from '../api/notification';

/**
 * 通知管理Hook
 * 提供WebSocket实时通知和通知列表管理功能
 */
export const useNotification = (userId) => {
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [totalCount, setTotalCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [connected, setConnected] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const wsRef = useRef(null);
  const reconnectTimerRef = useRef(null);
  const reconnectAttemptsRef = useRef(0);
  const userIdRef = useRef(userId);
  const connectingRef = useRef(false);
  const mountedRef = useRef(true);
  const MAX_RECONNECT_ATTEMPTS = 5;
  const PAGE_SIZE = 20;

  // Keep userIdRef in sync
  useEffect(() => {
    userIdRef.current = userId;
  }, [userId]);

  // 加载通知列表
  const loadNotifications = useCallback(async (page = 0, size = PAGE_SIZE, append = false) => {
    if (!userId) return;
    setLoading(true);
    try {
      const res = await NotificationApi.getNotifications(page, size);
      if (res?.code === 200 && res?.data) {
        const newNotifications = res.data.notifications || [];
        if (append) {
          setNotifications(prev => [...prev, ...newNotifications]);
        } else {
          setNotifications(newNotifications);
        }
        setUnreadCount(res.data.unreadCount || 0);
        setTotalCount(res.data.totalCount || 0);
        setHasMore(newNotifications.length >= size);
      }
    } catch (e) {
      console.error('[Notification] 加载通知列表失败:', e);
    } finally {
      setLoading(false);
    }
  }, [userId]);

  // 加载更多通知
  const loadMore = useCallback(async () => {
    if (!hasMore || loading) return;
    const currentPage = Math.floor(notifications.length / PAGE_SIZE);
    await loadNotifications(currentPage, PAGE_SIZE, true);
  }, [hasMore, loading, notifications.length, loadNotifications]);

  // 加载未读数量
  const loadUnreadCount = useCallback(async () => {
    if (!userId) return;
    try {
      const res = await NotificationApi.getUnreadCount();
      if (res?.code === 200 && res?.data) {
        setUnreadCount(res.data.unreadCount || 0);
      }
    } catch (e) {
      console.error('[Notification] 获取未读数量失败:', e);
    }
  }, [userId]);

  // 标记所有已读
  const markAllAsRead = useCallback(async () => {
    try {
      await NotificationApi.markAllAsRead();
      setUnreadCount(0);
      setNotifications(prev => prev.map(n => ({ ...n, read: true })));
    } catch (e) {
      console.error('[Notification] 标记已读失败:', e);
    }
  }, []);

  // 标记单个已读
  const markAsRead = useCallback(async (notificationId) => {
    try {
      await NotificationApi.markAsRead(notificationId);
      setNotifications(prev => 
        prev.map(n => n.id === notificationId ? { ...n, read: true } : n)
      );
      setUnreadCount(prev => Math.max(0, prev - 1));
    } catch (e) {
      console.error('[Notification] 标记已读失败:', e);
    }
  }, []);

  // 连接WebSocket - 使用 ref 来避免依赖导致的重连循环
  const connectWebSocket = useCallback(() => {
    const currentUserId = userIdRef.current;
    if (!currentUserId) return;

    // 防止重复连接
    if (connectingRef.current) {
      console.log('[Notification] 已有连接正在建立，跳过');
      return;
    }

    // 如果已经有活跃连接，不重复创建
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      console.log('[Notification] WebSocket已连接，跳过');
      return;
    }

    connectingRef.current = true;

    // 清理旧连接
    if (wsRef.current) {
      wsRef.current.onclose = null; // 防止触发重连逻辑
      wsRef.current.close();
      wsRef.current = null;
    }

    try {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = window.location.host;
      const url = `${protocol}//${host}/ws/notification/${currentUserId}`;
      console.log('[Notification] 连接WebSocket:', url);
      const ws = new WebSocket(url);
      let heartbeatInterval = null;

      ws.onopen = () => {
        console.log('[Notification] WebSocket连接成功');
        connectingRef.current = false;
        setConnected(true);
        reconnectAttemptsRef.current = 0;

        // 心跳保活
        heartbeatInterval = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send('ping');
          }
        }, 30000);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);

          // 心跳响应
          if (data === 'pong' || data.type === 'pong') {
            return;
          }

          // 连接成功消息
          if (data.type === 'CONNECTED') {
            console.log('[Notification] WebSocket连接确认:', data.message);
            return;
          }

          // 进度更新不添加到列表（仅实时展示）
          if (data.notificationType === 'TASK_PROGRESS') {
            window.dispatchEvent(new CustomEvent('taskProgress', { detail: data }));
            return;
          }

          // 其他通知添加到列表
          if (data.id || data.notificationType) {
            setNotifications(prev => [data, ...prev].slice(0, 100));
            if (!data.read) {
              setUnreadCount(prev => prev + 1);
            }

            if (data.taskId) {
              window.dispatchEvent(new CustomEvent('taskNotification', { detail: data }));
            }
          }
        } catch (e) {
          console.warn('[Notification] 解析WebSocket消息失败:', e);
        }
      };

      ws.onclose = (event) => {
        console.log('[Notification] WebSocket连接关闭:', event.code, event.reason);
        connectingRef.current = false;
        if (heartbeatInterval) {
          clearInterval(heartbeatInterval);
        }

        // 只有当组件仍挂载且 userId 有效时才重连，防止zombie连接
        if (!mountedRef.current) {
          console.log('[Notification] 组件已卸载，跳过重连');
          return;
        }
        setConnected(false);
        
        if (userIdRef.current && reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
          const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current), 30000);
          console.log(`[Notification] ${delay}ms后尝试重连...`);
          reconnectTimerRef.current = setTimeout(() => {
            if (!mountedRef.current) return;
            reconnectAttemptsRef.current++;
            connectWebSocket();
          }, delay);
        }
      };

      ws.onerror = (error) => {
        console.error('[Notification] WebSocket错误:', error);
        connectingRef.current = false;
      };

      wsRef.current = ws;
    } catch (e) {
      console.error('[Notification] 创建WebSocket失败:', e);
      connectingRef.current = false;
    }
  }, []); // 空依赖数组 - 使用 ref 获取最新值

  // 断开WebSocket
  const disconnectWebSocket = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (wsRef.current) {
      // 清除事件处理器，防止close触发重连逻辑（zombie连接）
      wsRef.current.onclose = null;
      wsRef.current.onerror = null;
      wsRef.current.onopen = null;
      wsRef.current.onmessage = null;
      wsRef.current.close();
      wsRef.current = null;
    }
    setConnected(false);
  }, []);

  // 用户登录时连接WebSocket并加载通知
  useEffect(() => {
    mountedRef.current = true;
    if (userId) {
      loadNotifications();
      connectWebSocket();
    } else {
      disconnectWebSocket();
      setNotifications([]);
      setUnreadCount(0);
    }

    return () => {
      mountedRef.current = false;
      disconnectWebSocket();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]); // 只依赖 userId - connectWebSocket 和 disconnectWebSocket 使用 ref 保持稳定

  return {
    notifications,
    unreadCount,
    totalCount,
    loading,
    connected,
    hasMore,
    loadNotifications,
    loadMore,
    loadUnreadCount,
    markAllAsRead,
    markAsRead,
  };
};

export default useNotification;
