import React, { useState, useEffect, useRef } from 'react';
import { 
  Input, 
  Card, 
  Typography, 
  Space, 
  Tag, 
  Row, 
  Col,
  Empty,
  Spin,
  Progress,
  App
} from 'antd';
import { 
  SearchOutlined, 
  ArrowRightOutlined,
  GithubOutlined
} from '@ant-design/icons';
import { useNavigate, useOutletContext, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import { TaskApi } from '../api/task';
import { formatDateTime } from '../utils/dateFormat';
import { useAuth } from '../auth/AuthContext';

const { Title, Text, Paragraph } = Typography;

const HomePage = () => {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const location = useLocation();
  const { darkMode } = useOutletContext();
  const { me, initialized } = useAuth();
  const [searchValue, setSearchValue] = useState('');
  const [tasks, setTasks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [greeting, setGreeting] = useState('');
  const progressByTaskIdRef = useRef({});

  // 随机问候语
  const greetings = [
    '哪个仓库你想了解?',
    '今天想探索哪个项目?',
    '准备好学习新代码了吗?',
    '发现你的下一个项目',
    '选择一个仓库开始探索'
  ];

  // 获取随机问候语
  useEffect(() => {
    const randomIndex = Math.floor(Math.random() * greetings.length);
    setGreeting(greetings[randomIndex]);
  }, []);

  // 获取已完成的任务列表（路由切换时重新获取）
  useEffect(() => {
    if (!initialized) {
      return;
    }
    fetchTasks();
  }, [location.pathname, me?.userId, initialized]);

  const fetchTasks = async () => {
    setLoading(true);
    try {
      if (!me?.userId) {
        setTasks([]);
        return;
      }
      const response = await TaskApi.getTasksByPage(
        {
          pageIndex: 1,
          pageSize: 100,
          userId: me.userId,
        },
        { timeout: 5000 }
      );
      
      if (response.code === 200) {
        const records = response.data.records || [];
        setTasks(
          records
            .filter((t) => t)
            .map((t) => {
              const progress = progressByTaskIdRef.current?.[t.taskId];
              if (!progress) {
                return t;
              }
              return {
                ...t,
                progress: typeof progress.progress === 'number' ? progress.progress : t.progress,
                currentStep: progress.currentStep || t.currentStep,
              };
            })
        );
      } else {
        message.error(response.msg || '获取任务列表失败');
      }
    } catch (error) {
      console.error('获取任务列表失败:', error);

      const normalizedMessage = error?.normalized?.message || error?.message;
      const status = error?.normalized?.status || error?.response?.status;

      if (status === 401 || normalizedMessage?.includes('登录')) {
        message.error('登录已失效，请重新使用 GitHub 登录');
      } else if (status === 502 || normalizedMessage?.includes('Proxy error')) {
        message.error('无法连接到后端服务，请确认 zwiki-gateway 已启动(默认端口 8992)');
      } else if (normalizedMessage === 'Network Error' || error?.code === 'ERR_NETWORK') {
        message.error('网络连接失败，请检查后端服务是否正常运行');
      } else if (error?.code === 'ECONNABORTED' || normalizedMessage?.includes('timeout')) {
        message.error('请求超时，请稍后重试');
      } else {
        message.error(normalizedMessage || '获取任务列表失败');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const handleTaskProgress = (e) => {
      const detail = e?.detail;
      const taskId = detail?.taskId;
      if (!taskId) {
        return;
      }

      progressByTaskIdRef.current = {
        ...progressByTaskIdRef.current,
        [taskId]: {
          progress: detail?.progress,
          currentStep: detail?.currentStep,
        },
      };

      setTasks((prev) => {
        const exists = prev.some((t) => t?.taskId === taskId);
        if (!exists) {
          return prev;
        }
        return prev.map((t) => {
          if (!t || t.taskId !== taskId) {
            return t;
          }
          return {
            ...t,
            progress: typeof detail?.progress === 'number' ? detail.progress : t.progress,
            currentStep: detail?.currentStep || t.currentStep,
          };
        });
      });
    };

    const handleTaskNotification = (e) => {
      const detail = e?.detail;
      if (!detail?.taskId) {
        return;
      }

      // 如果是排队通知，立即更新排队信息到任务卡片
      if (detail.notificationType === 'TASK_QUEUED' && detail.queuePosition) {
        setTasks((prev) =>
          prev.map((t) => {
            if (!t || t.taskId !== detail.taskId) return t;
            return {
              ...t,
              queuePosition: detail.queuePosition,
              queueAheadCount: detail.queueAheadCount,
              estimatedWaitMinutes: detail.estimatedWaitMinutes,
            };
          })
        );
      }

      fetchTasks();
    };

    window.addEventListener('taskProgress', handleTaskProgress);
    window.addEventListener('taskNotification', handleTaskNotification);
    return () => {
      window.removeEventListener('taskProgress', handleTaskProgress);
      window.removeEventListener('taskNotification', handleTaskNotification);
    };
  }, [me?.userId, initialized]);

  const getStatusTagColor = (status) => {
    const normalized = String(status || '').toLowerCase();
    if (normalized.includes('completed') || normalized.includes('已完成')) {
      return 'success';
    }
    if (normalized.includes('failed') || normalized.includes('失败')) {
      return 'error';
    }
    if (
      normalized.includes('in_progress') ||
      normalized.includes('processing') ||
      normalized.includes('running') ||
      normalized.includes('进行')
    ) {
      return 'processing';
    }
    return 'default';
  };

  // 过滤任务
  const filteredTasks = tasks.filter(task => 
    task.projectName?.toLowerCase().includes(searchValue.toLowerCase()) ||
    (task.projectUrl && task.projectUrl.toLowerCase().includes(searchValue.toLowerCase()))
  );

  // 动画变量
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
      transition: { type: 'spring', stiffness: 200, damping: 20 }
    }
  };

  const handleCardClick = (taskId) => {
    navigate(`/repo/${taskId}`);
  };

  const isProbablyRepoUrl = (value) => {
    if (!value) return false;
    const v = String(value).trim();
    return (
      v.startsWith('http://') ||
      v.startsWith('https://') ||
      v.startsWith('git@') ||
      v.includes('github.com/')
    );
  };

  const handleRepoUrlSubmit = async () => {
    const value = String(searchValue || '').trim();
    if (!value) {
      return;
    }
    if (!isProbablyRepoUrl(value)) {
      return;
    }
    try {
      const res = await TaskApi.createFromRepoUrl({
        projectUrl: value,
      });
      if (res?.code === 200) {
        message.success('已提交仓库分析');
        setSearchValue('');
        fetchTasks();
      } else {
        message.error(res?.msg || res?.message || '提交失败');
      }
    } catch (e) {
      console.error('提交仓库失败:', e);
      message.error(e?.normalized?.message || e?.message || '提交失败');
    }
  };

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', padding: '20px' }}>
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
        style={{ textAlign: 'center', marginBottom: 60 }}
      >
        <Title level={2} style={{ fontSize: 32, marginBottom: 10 }}>
          ZwikiAI
        </Title>
        <Paragraph style={{ fontSize: 18, marginBottom: 30 }}>
          {greeting}
        </Paragraph>
        
        <Input
          size="large"
          placeholder="粘贴仓库链接回车开始分析 / 输入关键字搜索"
          prefix={<SearchOutlined />}
          style={{ 
            maxWidth: 800, 
            borderRadius: 8,
            padding: '12px 16px',
            boxShadow: '0 2px 10px rgba(0, 0, 0, 0.05)'
          }}
          value={searchValue}
          onChange={e => setSearchValue(e.target.value)}
          onPressEnter={handleRepoUrlSubmit}
        />
        
        {/* MCP 入口 */}
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 20 }}>
          <div
            onClick={() => navigate('/mcp')}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              padding: '10px 24px',
              background: 'linear-gradient(135deg, #e0f7fa 0%, #b2ebf2 100%)',
              borderRadius: 24,
              cursor: 'pointer',
              transition: 'all 0.3s ease',
              border: '1px solid #80deea',
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.transform = 'translateY(-2px)';
              e.currentTarget.style.boxShadow = '0 4px 12px rgba(0, 188, 212, 0.25)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.transform = 'translateY(0)';
              e.currentTarget.style.boxShadow = 'none';
            }}
          >
            <span style={{ color: '#006064', fontWeight: 500, fontSize: 14 }}>
              把 Zwiki MCP 接入你的开发工具
            </span>
          </div>
        </div>
      </motion.div>

      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0' }}>
          <Spin size="large" tip="加载中...">
            <div />
          </Spin>
        </div>
      ) : (
        <motion.div
          variants={containerVariants}
          initial="hidden"
          animate="visible"
        >
          <Row gutter={[16, 16]} justify="start">
            {filteredTasks.length > 0 ? (
              filteredTasks.map(task => (
                <Col xs={24} sm={12} md={8} lg={8} xl={8} key={task.taskId}>
                  <motion.div variants={itemVariants}>
                    <Card 
                      hoverable
                      style={{ 
                        height: '100%',
                        borderRadius: 8,
                        boxShadow: '0 2px 10px rgba(0, 0, 0, 0.05)'
                      }}
                      onClick={() => handleCardClick(task.taskId)}
                    >
                      <Space direction="vertical" size="small" style={{ width: '100%' }}>
                        <div style={{ display: 'flex', alignItems: 'center' }}>
                          <GithubOutlined style={{ marginRight: 8 }} />
                          <Text strong style={{ fontSize: 16 }}>
                            {task.projectUrl && task.projectUrl.includes('github.com') 
                              ? task.projectUrl.split('github.com/')[1]?.split('/').slice(0, 2).join(' / ')
                              : task.projectName}
                          </Text>
                        </div>
                        
                        <Paragraph ellipsis={{ rows: 2 }} style={{ marginTop: 8, height: 44 }}>
                          {task.projectName} - 在 {formatDateTime(task.createTime, 'YYYY-MM-DD')} 创建
                        </Paragraph>

                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                          <Tag color={getStatusTagColor(task.status)}>{task.status || '-'}</Tag>
                          {typeof task.progress === 'number' ? (
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {task.progress}%
                            </Text>
                          ) : null}
                        </div>

                        {typeof task.progress === 'number' ? (
                          <Progress percent={Math.max(0, Math.min(100, task.progress))} size="small" />
                        ) : null}

                        {task.currentStep ? (
                          <Text type="secondary" style={{ fontSize: 12 }} ellipsis>
                            {task.currentStep}
                          </Text>
                        ) : null}

                        {task.queuePosition > 0 && (
                          <div style={{
                            marginTop: 4,
                            padding: '4px 8px',
                            background: 'rgba(24, 144, 255, 0.06)',
                            borderRadius: 4,
                            fontSize: 12,
                            color: '#1890ff',
                          }}>
                            排队第{task.queuePosition}位
                            {task.queueAheadCount > 0 && ` · 前方${task.queueAheadCount}个`}
                            {task.estimatedWaitMinutes > 0 && ` · 约${task.estimatedWaitMinutes}分钟`}
                          </div>
                        )}
                        
                        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
                          <ArrowRightOutlined />
                        </div>
                      </Space>
                    </Card>
                  </motion.div>
                </Col>
              ))
            ) : (
              <Col span={24}>
                <Empty
                  description="暂无匹配的项目"
                  style={{ margin: '40px 0' }}
                />
              </Col>
            )}
          </Row>
        </motion.div>
      )}
    </div>
  );
};

export default HomePage; 