import React, { useEffect, useRef } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Spin, App } from 'antd';
import { useAuth } from '../auth/AuthContext';

const ProtectedRoute = ({ children }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { me, loading, initialized, refreshMe } = useAuth();
  const { message } = App.useApp();
  const prevPathRef = useRef(location.pathname);
  const wasLoggedInRef = useRef(false);

  // 记录用户曾经登录过
  useEffect(() => {
    if (me) {
      wasLoggedInRef.current = true;
    }
  }, [me]);

  // 每次路由变化时检查登录状态
  useEffect(() => {
    if (prevPathRef.current !== location.pathname) {
      prevPathRef.current = location.pathname;
      if (wasLoggedInRef.current) {
        refreshMe().then((userData) => {
          if (!userData) {
            message.warning('登录信息过期，请重新登录');
            navigate('/login', { replace: true, state: { from: location } });
          }
        });
      }
    }
  }, [location.pathname, refreshMe, message, navigate, location]);

  if (!initialized || loading) {
    return (
      <div style={{ padding: 24, textAlign: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!me) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return children;
};

export default ProtectedRoute;
