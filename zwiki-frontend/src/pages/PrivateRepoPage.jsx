import React, { useEffect, useMemo, useState } from 'react';
import { Input, Card, Typography, Space, Tag, Button, Spin, Empty, Popconfirm, App } from 'antd';
import { LockOutlined, SearchOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { GithubApi } from '../api/github';
import { GiteeApi } from '../api/gitee';
import { TaskApi } from '../api/task';
import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';

const { Title, Text } = Typography;

const PrivateRepoPage = () => {
  const { message } = App.useApp();
  const { me } = useAuth();
  const navigate = useNavigate();
  const loginProvider = (me?.loginProvider || me?.provider || '').toLowerCase();
  const repoProvider = loginProvider === 'gitee' ? 'gitee' : 'github';
  const providerLabel = repoProvider === 'gitee' ? 'Gitee' : 'GitHub';
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(false);
  const [repos, setRepos] = useState([]);
  const [errorMsg, setErrorMsg] = useState('');
  const [adding, setAdding] = useState({});
  const [taskByUrl, setTaskByUrl] = useState({});
  const [taskLoading, setTaskLoading] = useState(false);

  const normalizeRepoUrl = (url) => {
    if (!url) {
      return '';
    }
    return String(url).trim().replace(/\.git$/i, '');
  };

  const fetchTasks = async () => {
    if (!me) {
      setTaskByUrl({});
      return;
    }
    setTaskLoading(true);
    try {
      const res = await TaskApi.getTasksByPage({ pageIndex: 1, pageSize: 200, userId: me.userId }, { timeout: 8000 });
      if (res && res.code === 200) {
        const records = res.data?.records || [];
        const map = {};
        records.forEach((t) => {
          const k = normalizeRepoUrl(t?.projectUrl);
          if (k) {
            map[k] = t;
          }
        });
        setTaskByUrl(map);
      } else {
        setTaskByUrl({});
      }
    } catch (e) {
      setTaskByUrl({});
    } finally {
      setTaskLoading(false);
    }
  };

  const fetchRepos = async (query) => {
    if (!me?.userId) {
      setRepos([]);
      setErrorMsg('');
      return;
    }

    setLoading(true);
    try {
      const listApi = repoProvider === 'gitee' ? GiteeApi.listPrivateRepos : GithubApi.listPrivateRepos;
      const res = await listApi(query, { timeout: 15000 });
      if (res && res.code === 200) {
        setRepos(res.data || []);
        setErrorMsg('');
      } else {
        setRepos([]);
        setErrorMsg(res?.msg || res?.message || '暂无私有仓库或无权限');
      }
    } catch (e) {
      setRepos([]);
      setErrorMsg(e?.normalized?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const t = setTimeout(() => {
      fetchRepos(q);
    }, 300);
    return () => clearTimeout(t);
  }, [q, repoProvider, me?.userId]);

  useEffect(() => {
    fetchTasks();
  }, [me?.userId]);

  const handleAdd = async (repo) => {
    if (!me?.userId) {
      return;
    }

    const projectName = repo.name;
    const params = {
      projectName,
      projectUrl: repo.cloneUrl,
      sourceType: 'git',
      creatorUserId: me.userId,
    };

    setAdding((prev) => ({ ...prev, [repo.fullName || repo.id || repo.name]: true }));
    try {
      const res = await TaskApi.createFromGit(params);
      if (res && res.code === 200) {
        message.success('已开始分析仓库');
        await fetchTasks();
        const taskId = res?.data?.taskId;
        if (taskId) {
          navigate(`/repo/${taskId}`);
        }
      } else {
        message.error(res?.msg || res?.message || '添加仓库失败');
      }
    } finally {
      setAdding((prev) => ({ ...prev, [repo.fullName || repo.id || repo.name]: false }));
    }
  };

  const handleRemove = async (e, taskId) => {
    if (e && e.stopPropagation) {
      e.stopPropagation();
    }
    if (!taskId) {
      return;
    }
    try {
      const res = await TaskApi.deleteTask(taskId);
      if (res && res.code === 200) {
        message.success('已移除');
        await fetchTasks();
      } else {
        message.error(res?.msg || res?.message || '移除失败');
      }
    } catch (err) {
      message.error(err?.normalized?.message || '移除失败');
    }
  };

  const sortedRepos = useMemo(() => {
    return [...repos].sort((a, b) => {
      const at = a.updatedAt || '';
      const bt = b.updatedAt || '';
      return bt.localeCompare(at);
    });
  }, [repos]);

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto', padding: '20px' }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Space align="center" size={12}>
          <LockOutlined />
          <Title level={3} style={{ margin: 0 }}>
            私有仓库
          </Title>
        </Space>

        <Input
          size="large"
          placeholder={me ? `搜索${providerLabel}仓库` : '请先登录'}
          prefix={<SearchOutlined />}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          disabled={!me}
          style={{ borderRadius: 8, padding: '10px 14px' }}
        />

        {loading || taskLoading ? (
          <div style={{ textAlign: 'center', padding: '40px 0' }}>
            <Spin size="large" tip="加载私有仓库中...">
              <div />
            </Spin>
          </div>
        ) : sortedRepos.length === 0 ? (
          <Empty description={me ? errorMsg || '暂无私有仓库或无权限' : '未登录'} style={{ margin: '40px 0' }} />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {sortedRepos.map((r) => {
              const key = r.fullName || r.id || r.name;
              const addLoading = !!adding[key];
              const matchedTask = taskByUrl[normalizeRepoUrl(r.cloneUrl)];
              const indexed = !!matchedTask && matchedTask.status === 'completed';
              const indexing = !!matchedTask && matchedTask.status && matchedTask.status !== 'completed';
              return (
                <Card
                  key={key}
                  hoverable
                  style={{ borderRadius: 12, background: '#fafafa' }}
                  styles={{ body: { padding: 16 } }}
                  onClick={() => {
                    if (matchedTask?.taskId) {
                      navigate(`/repo/${matchedTask.taskId}`);
                    }
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16 }}>
                    <Space direction="vertical" size={12} style={{ flex: 1, minWidth: 0 }}>
                      <Space size={10} wrap style={{ minWidth: 0 }}>
                        {!matchedTask ? <Tag color="default">未索引</Tag> : null}
                        {indexing ? <Tag color="processing">索引中</Tag> : null}
                        <Text strong style={{ fontSize: 18 }} ellipsis>
                          {r.fullName || r.name}
                        </Text>
                      </Space>

                      <Space size={10} wrap>
                        <span
                          style={{
                            width: 10,
                            height: 10,
                            borderRadius: '50%',
                            background: '#52c41a',
                            display: 'inline-block',
                          }}
                        />
                        {r.language ? <Tag color="green">{r.language}</Tag> : null}
                        {r.private ? <Tag color="cyan">私人</Tag> : <Tag>公开</Tag>}
                      </Space>

                    </Space>

                    {indexed ? (
                      <Popconfirm
                        title="确认移除该仓库？"
                        okText="移除"
                        cancelText="取消"
                        onConfirm={(e) => handleRemove(e, matchedTask?.taskId)}
                      >
                        <Button
                          danger
                          icon={<DeleteOutlined />}
                          onClick={(e) => e.stopPropagation()}
                          style={{ borderRadius: 10 }}
                        >
                          移除
                        </Button>
                      </Popconfirm>
                    ) : (
                      <Button
                        type="primary"
                        icon={<ReloadOutlined spin={indexing || addLoading} />}
                        loading={addLoading}
                        disabled={!me || indexing || addLoading}
                        onClick={(e) => {
                          e.stopPropagation();
                          handleAdd(r);
                        }}
                        style={{ borderRadius: 10 }}
                      >
                        添加到Zwiki
                      </Button>
                    )}
                  </div>
                </Card>
              );
            })}
          </Space>
        )}
      </Space>
    </div>
  );
};

export default PrivateRepoPage;
