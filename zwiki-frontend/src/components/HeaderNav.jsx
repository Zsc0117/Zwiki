import React from 'react';
import { Layout, Typography, Button, Space, Tooltip, Avatar, Dropdown } from 'antd';
import { UserOutlined, BulbOutlined, MoonOutlined, GithubOutlined, LogoutOutlined, LockOutlined, LoginOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useAuth } from '../auth/AuthContext';
import NotificationBell from './NotificationBell';
import { getCenterPalette } from '../theme/centerTheme';

const { Header } = Layout;
const { Title } = Typography;

const HeaderNav = ({ darkMode, toggleDarkMode }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const { me, loading, loginWithGithub, loginWithGitee, logout } = useAuth();
  
  const isCenterPage = location.pathname.startsWith('/center');
  const isManagementPage = isCenterPage;
  const managementDark = darkMode;
  const centerPalette = getCenterPalette(managementDark);
  const iconBtnStyle = {
    width: 36,
    height: 36,
    padding: 0,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: isManagementPage ? centerPalette.heading : (managementDark ? 'rgba(255, 255, 255, 0.85)' : 'inherit'),
    borderRadius: 10,
  };
  const iconStyle = { fontSize: 18, lineHeight: 1 };

  return (
    <Header 
      style={{ 
        display: 'flex', 
        alignItems: 'center', 
        justifyContent: 'space-between',
        padding: '0 24px',
        background: isManagementPage
          ? `linear-gradient(135deg, ${centerPalette.headerBgStart} 0%, ${centerPalette.headerBgEnd} 100%)`
          : (darkMode ? '#1f1f1f' : 'transparent'),
        borderBottom: isManagementPage ? `1px solid ${centerPalette.headerBorder}` : 'none',
        boxShadow: isManagementPage ? `0 10px 30px ${centerPalette.shadow}` : '0 2px 8px rgba(0, 0, 0, 0.06)',
        position: 'sticky',
        top: 0,
        zIndex: 1000,
        height: 64
      }}
    >
      <motion.div
        initial={{ opacity: 0, x: -20 }}
        animate={{ opacity: 1, x: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        style={{ display: 'flex', alignItems: 'center' }}
      >
        <Title 
          level={3} 
          style={{ 
            margin: 0, 
            cursor: 'pointer',
            color: isManagementPage ? centerPalette.heading : (darkMode ? '#ffffff' : 'inherit')
          }}
          onClick={() => navigate(isManagementPage ? '/center' : '/')}
        >
          {'ZwikiAI'}
        </Title>
      </motion.div>

      <Space size={8} style={{ alignItems: 'center' }}>
        {isManagementPage ? (
          <Button
            onClick={() => navigate('/')}
            style={{
              borderColor: centerPalette.primaryBorder,
              color: centerPalette.heading,
              background: managementDark ? 'rgba(255, 255, 255, 0.06)' : 'rgba(255, 255, 255, 0.72)',
              borderRadius: 10,
            }}
          >
            返回首页
          </Button>
        ) : null}
        {me ? (
          <>
            <NotificationBell userId={me.userId} darkMode={managementDark} />
            <Tooltip title="私有仓库">
              <Button
                type="text"
                icon={<LockOutlined style={iconStyle} />}
                onClick={() => navigate('/private/repo')}
                disabled={loading}
                style={iconBtnStyle}
              />
            </Tooltip>
          </>
        ) : null}

        <Tooltip title={darkMode ? '切换到明亮模式' : '切换到暗黑模式'}>
          <Button
            type="text"
            icon={darkMode ? <BulbOutlined style={iconStyle} /> : <MoonOutlined style={iconStyle} />}
            onClick={toggleDarkMode}
            style={iconBtnStyle}
          />
        </Tooltip>

        {me ? (
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                ...(isManagementPage ? [] : [
                  {
                    key: 'center',
                    icon: <UserOutlined />,
                    label: '个人中心',
                  },
                ]),
                {
                  key: 'logout',
                  icon: <LogoutOutlined />,
                  label: '退出登录',
                  danger: true,
                },
              ],
              onClick: async ({ key }) => {
                if (key === 'center') {
                  navigate('/center/tasks');
                  return;
                }
                if (key === 'logout') {
                  await logout();
                  navigate('/');
                }
              },
            }}
          >
            <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center' }}>
              <Tooltip title={me.name || me.login || me.userId}>
                <Avatar size={28} src={me.avatarUrl} icon={<UserOutlined />} />
              </Tooltip>
            </div>
          </Dropdown>
        ) : (
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                {
                  key: 'github',
                  icon: <GithubOutlined />,
                  label: 'GitHub 登录',
                },
                {
                  key: 'gitee',
                  icon: (
                    <span style={{ display: 'inline-flex', alignItems: 'center' }}>
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
                        <path d="M11.984 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.016 0zm6.09 5.333c.328 0 .593.266.592.593v1.482a.594.594 0 0 1-.593.592H9.777c-.982 0-1.778.796-1.778 1.778v5.63c0 .329.267.593.593.593h5.19c.328 0 .593-.264.593-.592v-1.482a.594.594 0 0 0-.593-.593h-2.964a.593.593 0 0 1-.593-.593v-1.482a.593.593 0 0 1 .593-.592h4.738a.59.59 0 0 1 .593.592v4.741a1.778 1.778 0 0 1-1.778 1.778H8.593A1.778 1.778 0 0 1 6.815 16V8.593A3.56 3.56 0 0 1 10.37 5.04l7.7-.001z" />
                      </svg>
                    </span>
                  ),
                  label: 'Gitee 登录',
                },
              ],
              onClick: ({ key }) => {
                if (key === 'github') loginWithGithub();
                if (key === 'gitee') loginWithGitee();
              },
            }}
          >
            <Tooltip title="登录">
              <Button
                type="text"
                icon={<LoginOutlined style={iconStyle} />}
                disabled={loading}
                style={iconBtnStyle}
              />
            </Tooltip>
          </Dropdown>
        )}
      </Space>
    </Header>
  );
};

export default HeaderNav; 