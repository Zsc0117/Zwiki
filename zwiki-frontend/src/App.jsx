import React, { useState, Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { ConfigProvider, App as AntApp, Spin, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import UserCenterLayout from './layouts/UserCenterLayout';
import BasicLayout from './layouts/BasicLayout';
import HomePage from './pages/HomePage';
import { AuthProvider } from './auth/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import ChatWidget from './components/ChatWidget';
import LoginPage from './pages/LoginPage';
import themeConfig from './theme/themeConfig';
import './App.css';

const TaskList = lazy(() => import('./pages/TaskList'));
const TaskDetail = lazy(() => import('./pages/TaskDetail'));
const TaskCreate = lazy(() => import('./pages/TaskCreate'));
const TaskEdit = lazy(() => import('./pages/TaskEdit'));
const RepoDetail = lazy(() => import('./pages/RepoDetail'));
const ThesisPreview = lazy(() => import('./pages/ThesisPreview'));
const PrivateRepoPage = lazy(() => import('./pages/PrivateRepoPage'));
const McpAdmin = lazy(() => import('./pages/McpAdmin'));
const LlmAdmin = lazy(() => import('./pages/LlmAdmin'));
const GitHubReviewConfigPage = lazy(() => import('./pages/GitHubReviewConfigPage'));
const Dashboard = lazy(() => import('./pages/Dashboard'));

const ReviewHistory = lazy(() => import('./pages/ReviewHistory'));
const UserSettings = lazy(() => import('./pages/UserSettings'));
const CodeBrowser = lazy(() => import('./pages/CodeBrowser'));
const DiagramPage = lazy(() => import('./pages/DiagramPage'));
const McpIntroPage = lazy(() => import('./pages/McpIntroPage'));
const ApiKeyManagePage = lazy(() => import('./pages/ApiKeyManagePage'));
const TaskCompensationPage = lazy(() => import('./components/TaskCompensationPage'));

const PageFallback = () => (
  <div style={{ padding: 24, textAlign: 'center' }}>
    <Spin size="large" />
  </div>
);

const AdminRouteRedirect = () => {
  const location = useLocation();
  const targetPath = `${location.pathname.replace(/^\/admin\b/, '/center') || '/center'}${location.search}${location.hash}`;
  return <Navigate to={targetPath} replace />;
};

const App = () => {
  const [darkMode, setDarkMode] = useState(false);

  const toggleDarkMode = () => {
    setDarkMode(!darkMode);
  };

  // 合并暗色主题配置
  const mergedTheme = {
    ...themeConfig,
    algorithm: darkMode ? theme.darkAlgorithm : theme.defaultAlgorithm,
  };

  return (
    <ConfigProvider theme={mergedTheme} locale={zhCN}>
      <AntApp>
        <AuthProvider>
          <BrowserRouter
            future={{
              v7_startTransition: true,
              v7_relativeSplatPath: true,
            }}
          >
            <Suspense fallback={<PageFallback />}>
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route
                  path="/thesis"
                  element={
                    <ProtectedRoute>
                      <ThesisPreview />
                    </ProtectedRoute>
                  }
                />
                <Route
                  path="/code"
                  element={
                    <ProtectedRoute>
                      <CodeBrowser />
                    </ProtectedRoute>
                  }
                />
                {/* 前台路由 */}
                <Route
                  path="/"
                  element={
                    <ProtectedRoute>
                      <BasicLayout darkMode={darkMode} toggleDarkMode={toggleDarkMode} />
                    </ProtectedRoute>
                  }
                >
                  <Route index element={<HomePage darkMode={darkMode} />} />
                  <Route path="private/repo" element={<PrivateRepoPage />} />
                  <Route path="repo/:taskId" element={<RepoDetail darkMode={darkMode} />} />
                  <Route path="repo/:taskId/diagrams" element={<DiagramPage darkMode={darkMode} />} />
                  <Route path="mcp" element={<McpIntroPage darkMode={darkMode} />} />
                </Route>

                {/* 个人中心路由 */}
                <Route
                  path="/center"
                  element={
                    <ProtectedRoute>
                      <UserCenterLayout darkMode={darkMode} toggleDarkMode={toggleDarkMode} />
                    </ProtectedRoute>
                  }
                >
                  <Route index element={<Navigate to="/center/tasks" replace />} />
                  <Route path="tasks" element={<TaskList darkMode={darkMode} scope="mine" showScopeFilter />} />
                  <Route path="task/detail/:taskId" element={<TaskDetail darkMode={darkMode} />} />
                  <Route path="task/create" element={<TaskCreate darkMode={darkMode} />} />
                  <Route path="task/edit/:taskId" element={<TaskEdit darkMode={darkMode} />} />
                  <Route path="mcp" element={<McpAdmin darkMode={darkMode} />} />
                  <Route path="llm" element={<LlmAdmin darkMode={darkMode} />} />
                  <Route path="review" element={<GitHubReviewConfigPage darkMode={darkMode} />} />
                  <Route path="apikeys" element={<ApiKeyManagePage darkMode={darkMode} />} />
                  <Route path="review-history" element={<ReviewHistory darkMode={darkMode} />} />
                  <Route path="dashboard" element={<Dashboard darkMode={darkMode} />} />
                  <Route path="task-compensation" element={<TaskCompensationPage darkMode={darkMode} />} />
                  <Route path="settings" element={<UserSettings darkMode={darkMode} />} />
                </Route>

                {/* 管理后台路由 */}
                <Route path="/admin/*" element={<AdminRouteRedirect />} />

                {/* 重定向其他所有路径到首页 */}
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </Suspense>
            <ChatWidget />
          </BrowserRouter>
        </AuthProvider>
      </AntApp>
    </ConfigProvider>
  );
};

export default App; 