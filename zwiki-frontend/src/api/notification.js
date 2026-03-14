import api from './http';

/**
 * 通知相关API
 */
export const NotificationApi = {
  /**
   * 获取通知列表
   */
  getNotifications: (page = 0, size = 20) => {
    return api.get('/notification/list', { params: { page, size } });
  },

  /**
   * 获取未读通知数量
   */
  getUnreadCount: () => {
    return api.get('/notification/unread-count');
  },

  /**
   * 标记所有通知为已读
   */
  markAllAsRead: () => {
    return api.post('/notification/mark-all-read');
  },

  /**
   * 标记单个通知为已读
   */
  markAsRead: (notificationId) => {
    return api.post(`/notification/mark-read/${notificationId}`);
  },

  /**
   * 清空所有通知
   */
  clearAll: () => {
    return api.delete('/notification/clear-all');
  },

  /**
   * 获取WebSocket状态
   */
  getWebSocketStatus: () => {
    return api.get('/notification/ws-status');
  },
};

export default NotificationApi;
