import React, { useState, useEffect, useCallback } from 'react';
import {
  App,
  Card,
  Table,
  Button,
  Space,
  Tag,
  Typography,
  Form,
  Switch,
  Spin,
  Modal,
  Input,
  Select,
  Empty,
  Alert,
  Tooltip,
  Popconfirm,
  Steps,
  Row,
  Col,
  Statistic,
  Divider,
  Collapse,
  Badge,
  theme
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ReloadOutlined,
  GithubOutlined,
  CopyOutlined,
  CheckCircleOutlined,
  SafetyCertificateOutlined,
  QuestionCircleOutlined,
  RocketOutlined,
  LinkOutlined,
  KeyOutlined,
  SettingOutlined,
  CodeOutlined,
  EyeOutlined,
  NumberOutlined,
  ClockCircleOutlined,
  BookOutlined,
  ExperimentOutlined,
  ThunderboltOutlined
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import { GitHubReviewConfigApi } from '../api/githubReviewConfig';
import { GithubApi } from '../api/github';

const { Title, Text, Paragraph } = Typography;

const GitHubReviewConfigPage = ({ darkMode }) => {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);
  const [configs, setConfigs] = useState([]);
  const [repos, setRepos] = useState([]);
  const [reposLoading, setReposLoading] = useState(false);
  const [hasToken, setHasToken] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingConfig, setEditingConfig] = useState(null);
  const [modalLoading, setModalLoading] = useState(false);
  const [guideCollapsed, setGuideCollapsed] = useState(false);
  const [form] = Form.useForm();
  const { token: themeToken } = theme.useToken();

  const fetchConfigs = useCallback(async () => {
    setLoading(true);
    try {
      const res = await GitHubReviewConfigApi.list();
      if (res.data?.code === 200) {
        setConfigs(res.data.data || []);
      }
    } catch (e) {
      message.error('加载审查配置失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  const fetchTokenStatus = useCallback(async () => {
    try {
      const res = await GitHubReviewConfigApi.tokenStatus();
      if (res.data?.code === 200) {
        setHasToken(res.data.data);
      }
    } catch {
      setHasToken(false);
    }
  }, []);

  const fetchRepos = useCallback(async () => {
    setReposLoading(true);
    try {
      const res = await GithubApi.listPrivateRepos('');
      if (res.data?.code === 200) {
        setRepos(res.data.data || []);
      }
    } catch {
      // ignore
    } finally {
      setReposLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchConfigs();
    fetchTokenStatus();
    fetchRepos();
  }, [fetchConfigs, fetchTokenStatus, fetchRepos]);

  useEffect(() => {
    if (configs.length > 0) {
      setGuideCollapsed(true);
    }
  }, [configs]);

  const handleAdd = () => {
    setEditingConfig(null);
    form.resetFields();
    form.setFieldsValue({ enabled: true, webhookSecret: '', customPat: '' });
    setModalVisible(true);
  };

  const handleEdit = (record) => {
    setEditingConfig(record);
    form.setFieldsValue({
      repoFullName: record.repoFullName,
      webhookSecret: record.webhookSecret || '',
      enabled: record.enabled,
      customPat: '',
    });
    setModalVisible(true);
  };

  const handleDelete = async (id) => {
    try {
      const res = await GitHubReviewConfigApi.delete(id);
      if (res.data?.code === 200) {
        message.success('删除成功');
        fetchConfigs();
      } else {
        message.error(res.data?.message || '删除失败');
      }
    } catch {
      message.error('删除失败');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setModalLoading(true);

      const payload = {
        repoFullName: values.repoFullName,
        webhookSecret: values.webhookSecret || '',
        enabled: values.enabled,
      };
      if (values.customPat && values.customPat.trim()) {
        payload.customPat = values.customPat.trim();
      }

      let res;
      if (editingConfig) {
        res = await GitHubReviewConfigApi.update(editingConfig.id, payload);
      } else {
        res = await GitHubReviewConfigApi.create(payload);
      }

      if (res.data?.code === 200) {
        message.success(editingConfig ? '更新成功' : '添加成功');
        setModalVisible(false);
        fetchConfigs();
      } else {
        message.error(res.data?.message || '操作失败');
      }
    } catch (e) {
      if (e.errorFields) return;
      message.error('操作失败');
    } finally {
      setModalLoading(false);
    }
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text).then(() => {
      message.success('已复制到剪贴板');
    });
  };

  const columns = [
    {
      title: '仓库',
      dataIndex: 'repoFullName',
      key: 'repoFullName',
      render: (text) => (
        <Space>
          <GithubOutlined style={{ fontSize: 16 }} />
          <a
            href={`https://github.com/${text}`}
            target="_blank"
            rel="noopener noreferrer"
            style={{ fontWeight: 500 }}
          >
            {text}
          </a>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled) =>
        enabled ? (
          <Badge status="success" text={<Text style={{ fontSize: 13 }}>已启用</Text>} />
        ) : (
          <Badge status="default" text={<Text type="secondary" style={{ fontSize: 13 }}>已停用</Text>} />
        ),
    },
    {
      title: 'Token 来源',
      key: 'tokenSource',
      width: 130,
      render: (_, record) =>
        record.usingCustomPat ? (
          <Tag icon={<KeyOutlined />} color={themeToken.colorPrimary}>自定义 PAT</Tag>
        ) : (
          <Tag icon={<GithubOutlined />} color="green">OAuth 登录</Tag>
        ),
    },
    {
      title: 'Webhook 密钥',
      dataIndex: 'webhookSecret',
      key: 'webhookSecret',
      width: 130,
      render: (text) =>
        text ? (
          <Tag icon={<SafetyCertificateOutlined />} color={themeToken.colorPrimary}>已配置</Tag>
        ) : (
          <Tooltip title="未配置密钥，Webhook 请求将跳过签名验证">
            <Tag color="warning">未配置</Tag>
          </Tooltip>
        ),
    },
    {
      title: '审查次数',
      dataIndex: 'reviewCount',
      key: 'reviewCount',
      width: 100,
      align: 'center',
      render: (count) => (
        <Text strong style={{ fontSize: 15 }}>{count || 0}</Text>
      ),
    },
    {
      title: '最后审查',
      dataIndex: 'lastReviewAt',
      key: 'lastReviewAt',
      width: 170,
      render: (text) =>
        text ? (
          <Tooltip title={new Date(text).toLocaleString('zh-CN')}>
            <Space size={4}>
              <ClockCircleOutlined style={{ color: themeToken.colorTextSecondary }} />
              <Text type="secondary">{formatTimeAgo(text)}</Text>
            </Space>
          </Tooltip>
        ) : (
          <Text type="secondary">暂无记录</Text>
        ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      render: (_, record) => (
        <Space size="small">
          <Tooltip title="编辑配置">
            <Button type="text" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          </Tooltip>
          <Tooltip title="查看 Webhook URL">
            <Button
              type="text"
              size="small"
              icon={<CopyOutlined />}
              onClick={() => {
                if (record.webhookUrl) {
                  copyToClipboard(record.webhookUrl);
                }
              }}
            />
          </Tooltip>
          <Popconfirm
            title="确定删除此审查配置？"
            description="删除后，该仓库的 Webhook 将不再触发代码审查。"
            onConfirm={() => handleDelete(record.id)}
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
          >
            <Tooltip title="删除">
              <Button type="text" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const configuredRepoNames = configs.map((c) => c.repoFullName);
  const availableRepos = repos.filter((r) => !configuredRepoNames.includes(r.fullName));

  const totalReviews = configs.reduce((sum, c) => sum + (c.reviewCount || 0), 0);
  const enabledCount = configs.filter((c) => c.enabled).length;
  const webhookUrl = configs.length > 0 ? configs[0]?.webhookUrl : null;

  const currentStep = !hasToken ? 0 : configs.length === 0 ? 1 : 2;

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
    >
      {/* ============ 页面标题 ============ */}
      <div style={{ marginBottom: 24 }}>
        <Title level={2} style={{ margin: 0, display: 'flex', alignItems: 'center', gap: 10 }}>
          <CodeOutlined />
          AI 代码审查
        </Title>
        <Text type="secondary" style={{ fontSize: 14, marginTop: 4, display: 'block' }}>
          为你的 GitHub 仓库启用 AI 自动代码审查。当有新的 Pull Request 时，系统将自动分析代码变更并给出专业审查意见。
        </Text>
      </div>

      {/* ============ 统计卡片 ============ */}
      {configs.length > 0 && (
        <Row gutter={16} style={{ marginBottom: 24 }}>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic
                title="监控仓库"
                value={enabledCount}
                suffix={<Text type="secondary" style={{ fontSize: 14 }}>/ {configs.length} 个</Text>}
                prefix={<EyeOutlined style={{ color: themeToken.colorPrimary }} />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic
                title="累计审查"
                value={totalReviews}
                suffix="次"
                prefix={<NumberOutlined style={{ color: '#52c41a' }} />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={8}>
            <Card>
              <Statistic
                title="Token 来源"
                value={configs.filter(c => c.usingCustomPat).length > 0 ? '自定义 PAT' : 'OAuth'}
                prefix={<KeyOutlined style={{ color: themeToken.colorPrimary }} />}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
        </Row>
      )}

      {/* ============ 未授权提醒 ============ */}
      {hasToken === false && (
        <Alert
          type="info"
          showIcon
          icon={<GithubOutlined />}
          message="提示：未检测到 GitHub OAuth Token"
          description={
            <span>
              系统未检测到你的 GitHub OAuth Token。你仍然可以添加仓库并使用<strong>自定义 PAT</strong> 进行代码审查。
              如需使用 OAuth Token，请通过右上角头像进行 <strong>GitHub OAuth</strong> 登录授权。
            </span>
          }
          style={{ marginBottom: 24, borderRadius: 8 }}
        />
      )}

      {/* ============ 使用指南（可折叠） ============ */}
      <Card
        style={{ marginBottom: 24, borderRadius: 8 }}
        styles={{ body: { padding: configs.length > 0 && guideCollapsed ? 0 : undefined } }}
      >
        <Collapse
          ghost
          activeKey={guideCollapsed ? [] : ['guide']}
          onChange={(keys) => setGuideCollapsed(!keys.includes('guide'))}
          items={[
            {
              key: 'guide',
              label: (
                <Space size={8}>
                  <BookOutlined style={{ color: themeToken.colorPrimary }} />
                  <Text strong style={{ fontSize: 15 }}>使用指南：3 步开启 AI 代码审查</Text>
                  {configs.length === 0 && (
                    <Tag color={themeToken.colorPrimary} style={{ marginLeft: 8 }}>新手必看</Tag>
                  )}
                </Space>
              ),
              children: (
                <div style={{ padding: '8px 0' }}>
                  <Steps
                    direction="vertical"
                    size="small"
                    current={currentStep}
                    items={[
                      {
                        title: <Text strong>第 1 步：确认 GitHub Token</Text>,
                        icon: <GithubOutlined />,
                        status: hasToken ? 'finish' : 'process',
                        description: (
                          <div style={{ maxWidth: 600, paddingBottom: 8 }}>
                            <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
                              你需要提供一个 GitHub Token 来让系统读取代码和发布审查评论。有两种方式：
                            </Paragraph>
                            <ul style={{ margin: '4px 0 0', paddingLeft: 20, color: themeToken.colorTextSecondary, fontSize: 13, lineHeight: 1.8 }}>
                              <li><strong>GitHub OAuth 登录</strong>（推荐）— 通过右上角头像进行 GitHub 登录，系统自动获取 Token</li>
                              <li><strong>自定义 PAT</strong> — 在添加仓库时手动填写 Personal Access Token</li>
                            </ul>
                            {hasToken && (
                              <Tag icon={<CheckCircleOutlined />} color="success" style={{ marginTop: 4 }}>
                                OAuth Token 已就绪
                              </Tag>
                            )}
                            {hasToken === false && (
                              <Tag color="default" style={{ marginTop: 4 }}>
                                未检测到 OAuth Token，可使用自定义 PAT
                              </Tag>
                            )}
                          </div>
                        ),
                      },
                      {
                        title: <Text strong>第 2 步：在本页添加仓库</Text>,
                        icon: <SettingOutlined />,
                        status: configs.length > 0 ? 'finish' : 'process',
                        description: (
                          <div style={{ maxWidth: 600, paddingBottom: 8 }}>
                            <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
                              点击下方「<strong>添加仓库</strong>」按钮，从列表中选择你想审查的 GitHub 仓库。
                              系统会自动列出你有权限的仓库。
                            </Paragraph>
                            <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
                              <Text type="secondary" italic>可选项：</Text>
                            </Paragraph>
                            <ul style={{ margin: '2px 0 0', paddingLeft: 20, color: themeToken.colorTextSecondary, fontSize: 13, lineHeight: 1.8 }}>
                              <li><strong>Webhook Secret</strong> — 自定义一个密钥字符串（如 <Text code>my-secret-123</Text>），用于验证 Webhook 请求真实性</li>
                              <li><strong>自定义 PAT</strong> — 如果 OAuth Token 权限不足（比如需要读取组织私有仓库），可以额外提供一个
                                <a href="https://github.com/settings/tokens?type=beta" target="_blank" rel="noopener noreferrer"> Fine-grained Token</a>
                              </li>
                            </ul>
                          </div>
                        ),
                      },
                      {
                        title: <Text strong>第 3 步：到 GitHub 仓库配置 Webhook</Text>,
                        icon: <LinkOutlined />,
                        status: configs.length > 0 ? 'finish' : 'wait',
                        description: (
                          <div style={{ maxWidth: 600, paddingBottom: 8 }}>
                            {webhookUrl ? (
                              <>
                                <Paragraph type="secondary" style={{ margin: '4px 0 8px' }}>
                                  打开你的 GitHub 仓库 → <strong>Settings</strong> → <strong>Webhooks</strong> → <strong>Add webhook</strong>，按如下填写：
                                </Paragraph>
                                <div style={{
                                  background: themeToken.colorBgLayout,
                                  borderRadius: 8,
                                  padding: '12px 16px',
                                  marginBottom: 8,
                                  border: `1px solid ${themeToken.colorBorderSecondary}`
                                }}>
                                  <Row gutter={[16, 8]}>
                                    <Col span={6}><Text type="secondary">Payload URL</Text></Col>
                                    <Col span={18}>
                                      <Space>
                                        <Text code copyable={{ onCopy: () => copyToClipboard(webhookUrl) }} style={{ fontSize: 12 }}>
                                          {webhookUrl}
                                        </Text>
                                      </Space>
                                    </Col>
                                    <Col span={6}><Text type="secondary">Content type</Text></Col>
                                    <Col span={18}><Text code>application/json</Text></Col>
                                    <Col span={6}><Text type="secondary">Secret</Text></Col>
                                    <Col span={18}><Text type="secondary">填写你在上一步设置的 Webhook Secret（如有）</Text></Col>
                                    <Col span={6}><Text type="secondary">事件</Text></Col>
                                    <Col span={18}>
                                      勾选 <Tag color={themeToken.colorPrimary}>Pull requests</Tag>
                                    </Col>
                                  </Row>
                                </div>
                              </>
                            ) : (
                              <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
                                添加仓库后，此处会显示 Webhook 回调地址和配置说明。
                              </Paragraph>
                            )}
                            <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
                              配置完成后，每当有新 PR 或 PR 更新时，系统会自动进行 AI 代码审查并将结果以评论形式发布到 PR 上。
                            </Paragraph>
                          </div>
                        ),
                      },
                    ]}
                  />

                  <Divider style={{ margin: '12px 0' }} />

                  <Collapse
                    ghost
                    size="small"
                    items={[
                      {
                        key: 'faq',
                        label: <Text type="secondary"><QuestionCircleOutlined /> 常见问题</Text>,
                        children: (
                          <div style={{ fontSize: 13, lineHeight: 2 }}>
                            <Paragraph>
                              <Text strong>Q: OAuth Token 和自定义 PAT 有什么区别？</Text><br />
                              <Text type="secondary">
                                OAuth Token 在你登录时自动获取，权限由登录时的授权范围决定。
                                自定义 PAT 是你在 GitHub 上手动创建的令牌，可以精确控制权限范围。
                                如果 OAuth Token 能正常工作，则无需配置自定义 PAT。
                              </Text>
                            </Paragraph>
                            <Paragraph>
                              <Text strong>Q: Webhook Secret 是必须的吗？</Text><br />
                              <Text type="secondary">
                                不是必须的，但强烈建议配置。Secret 用于验证 Webhook 请求确实来自 GitHub，
                                防止第三方伪造请求。不配置则跳过签名验证。
                              </Text>
                            </Paragraph>
                            <Paragraph>
                              <Text strong>Q: 创建自定义 PAT 需要哪些权限？</Text><br />
                              <Text type="secondary">
                                在 GitHub → Settings → Developer settings → Fine-grained tokens 中创建，
                                需要 <Tag>Contents: Read</Tag> <Tag>Pull requests: Read and write</Tag> <Tag>Metadata: Read</Tag> 权限。
                              </Text>
                            </Paragraph>
                            <Paragraph style={{ margin: 0 }}>
                              <Text strong>Q: 审查结果会发布在哪里？</Text><br />
                              <Text type="secondary">
                                审查结果会以评论形式自动发布到 PR 页面上，包括总体评价和逐行审查意见。
                              </Text>
                            </Paragraph>
                          </div>
                        ),
                      },
                    ]}
                  />
                </div>
              ),
            },
          ]}
        />
      </Card>

      {/* ============ 仓库列表 ============ */}
      <Card
        title={
          <Space>
            <ExperimentOutlined style={{ color: themeToken.colorPrimary }} />
            <span>审查仓库列表</span>
            {configs.length > 0 && (
              <Tag color={themeToken.colorPrimary}>{enabledCount} 个启用中</Tag>
            )}
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={fetchConfigs} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
              添加仓库
            </Button>
          </Space>
        }
        style={{ borderRadius: 8 }}
      >
        {/* Webhook URL 快捷复制 */}
        {webhookUrl && (
          <div style={{
            background: themeToken.colorBgLayout,
            borderRadius: 8,
            padding: '10px 16px',
            marginBottom: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            border: `1px solid ${themeToken.colorBorderSecondary}`
          }}>
            <Space>
              <LinkOutlined style={{ color: themeToken.colorPrimary }} />
              <Text type="secondary">Webhook 回调地址：</Text>
              <Text code style={{ fontSize: 12 }}>{webhookUrl}</Text>
            </Space>
            <Button
              type="primary"
              ghost
              size="small"
              icon={<CopyOutlined />}
              onClick={() => copyToClipboard(webhookUrl)}
            >
              复制
            </Button>
          </div>
        )}

        <Spin spinning={loading}>
          <Table
            dataSource={configs}
            columns={columns}
            rowKey="id"
            pagination={false}
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    <div style={{ padding: '16px 0' }}>
                      <Paragraph type="secondary" style={{ fontSize: 15, marginBottom: 8 }}>
                        还没有添加任何仓库
                      </Paragraph>
                      <Paragraph type="secondary" style={{ fontSize: 13, marginBottom: 16 }}>
                        点击「添加仓库」选择你想要 AI 审查的 GitHub 仓库，3 分钟即可完成配置
                      </Paragraph>
                      <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
                        添加第一个仓库
                      </Button>
                    </div>
                  }
                />
              ),
            }}
          />
        </Spin>
      </Card>

      {/* ============ 工作原理卡片 ============ */}
      {configs.length > 0 && (
        <Card
          style={{ marginTop: 24, borderRadius: 8 }}
          styles={{ body: { padding: 0 } }}
        >
          <Collapse
            ghost
            items={[
              {
                key: 'how',
                label: (
                  <Space>
                    <ThunderboltOutlined style={{ color: themeToken.colorPrimary }} />
                    <Text strong>工作原理</Text>
                  </Space>
                ),
                children: (
                  <div style={{ padding: '0 8px 16px' }}>
                    <Steps
                      direction="horizontal"
                      size="small"
                      current={-1}
                      items={[
                        {
                          title: 'PR 事件',
                          description: 'GitHub 发送 Webhook',
                          icon: <GithubOutlined />,
                        },
                        {
                          title: '签名验证',
                          description: '校验请求合法性',
                          icon: <SafetyCertificateOutlined />,
                        },
                        {
                          title: 'AI 审查',
                          description: '多 Agent 协同分析',
                          icon: <RocketOutlined />,
                        },
                        {
                          title: '发布评论',
                          description: '结果写入 PR 页面',
                          icon: <CodeOutlined />,
                        },
                      ]}
                    />
                  </div>
                ),
              },
            ]}
          />
        </Card>
      )}

      {/* ============ 添加/编辑弹窗 ============ */}
      <Modal
        title={
          <Space>
            {editingConfig ? <EditOutlined /> : <PlusOutlined />}
            <span>{editingConfig ? '编辑审查配置' : '添加审查仓库'}</span>
          </Space>
        }
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={modalLoading}
        okText={editingConfig ? '保存' : '添加'}
        cancelText="取消"
        width={600}
        forceRender
      >
        <Form form={form} layout="vertical" style={{ marginTop: 20 }}>
          {/* 仓库选择 */}
          <Form.Item
            name="repoFullName"
            label={
              <Space>
                <GithubOutlined />
                <span>选择仓库</span>
              </Space>
            }
            rules={[{ required: true, message: '请选择或输入仓库名称' }]}
            extra={!editingConfig && '从列表中选择，或直接输入 owner/repo 格式的仓库全名'}
          >
            {editingConfig ? (
              <Input disabled prefix={<GithubOutlined />} />
            ) : (
              <Select
                showSearch
                placeholder="搜索仓库名称..."
                loading={reposLoading}
                size="large"
                filterOption={(input, option) =>
                  (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                }
                options={availableRepos.map((r) => ({
                  value: r.fullName,
                  label: r.fullName + (r.description ? ` — ${r.description}` : ''),
                }))}
                notFoundContent={
                  reposLoading ? (
                    <div style={{ textAlign: 'center', padding: 12 }}>
                      <Spin size="small" />
                      <div style={{ marginTop: 4, color: themeToken.colorTextSecondary, fontSize: 12 }}>加载仓库列表...</div>
                    </div>
                  ) : (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="无匹配仓库，可直接输入 owner/repo"
                      imageStyle={{ height: 40 }}
                    />
                  )
                }
              />
            )}
          </Form.Item>

          <Divider style={{ margin: '8px 0 16px' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>以下为可选配置</Text>
          </Divider>

          {/* Webhook Secret */}
          <Form.Item
            name="webhookSecret"
            label={
              <Space>
                <SafetyCertificateOutlined />
                <span>Webhook Secret</span>
                <Tooltip
                  title={
                    <div>
                      <p style={{ margin: '0 0 4px' }}>用于验证 GitHub Webhook 请求的真实性。</p>
                      <p style={{ margin: '0 0 4px' }}>请自定义一个密钥字符串（如 my-secret-123），然后在 GitHub 仓库的 Webhook 设置中填入相同的值。</p>
                      <p style={{ margin: 0 }}>留空则跳过签名验证（不推荐）。</p>
                    </div>
                  }
                >
                  <QuestionCircleOutlined style={{ color: themeToken.colorTextSecondary, cursor: 'help' }} />
                </Tooltip>
              </Space>
            }
          >
            <Input.Password
              placeholder="自定义一个密钥字符串，如 my-secret-123"
              prefix={<KeyOutlined style={{ color: themeToken.colorTextQuaternary }} />}
            />
          </Form.Item>

          {/* 自定义 PAT */}
          <Form.Item
            name="customPat"
            label={
              <Space>
                <KeyOutlined />
                <span>自定义 Personal Access Token</span>
                <Tooltip
                  title={
                    <div>
                      <p style={{ margin: '0 0 4px' }}>通常无需配置。系统默认使用你 GitHub 登录时的 OAuth Token。</p>
                      <p style={{ margin: '0 0 4px' }}>仅当 OAuth Token 权限不足时（如需要访问组织私有仓库），才需要手动提供 PAT。</p>
                      <p style={{ margin: 0 }}>
                        创建方法：GitHub → Settings → Developer settings → Fine-grained tokens
                      </p>
                    </div>
                  }
                >
                  <QuestionCircleOutlined style={{ color: themeToken.colorTextSecondary, cursor: 'help' }} />
                </Tooltip>
              </Space>
            }
          >
            <Input.Password
              placeholder="留空则使用 OAuth 登录 Token（推荐）"
              prefix={<GithubOutlined style={{ color: themeToken.colorTextQuaternary }} />}
            />
          </Form.Item>

          {editingConfig?.usingCustomPat && (
            <Alert
              type="info"
              showIcon
              message="当前使用自定义 PAT。输入新 PAT 将替换旧值，留空则保持不变。"
              style={{ marginBottom: 16, borderRadius: 6 }}
            />
          )}

          {/* 启用开关 */}
          <Form.Item name="enabled" label="启用审查" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </motion.div>
  );
};

function formatTimeAgo(dateStr) {
  const now = new Date();
  const date = new Date(dateStr);
  const diffMs = now - date;
  const diffMin = Math.floor(diffMs / 60000);
  const diffHr = Math.floor(diffMs / 3600000);
  const diffDay = Math.floor(diffMs / 86400000);

  if (diffMin < 1) return '刚刚';
  if (diffMin < 60) return `${diffMin} 分钟前`;
  if (diffHr < 24) return `${diffHr} 小时前`;
  if (diffDay < 30) return `${diffDay} 天前`;
  return date.toLocaleDateString('zh-CN');
}

export default GitHubReviewConfigPage;
