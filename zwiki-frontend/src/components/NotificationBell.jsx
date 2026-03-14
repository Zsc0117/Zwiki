import React, { useState, useCallback } from 'react';
import { Badge, Dropdown, List, Typography, Button, Empty, Spin, Tooltip, Space } from 'antd';
import { BellOutlined, CheckOutlined } from '@ant-design/icons';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { useNotification } from '../hooks/useNotification';
import { centerPalette } from '../theme/centerTheme';

const { Text, Paragraph } = Typography;

/**
 * 通知铃铛组件
 * 显示系统通知，支持实时更新
 */
const NotificationBell = ({ userId, darkMode }) => {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [selectedNotification, setSelectedNotification] = useState(null);
  const {
    notifications,
    unreadCount,
    loading,
    connected,
    loadNotifications,
    loadUnreadCount,
    markAllAsRead,
    markAsRead,
  } = useNotification(userId);

  // 获取通知颜色
  const getNotificationColor = (type) => {
    switch (type) {
      case 'TASK_QUEUED':
        return centerPalette.queue;
      case 'TASK_STARTED':
        return centerPalette.accent;
      case 'TASK_COMPLETED':
        return centerPalette.success;
      case 'TASK_FAILED':
        return centerPalette.danger;
      case 'QUEUE_TIMEOUT':
        return centerPalette.warning;
      case 'SYSTEM':
        return centerPalette.system;
      default:
        return centerPalette.neutral;
    }
  };

  // 格式化时间
  const formatTime = (timestamp) => {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now - date;
    
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`;
    if (diff < 604800000) return `${Math.floor(diff / 86400000)}天前`;
    
    return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
  };

  // 处理通知点击
  const handleNotificationClick = useCallback((notification) => {
    if (!notification.read) {
      markAsRead(notification.id);
    }

    // 点击消息先展示完整内容，不再直接跳转
    setSelectedNotification(notification);
  }, [markAsRead]);

  // 通知列表内容
  const notificationContent = (
    <div 
      style={{ 
        width: 360,
        maxHeight: 480,
        background: darkMode ? '#1f1f1f' : '#fff',
        borderRadius: 8,
        boxShadow: '0 6px 16px rgba(0, 0, 0, 0.12)',
        overflow: 'hidden',
      }}
    >
      {/* 头部 */}
      <div 
        style={{ 
          padding: '12px 16px',
          borderBottom: `1px solid ${darkMode ? '#303030' : '#f0f0f0'}`,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Space>
          <Text strong style={{ fontSize: 16, color: darkMode ? '#fff' : 'inherit' }}>
            消息通知
          </Text>
          {connected && (
            <Tooltip title="实时连接中">
              <div 
                style={{ 
                  width: 8, 
                  height: 8, 
                  borderRadius: '50%', 
                  background: centerPalette.queue,
                  animation: 'pulse 2s infinite',
                }} 
              />
            </Tooltip>
          )}
        </Space>
        <Space size={4}>
          {unreadCount > 0 && (
            <Tooltip title="全部标记已读">
              <Button 
                type="text" 
                size="small" 
                icon={<CheckOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  markAllAsRead();
                }}
              />
            </Tooltip>
          )}
        </Space>
      </div>

      {/* 通知列表 */}
      {selectedNotification && (
        <div
          style={{
            padding: '12px 16px',
            borderBottom: `1px solid ${darkMode ? '#303030' : '#f0f0f0'}`,
            background: darkMode ? 'rgba(255, 255, 255, 0.02)' : '#fafafa',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
            <Text strong style={{ color: darkMode ? '#fff' : 'inherit' }}>
              {selectedNotification.projectName || selectedNotification.title || '系统通知'}
            </Text>
            <Text type="secondary" style={{ fontSize: 12, flexShrink: 0 }}>
              {formatTime(selectedNotification.timestamp)}
            </Text>
          </div>
          <Paragraph
            style={{
              margin: '8px 0 0',
              color: darkMode ? 'rgba(255, 255, 255, 0.75)' : 'rgba(0, 0, 0, 0.75)',
              whiteSpace: 'pre-wrap',
            }}
          >
            {selectedNotification.message || '暂无消息内容'}
          </Paragraph>
          {selectedNotification.resourceUrl && (
            <Button
              type="link"
              size="small"
              style={{ padding: 0, marginTop: 2 }}
              onClick={(e) => {
                e.stopPropagation();
                setOpen(false);
                navigate(selectedNotification.resourceUrl);
              }}
            >
              前往相关页面
            </Button>
          )}
        </div>
      )}
      <div style={{ maxHeight: 400, overflowY: 'auto' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <Spin />
          </div>
        ) : notifications.length === 0 ? (
          <Empty 
            image={Empty.PRESENTED_IMAGE_SIMPLE} 
            description="暂无通知"
            style={{ padding: '40px 0' }}
          />
        ) : (
          <List
            dataSource={notifications}
            renderItem={(item) => (
              <motion.div
                initial={{ opacity: 0, y: -10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.2 }}
              >
                <List.Item
                  onClick={() => handleNotificationClick(item)}
                  style={{
                    padding: '12px 16px',
                    cursor: 'pointer',
                    background: item.read 
                      ? 'transparent' 
                      : (darkMode ? 'rgba(32, 180, 134, 0.12)' : 'rgba(32, 180, 134, 0.08)'),
                    borderBottom: `1px solid ${darkMode ? '#303030' : '#f0f0f0'}`,
                    transition: 'background 0.2s',
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.background = darkMode 
                      ? 'rgba(255, 255, 255, 0.05)' 
                      : 'rgba(0, 0, 0, 0.02)';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.background = item.read 
                      ? 'transparent' 
                      : (darkMode ? 'rgba(32, 180, 134, 0.12)' : 'rgba(32, 180, 134, 0.08)');
                  }}
                >
                  <List.Item.Meta
                    avatar={
                      <div 
                        style={{ 
                          width: 10,
                          height: 10,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          background: getNotificationColor(item.notificationType),
                          borderRadius: '50%',
                          marginTop: 8,
                        }}
                      />
                    }
                    title={
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <Text 
                          strong={!item.read}
                          style={{ 
                            color: darkMode ? '#fff' : 'inherit',
                            fontSize: 14,
                          }}
                        >
                          {item.projectName || item.title || '系统通知'}
                        </Text>
                        <Text 
                          type="secondary" 
                          style={{ fontSize: 12 }}
                        >
                          {formatTime(item.timestamp)}
                        </Text>
                      </div>
                    }
                    description={
                      <div>
                        <Paragraph 
                          ellipsis={{ rows: 2 }}
                          style={{ 
                            marginBottom: 0,
                            color: darkMode ? 'rgba(255, 255, 255, 0.65)' : 'rgba(0, 0, 0, 0.45)',
                            fontSize: 13,
                          }}
                        >
                          {item.message}
                        </Paragraph>
                        {item.notificationType === 'TASK_QUEUED' && item.queuePosition > 0 && (
                          <div style={{ marginTop: 4, fontSize: 12, color: centerPalette.queue }}>
                            排队第{item.queuePosition}位
                            {item.queueAheadCount > 0 && `，前方${item.queueAheadCount}个任务`}
                            {item.estimatedWaitMinutes > 0 && `，预计${item.estimatedWaitMinutes}分钟`}
                          </div>
                        )}
                      </div>
                    }
                  />
                  {!item.read && (
                    <div 
                      style={{ 
                        width: 8, 
                        height: 8, 
                        borderRadius: '50%', 
                        background: centerPalette.queue,
                        marginLeft: 8,
                        flexShrink: 0,
                      }} 
                    />
                  )}
                </List.Item>
              </motion.div>
            )}
          />
        )}
      </div>
    </div>
  );

  return (
    <>
      <style>{`
        @keyframes pulse {
          0% {
            box-shadow: 0 0 0 0 rgba(82, 196, 26, 0.4);
          }
          70% {
            box-shadow: 0 0 0 6px rgba(82, 196, 26, 0);
          }
          100% {
            box-shadow: 0 0 0 0 rgba(82, 196, 26, 0);
          }
        }
      `}</style>
      <Dropdown
        trigger={['click']}
        open={open}
        onOpenChange={(nextOpen) => {
          setOpen(nextOpen);
          if (nextOpen) {
            loadNotifications(0, 20, false);
            loadUnreadCount();
          } else {
            setSelectedNotification(null);
          }
        }}
        popupRender={() => notificationContent}
        placement="bottomRight"
      >
        <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center' }}>
          <Tooltip title="消息通知">
            <Badge 
              count={unreadCount} 
              size="small"
              offset={[-2, 2]}
              style={{ boxShadow: 'none' }}
            >
              <Button
                type="text"
                icon={<BellOutlined style={{ fontSize: 18, lineHeight: 1 }} />}
                style={{
                  width: 36,
                  height: 36,
                  padding: 0,
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: darkMode ? 'rgba(255, 255, 255, 0.85)' : 'inherit',
                }}
              />
            </Badge>
          </Tooltip>
        </div>
      </Dropdown>
    </>
  );
};

export default NotificationBell;
