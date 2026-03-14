import React from 'react';
import { Layout } from 'antd';
import { Outlet, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import HeaderNav from '../components/HeaderNav';

const { Content, Footer } = Layout;

const BasicLayout = ({ darkMode, toggleDarkMode }) => {
  const location = useLocation();
  const isRepoDetailPage = /^\/repo\//.test(location.pathname);

  return (
    <Layout style={{ minHeight: '100vh', height: isRepoDetailPage ? '100vh' : undefined }}>
      {!isRepoDetailPage && (
        <HeaderNav darkMode={darkMode} toggleDarkMode={toggleDarkMode} />
      )}
      <Content style={{ padding: '0', background: darkMode ? '#141414' : '#f5f7fa' }}>
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.5 }}
        >
          <Outlet context={{ darkMode, toggleDarkMode }} />
        </motion.div>
      </Content>
      {!isRepoDetailPage && (
        <Footer style={{ textAlign: 'center', background: darkMode ? '#1f1f1f' : '#f0f2f5' }}>
          ZwikiAI ©{new Date().getFullYear()} - 深度理解代码仓库
        </Footer>
      )}
    </Layout>
  );
};

export default BasicLayout; 