import React, { useState, useRef } from 'react';
import { Card, Col, Divider, Row, Space, Typography } from 'antd';
import {
  GithubOutlined,
  LockOutlined,
  RocketOutlined,
  FileTextOutlined,
  SearchOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { motion, useMotionValue, useTransform, animate } from 'framer-motion';

const { Title, Paragraph } = Typography;

const SLIDER_WIDTH = 420;
const THUMB_SIZE = 56;
const TRIGGER_THRESHOLD = 0.75;

const SwipeLoginSlider = ({ onGithub, onGitee, disabled }) => {
  const containerRef = useRef(null);
  const x = useMotionValue(0);
  const [triggered, setTriggered] = useState(null);
  
  const maxDrag = (SLIDER_WIDTH - THUMB_SIZE) / 2;
  
  const thumbBg = useTransform(
    x,
    [-maxDrag, -maxDrag * 0.5, 0, maxDrag * 0.5, maxDrag],
    ['#3b82f6', '#60a5fa', '#64748b', '#4ade80', '#22c55e']
  );

  const handleDragEnd = (_, info) => {
    const threshold = maxDrag * TRIGGER_THRESHOLD;
    if (info.offset.x < -threshold && !disabled) {
      setTriggered('github');
      animate(x, -maxDrag, { type: 'spring', stiffness: 300, damping: 30 });
      setTimeout(() => {
        onGithub?.();
      }, 200);
    } else if (info.offset.x > threshold && !disabled) {
      setTriggered('gitee');
      animate(x, maxDrag, { type: 'spring', stiffness: 300, damping: 30 });
      setTimeout(() => {
        onGitee?.();
      }, 200);
    } else {
      animate(x, 0, { type: 'spring', stiffness: 400, damping: 30 });
    }
  };

  return (
    <div style={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
      <div
        ref={containerRef}
        style={{
          position: 'relative',
          width: SLIDER_WIDTH,
          height: THUMB_SIZE,
          borderRadius: THUMB_SIZE / 2,
          background: 'linear-gradient(90deg, #60a5fa 0%, #60a5fa 50%, #4ade80 50%, #4ade80 100%)',
          boxShadow: 'inset 0 2px 8px rgba(0,0,0,0.15)',
        }}
      >
        {/* GitHub side hint - always visible */}
        <div
          style={{
            position: 'absolute',
            left: 18,
            top: '50%',
            transform: 'translateY(-50%)',
            color: '#fff',
            fontSize: 13,
            fontWeight: 600,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            pointerEvents: 'none',
            textShadow: '0 1px 2px rgba(0,0,0,0.2)',
          }}
        >
          <GithubOutlined style={{ fontSize: 18 }} />
          <span>← GitHub</span>
        </div>

        {/* Gitee side hint - always visible */}
        <div
          style={{
            position: 'absolute',
            right: 18,
            top: '50%',
            transform: 'translateY(-50%)',
            color: '#fff',
            fontSize: 13,
            fontWeight: 600,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            pointerEvents: 'none',
            textShadow: '0 1px 2px rgba(0,0,0,0.2)',
          }}
        >
          <span>Gitee →</span>
          <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
            <path d="M11.984 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.016 0zm6.09 5.333c.328 0 .593.266.592.593v1.482a.594.594 0 0 1-.593.592H9.777c-.982 0-1.778.796-1.778 1.778v5.63c0 .329.267.593.593.593h5.19c.328 0 .593-.264.593-.592v-1.482a.594.594 0 0 0-.593-.593h-2.964a.593.593 0 0 1-.593-.593v-1.482a.593.593 0 0 1 .593-.592h4.738a.59.59 0 0 1 .593.592v4.741a1.778 1.778 0 0 1-1.778 1.778H8.593A1.778 1.778 0 0 1 6.815 16V8.593A3.56 3.56 0 0 1 10.37 5.04l7.7-.001z" />
          </svg>
        </div>

        {/* Draggable thumb */}
        <motion.div
          drag="x"
          dragConstraints={{ left: -maxDrag, right: maxDrag }}
          dragElastic={0.1}
          onDragEnd={handleDragEnd}
          style={{
            position: 'absolute',
            left: '50%',
            top: '50%',
            x,
            marginLeft: -THUMB_SIZE / 2,
            marginTop: -THUMB_SIZE / 2,
            width: THUMB_SIZE,
            height: THUMB_SIZE,
            borderRadius: '50%',
            background: thumbBg,
            boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
            cursor: disabled ? 'not-allowed' : 'grab',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            border: '3px solid rgba(255,255,255,0.9)',
            opacity: disabled ? 0.6 : 1,
          }}
          whileTap={{ cursor: 'grabbing', scale: 1.05 }}
        >
          <div style={{ 
            color: '#fff', 
            fontSize: 11, 
            fontWeight: 600,
            textAlign: 'center',
            lineHeight: 1.2,
            userSelect: 'none',
          }}>
            {triggered === 'github' ? <GithubOutlined style={{ fontSize: 20 }} /> :
             triggered === 'gitee' ? (
               <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
                 <path d="M11.984 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.016 0zm6.09 5.333c.328 0 .593.266.592.593v1.482a.594.594 0 0 1-.593.592H9.777c-.982 0-1.778.796-1.778 1.778v5.63c0 .329.267.593.593.593h5.19c.328 0 .593-.264.593-.592v-1.482a.594.594 0 0 0-.593-.593h-2.964a.593.593 0 0 1-.593-.593v-1.482a.593.593 0 0 1 .593-.592h4.738a.59.59 0 0 1 .593.592v4.741a1.778 1.778 0 0 1-1.778 1.778H8.593A1.778 1.778 0 0 1 6.815 16V8.593A3.56 3.56 0 0 1 10.37 5.04l7.7-.001z" />
               </svg>
             ) : '拖动'}
          </div>
        </motion.div>
      </div>
      <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', textAlign: 'center' }}>
        向左滑动使用 GitHub 登录 · 向右滑动使用 Gitee 登录
      </div>
    </div>
  );
};

const LoginPage = () => {
  const location = useLocation();
  const { me, loading, initialized, loginWithGithub, loginWithGitee } = useAuth();

  const from = location.state?.from?.pathname || '/';

  if (initialized && !loading && me) {
    return <Navigate to={from} replace />;
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background:
          'radial-gradient(1100px 700px at 20% 10%, rgba(24, 144, 255, 0.18) 0%, rgba(24, 144, 255, 0) 60%), radial-gradient(900px 700px at 90% 20%, rgba(82, 196, 26, 0.10) 0%, rgba(82, 196, 26, 0) 55%), linear-gradient(135deg, #f7f9fc 0%, #eef4ff 35%, #f7f9fc 100%)',
      }}
    >
      <div style={{ width: 'min(1080px, 100%)' }}>
        <Row gutter={[20, 20]} align="middle">
          <Col xs={24} md={13}>
            <div style={{ padding: '6px 4px' }}>
              <Title level={2} style={{ margin: 0 }}>
                ZwikiAI
              </Title>
              <Paragraph style={{ marginTop: 12, marginBottom: 0, color: 'rgba(0,0,0,0.65)', fontSize: 16 }}>
                一键把代码仓库变成可搜索、可追溯、可生成文档的智能 Wiki。
              </Paragraph>

              <div style={{ marginTop: 18 }}>
                <Row gutter={[12, 12]}>
                  <Col xs={24} sm={12}>
                    <Card
                      variant="borderless"
                      style={{
                        borderRadius: 14,
                        background: '#ffffff',
                        border: '1px solid rgba(5, 5, 5, 0.06)',
                        boxShadow: '0 10px 28px rgba(0,0,0,0.06)',
                      }}
                      styles={{ body: { padding: 16 } }}
                    >
                      <Space align="start" size={10}>
                        <div style={{
                          width: 34,
                          height: 34,
                          borderRadius: 10,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          background: 'rgba(24, 144, 255, 0.12)',
                          color: '#1677ff',
                          flex: '0 0 auto',
                        }}>
                          <SearchOutlined />
                        </div>
                        <div style={{ minWidth: 0 }}>
                          <div style={{ color: 'rgba(0,0,0,0.88)', fontWeight: 600 }}>智能检索</div>
                          <div style={{ color: 'rgba(0,0,0,0.55)', fontSize: 13, marginTop: 4 }}>
                            按仓库、文档、源码快速定位关键实现。
                          </div>
                        </div>
                      </Space>
                    </Card>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Card
                      variant="borderless"
                      style={{
                        borderRadius: 14,
                        background: '#ffffff',
                        border: '1px solid rgba(5, 5, 5, 0.06)',
                        boxShadow: '0 10px 28px rgba(0,0,0,0.06)',
                      }}
                      styles={{ body: { padding: 16 } }}
                    >
                      <Space align="start" size={10}>
                        <div style={{
                          width: 34,
                          height: 34,
                          borderRadius: 10,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          background: 'rgba(0, 180, 216, 0.12)',
                          color: '#08979c',
                          flex: '0 0 auto',
                        }}>
                          <CodeOutlined />
                        </div>
                        <div style={{ minWidth: 0 }}>
                          <div style={{ color: 'rgba(0,0,0,0.88)', fontWeight: 600 }}>结构化理解</div>
                          <div style={{ color: 'rgba(0,0,0,0.55)', fontSize: 13, marginTop: 4 }}>
                            自动梳理模块、接口与调用链。
                          </div>
                        </div>
                      </Space>
                    </Card>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Card
                      variant="borderless"
                      style={{
                        borderRadius: 14,
                        background: '#ffffff',
                        border: '1px solid rgba(5, 5, 5, 0.06)',
                        boxShadow: '0 10px 28px rgba(0,0,0,0.06)',
                      }}
                      styles={{ body: { padding: 16 } }}
                    >
                      <Space align="start" size={10}>
                        <div style={{
                          width: 34,
                          height: 34,
                          borderRadius: 10,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          background: 'rgba(82, 196, 26, 0.16)',
                          color: '#389e0d',
                          flex: '0 0 auto',
                        }}>
                          <FileTextOutlined />
                        </div>
                        <div style={{ minWidth: 0 }}>
                          <div style={{ color: 'rgba(0,0,0,0.88)', fontWeight: 600 }}>论文/文档生成</div>
                          <div style={{ color: 'rgba(0,0,0,0.55)', fontSize: 13, marginTop: 4 }}>
                            一键生成章节与图表，快速成稿。
                          </div>
                        </div>
                      </Space>
                    </Card>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Card
                      variant="borderless"
                      style={{
                        borderRadius: 14,
                        background: '#ffffff',
                        border: '1px solid rgba(5, 5, 5, 0.06)',
                        boxShadow: '0 10px 28px rgba(0,0,0,0.06)',
                      }}
                      styles={{ body: { padding: 16 } }}
                    >
                      <Space align="start" size={10}>
                        <div style={{
                          width: 34,
                          height: 34,
                          borderRadius: 10,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          background: 'rgba(250, 140, 22, 0.16)',
                          color: '#d46b08',
                          flex: '0 0 auto',
                        }}>
                          <RocketOutlined />
                        </div>
                        <div style={{ minWidth: 0 }}>
                          <div style={{ color: 'rgba(0,0,0,0.88)', fontWeight: 600 }}>自动化流程</div>
                          <div style={{ color: 'rgba(0,0,0,0.55)', fontSize: 13, marginTop: 4 }}>
                            任务式分析与生成，进度可追踪。
                          </div>
                        </div>
                      </Space>
                    </Card>
                  </Col>
                </Row>
              </div>
            </div>
          </Col>

          <Col xs={24} md={11}>
            <Card
              variant="borderless"
              style={{
                borderRadius: 16,
                background: '#ffffff',
                border: '1px solid rgba(5, 5, 5, 0.06)',
                boxShadow: '0 18px 50px rgba(0,0,0,0.10)',
              }}
              styles={{ body: { padding: 22 } }}
            >
              <Space direction="vertical" size={14} style={{ width: '100%' }}>
                <div>
                  <Title level={3} style={{ margin: 0 }}>
                    登录后开始使用
                  </Title>
                  <Paragraph style={{ marginTop: 8, marginBottom: 0 }} type="secondary">
                    为确保仓库访问与数据隔离，需要先完成 GitHub 或 Gitee 授权。
                  </Paragraph>
                </div>

                <Divider style={{ margin: '8px 0' }} />

                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <Space size={10} align="center">
                    <LockOutlined />
                    <span style={{ color: 'rgba(0,0,0,0.65)' }}>支持私有仓库访问（需授权）</span>
                  </Space>
                  <Space size={10} align="center">
                    <GithubOutlined />
                    <span style={{ color: 'rgba(0,0,0,0.65)' }}>支持 GitHub / Gitee 账号登录，无需额外注册</span>
                  </Space>
                </Space>

                <SwipeLoginSlider
                  onGithub={loginWithGithub}
                  onGitee={loginWithGitee}
                  disabled={!initialized || loading}
                />

                <Paragraph style={{ margin: 0, fontSize: 12 }} type="secondary">
                  登录后将自动跳转到你刚才访问的页面。
                </Paragraph>
              </Space>
            </Card>
          </Col>
        </Row>
      </div>
    </div>
  );
};

export default LoginPage;
