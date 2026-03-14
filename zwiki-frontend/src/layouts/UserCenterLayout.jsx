import React from 'react';
import { ConfigProvider, Layout, Menu } from 'antd';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { 
  FolderOutlined, 
  ApiOutlined,
  ThunderboltOutlined,
  CodeOutlined,
  KeyOutlined,
  SettingOutlined,
  ToolOutlined,
  HistoryOutlined,
  AppstoreOutlined
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import HeaderNav from '../components/HeaderNav';
import { getCenterTheme, getCenterPalette } from '../theme/centerTheme';

const { Content, Sider } = Layout;

const UserCenterLayout = ({ darkMode, toggleDarkMode }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const centerTheme = getCenterTheme(darkMode);
  const centerPalette = getCenterPalette(darkMode);
  const menuItems = [
    {
      key: '/center/tasks',
      icon: <FolderOutlined />,
      label: '任务管理',
    },
    {
      key: '/center/mcp',
      icon: <ApiOutlined />,
      label: 'MCP 管理',
    },
    {
      key: '/center/llm',
      icon: <ThunderboltOutlined />,
      label: '负载均衡管理',
    },
    {
      key: '/center/review',
      icon: <CodeOutlined />,
      label: '代码审查',
    },
    {
      key: '/center/apikeys',
      icon: <KeyOutlined />,
      label: 'API Key',
    },
    {
      key: '/center/review-history',
      icon: <HistoryOutlined />,
      label: '审查历史',
    },
    {
      key: '/center/dashboard',
      icon: <AppstoreOutlined />,
      label: '数据统计',
    },
    {
      key: '/center/task-compensation',
      icon: <ToolOutlined />,
      label: '任务补偿',
    },
    {
      key: '/center/settings',
      icon: <SettingOutlined />,
      label: '个人设置',
    },
  ];

  const handleMenuClick = (e) => {
    navigate(e.key);
  };

  const containerVariants = {
    hidden: { opacity: 0 },
    visible: { 
      opacity: 1,
      transition: { 
        staggerChildren: 0.1 
      } 
    },
  };

  const itemVariants = {
    hidden: { y: 20, opacity: 0 },
    visible: { 
      y: 0, 
      opacity: 1,
      transition: { type: 'spring', stiffness: 300, damping: 24 }
    }
  };

  const selectedKeys = [location.pathname];

  return (
    <ConfigProvider theme={centerTheme}>
      <Layout
        className="zwiki-center-layout"
        style={{
          minHeight: '100vh',
          background: `linear-gradient(180deg, ${centerPalette.layoutBg} 0%, ${centerPalette.layoutBg} 34%, ${centerPalette.layoutBgAlt} 100%)`,
        }}
      >
        <HeaderNav darkMode={darkMode} toggleDarkMode={toggleDarkMode} />
        <Layout style={{ background: 'transparent' }}>
          <Sider
            width={232}
            style={{
              background: centerPalette.siderBg,
              borderRight: `1px solid ${centerPalette.headerBorder}`,
              boxShadow: `0 18px 48px ${centerPalette.shadow}`,
            }}
          >
            <Menu
              className="zwiki-center-menu"
              mode="inline"
              selectedKeys={selectedKeys}
              style={{
                height: '100%',
                borderRight: 0,
                padding: '18px 0',
                background: 'transparent',
              }}
              items={menuItems}
              onClick={handleMenuClick}
            />
          </Sider>
          <Layout style={{ padding: '28px', background: 'transparent' }}>
            <Content
              className="zwiki-center-content"
              style={{
                padding: 28,
                margin: 0,
                background: centerPalette.contentBg,
                borderRadius: 24,
                border: `1px solid ${centerPalette.headerBorder}`,
                boxShadow: `0 22px 54px ${centerPalette.shadowStrong}`,
                backdropFilter: 'blur(16px)',
              }}
            >
              <motion.div
                variants={containerVariants}
                initial="hidden"
                animate="visible"
                style={{ height: '100%' }}
              >
                <motion.div variants={itemVariants}>
                  <Outlet />
                </motion.div>
              </motion.div>
            </Content>
          </Layout>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
};

export default UserCenterLayout;
