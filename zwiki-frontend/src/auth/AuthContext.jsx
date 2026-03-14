import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { AuthApi } from '../api/auth';

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [me, setMe] = useState(null);
  const [loading, setLoading] = useState(true);
  const [initialized, setInitialized] = useState(false);

  const refreshMe = useCallback(async () => {
    setLoading(true);
    try {
      const res = await AuthApi.me({ timeout: 5000 });
      if (res && res.code === 200) {
        setMe(res.data);
        if (res.data?.userId) {
          localStorage.setItem('userId', res.data.userId);
        }
        // 存储Sa-Token
        if (res.data?.token) {
          localStorage.setItem('satoken', res.data.token);
        }
        return res.data;
      }
      setMe(null);
      localStorage.removeItem('userId');
      localStorage.removeItem('satoken');
      return null;
    } catch (e) {
      setMe(null);
      localStorage.removeItem('userId');
      localStorage.removeItem('satoken');
      return null;
    } finally {
      setLoading(false);
      setInitialized(true);
    }
  }, []);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  const loginWithGithub = useCallback(() => {
    window.location.href = AuthApi.getGithubLoginUrl();
  }, []);

  const loginWithGitee = useCallback(() => {
    window.location.href = AuthApi.getGiteeLoginUrl();
  }, []);

  const logout = useCallback(async () => {
    try {
      await AuthApi.logout({ timeout: 5000 });
    } finally {
      setMe(null);
      localStorage.removeItem('userId');
      localStorage.removeItem('satoken');
      setInitialized(true);
    }
  }, []);

  const value = useMemo(
    () => ({
      me,
      loading,
      initialized,
      refreshMe,
      loginWithGithub,
      loginWithGitee,
      logout,
    }),
    [me, loading, initialized, refreshMe, loginWithGithub, loginWithGitee, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
};
