import React, { useCallback, useEffect, useState } from 'react';
import { Card, Form, Input, Switch, Select, Button, Avatar, Tag, Divider, App, Spin, Typography, Space, Popconfirm } from 'antd';
import { UserOutlined, MailOutlined, BellOutlined, SaveOutlined, GithubOutlined, LinkOutlined, DisconnectOutlined, LoginOutlined, SendOutlined } from '@ant-design/icons';
import { SettingsApi } from '../api/settings';
import { LlmApi } from '../api/llm';
import { AuthApi } from '../api/auth';
import { useAuth } from '../auth/AuthContext';
import { getCenterPalette } from '../theme/centerTheme';

const { Title, Text } = Typography;

const PROVIDER_LABELS = {
  dashscope: '阿里百炼',
  openai: 'OpenAI',
  azure: 'Azure',
  minimax: 'MiniMax',
  deepseek: 'DeepSeek',
  moonshot: 'Moonshot',
  zhipu: '智谱',
  custom: '自定义',
};

const formatTime = (time) => {
  if (!time) return '-';
  let date;
  if (Array.isArray(time)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = time;
    date = new Date(year, month - 1, day, hour, minute, second);
  } else if (typeof time === 'string') {
    date = new Date(time.replace(' ', 'T'));
  } else if (typeof time === 'number') {
    date = new Date(time);
  } else {
    date = new Date(time);
  }
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleString('zh-CN');
};

const UserSettings = ({ darkMode }) => {
  const [form] = Form.useForm();
  const { message } = App.useApp();
  const palette = getCenterPalette(darkMode);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [settings, setSettings] = useState(null);
  const [models, setModels] = useState([]);
  const [linkedAccounts, setLinkedAccounts] = useState([]);
  const [unbinding, setUnbinding] = useState(null);
  const [sendingTestEmail, setSendingTestEmail] = useState(false);
  const { refreshMe, me } = useAuth();

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [settingsRes, modelsRes, accountsRes] = await Promise.all([
        SettingsApi.get(),
        LlmApi.getModels().catch(() => null),
        AuthApi.getLinkedAccounts().catch(() => null),
      ]);
      if (settingsRes?.code === 200 && settingsRes.data) {
        setSettings(settingsRes.data);
        form.setFieldsValue({
          displayName: settingsRes.data.displayName,
          email: settingsRes.data.email,
          catalogueModel: settingsRes.data.catalogueModel || undefined,
          docGenModel: settingsRes.data.docGenModel || undefined,
          chatModel: settingsRes.data.chatModel || undefined,
          assistantModel: settingsRes.data.assistantModel || undefined,
          notificationEnabled: settingsRes.data.notificationEnabled !== false,
          emailNotificationEnabled: settingsRes.data.emailNotificationEnabled === true,
        });
      }
      if (modelsRes?.code === 200 && Array.isArray(modelsRes.data)) {
        setModels(modelsRes.data.filter(m => m.enabled));
      }
      if (accountsRes?.code === 200 && Array.isArray(accountsRes.data)) {
        setLinkedAccounts(accountsRes.data);
      }
    } catch (e) {
      message.error('加载设置失败');
    } finally {
      setLoading(false);
    }
  }, [form, message]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleBind = (provider) => {
    window.location.href = AuthApi.getBindUrl(provider);
  };

  const handleUnbind = async (provider) => {
    setUnbinding(provider);
    try {
      const res = await AuthApi.unbindProvider(provider);
      if (res?.code === 200) {
        message.success('解绑成功');
        const accountsRes = await AuthApi.getLinkedAccounts().catch(() => null);
        if (accountsRes?.code === 200 && Array.isArray(accountsRes.data)) {
          setLinkedAccounts(accountsRes.data);
        }
      } else {
        message.error(res?.msg || '解绑失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '解绑失败');
    } finally {
      setUnbinding(null);
    }
  };

  const handleSendTestEmail = async () => {
    setSendingTestEmail(true);
    try {
      const res = await SettingsApi.sendTestEmail();
      if (res?.code === 200) {
        message.success(res.data || '测试邮件已发送，请查收');
      } else {
        message.error(res?.msg || '发送失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '发送测试邮件失败');
    } finally {
      setSendingTestEmail(false);
    }
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const res = await SettingsApi.update(values);
      if (res?.code === 200) {
        message.success('设置已保存');
        await refreshMe();
      } else {
        message.error(res?.msg || '保存失败');
      }
    } catch (e) {
      if (e?.errorFields) return;
      message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large">
          <div style={{ padding: 24, color: palette.textMuted }}>加载用户设置...</div>
        </Spin>
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <Title level={3} style={{ color: palette.heading }}>个人设置</Title>

      <Card style={{ marginBottom: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24 }}>
          <Avatar size={64} src={settings?.avatarUrl} icon={<UserOutlined />} />
          <div>
            <Text strong style={{ fontSize: 18 }}>{settings?.displayName || '-'}</Text>
            <br />
            <Text type="secondary">
              {settings?.role === 'ADMIN' ? <Tag color="red">管理员</Tag> : <Tag color={palette.primary}>普通用户</Tag>}
              <span style={{ marginLeft: 8 }}>注册于 {formatTime(settings?.createTime)}</span>
            </Text>
          </div>
        </div>

        <Divider />

        <Form form={form} layout="vertical">
          <Form.Item
            name="displayName"
            label={<span><UserOutlined style={{ marginRight: 6 }} />显示名称</span>}
            rules={[{ required: true, message: '请输入显示名称' }]}
          >
            <Input placeholder="请输入显示名称" maxLength={64} />
          </Form.Item>

          <Form.Item
            name="email"
            label={<span><MailOutlined style={{ marginRight: 6 }} />邮箱地址</span>}
            rules={[{ type: 'email', message: '请输入有效的邮箱' }]}
          >
            <Input placeholder="请输入邮箱地址" />
          </Form.Item>

          <Divider orientation="left" plain style={{ margin: '8px 0 16px' }}>场景模型配置</Divider>

          <Form.Item
            name="catalogueModel"
            label="生成目录"
            extra="分析项目结构、生成文档目录时使用，建议选择推理能力强的模型"
          >
            <Select
              allowClear
              placeholder="跟随负载均衡"
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({
                label: `${m.displayName || m.name} [${PROVIDER_LABELS[m.provider] || m.provider}${m.keyName ? ` / ${m.keyName}` : ''}]`,
                value: m.uniqueId || m.name,
              }))}
            />
          </Form.Item>

          <Form.Item
            name="docGenModel"
            label="文档生成"
            extra="阅读源代码、生成每章文档内容时使用，建议选择上下文窗口大的模型"
          >
            <Select
              allowClear
              placeholder="跟随负载均衡"
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({
                label: `${m.displayName || m.name} [${PROVIDER_LABELS[m.provider] || m.provider}${m.keyName ? ` / ${m.keyName}` : ''}]`,
                value: m.uniqueId || m.name,
              }))}
            />
          </Form.Item>

          <Form.Item
            name="chatModel"
            label="项目问答 (Ask AI)"
            extra="在项目详情页中向 AI 提问时使用"
          >
            <Select
              allowClear
              placeholder="跟随负载均衡"
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({
                label: `${m.displayName || m.name} [${PROVIDER_LABELS[m.provider] || m.provider}${m.keyName ? ` / ${m.keyName}` : ''}]`,
                value: m.uniqueId || m.name,
              }))}
            />
          </Form.Item>

          <Form.Item
            name="assistantModel"
            label="智能助手"
            extra="右下角 ZwikiAI 智能助手对话时使用，可选择轻量快速的模型"
          >
            <Select
              allowClear
              placeholder="跟随负载均衡"
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({
                label: `${m.displayName || m.name} [${PROVIDER_LABELS[m.provider] || m.provider}${m.keyName ? ` / ${m.keyName}` : ''}]`,
                value: m.uniqueId || m.name,
              }))}
            />
          </Form.Item>

          <Form.Item
            name="notificationEnabled"
            label={<span><BellOutlined style={{ marginRight: 6 }} />消息通知</span>}
            valuePropName="checked"
          >
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>

          <Form.Item
            name="emailNotificationEnabled"
            label={<span><MailOutlined style={{ marginRight: 6 }} />邮件通知</span>}
            valuePropName="checked"
            extra="开启后，当项目分析完成或失败时，系统会自动发送邮件通知到您设置的邮箱（需先填写邮箱地址）"
          >
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>

          <Form.Item>
            <Button
              icon={<SendOutlined />}
              loading={sendingTestEmail}
              onClick={handleSendTestEmail}
              disabled={!form.getFieldValue('email') || !form.getFieldValue('emailNotificationEnabled')}
            >
              发送测试邮件
            </Button>
          </Form.Item>

          <Form.Item style={{ marginTop: 24 }}>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              onClick={handleSave}
              size="large"
            >
              保存设置
            </Button>
          </Form.Item>
        </Form>
      </Card>

      <Card title="账号信息" size="small" style={{ marginBottom: 24 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div><Text type="secondary">用户 ID：</Text><Text copyable>{settings?.userId}</Text></div>
          <div><Text type="secondary">最后登录：</Text><Text>{formatTime(settings?.lastLoginTime)}</Text></div>
        </div>
      </Card>

      <Card title={<span><LinkOutlined style={{ marginRight: 8 }} />账号绑定</span>} size="small">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {[{ provider: 'github', label: 'GitHub', color: '#24292e', icon: <GithubOutlined /> },
            { provider: 'gitee', label: 'Gitee', color: '#C71D23', icon: (
              <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" style={{ verticalAlign: 'middle' }}>
                <path d="M11.984 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.016 0zm6.09 5.333c.328 0 .593.266.592.593v1.482a.594.594 0 0 1-.593.592H9.777c-.982 0-1.778.796-1.778 1.778v5.63c0 .329.267.593.593.593h5.19c.328 0 .593-.264.593-.592v-1.482a.594.594 0 0 0-.593-.593h-2.964a.593.593 0 0 1-.593-.593v-1.482a.593.593 0 0 1 .593-.592h4.738a.59.59 0 0 1 .593.592v4.741a1.778 1.778 0 0 1-1.778 1.778H8.593A1.778 1.778 0 0 1 6.815 16V8.593A3.56 3.56 0 0 1 10.37 5.04l7.7-.001z" />
              </svg>
            ) },
          ].map(({ provider, label, color, icon }) => {
            const account = linkedAccounts.find(a => a.provider === provider);
            const isCurrentLogin = me?.loginProvider === provider;
            return (
              <div key={provider} style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '12px 16px', borderRadius: 8,
                border: isCurrentLogin ? `1px solid ${palette.primaryBorder}` : `1px solid ${palette.headerBorder}`,
                background: isCurrentLogin ? palette.primarySoft : (darkMode ? 'rgba(255, 255, 255, 0.03)' : '#fafafa'),
              }}>
                <Space size={12}>
                  <span style={{ color, fontSize: 20 }}>{icon}</span>
                  <div>
                    <Space size={8}>
                      <Text strong>{label}</Text>
                      {isCurrentLogin && (
                        <Tag color={palette.primary} icon={<LoginOutlined />} style={{ margin: 0 }}>当前登录</Tag>
                      )}
                    </Space>
                    {account ? (
                      <div style={{ marginTop: 2 }}>
                        <Space size={8}>
                          {account.avatarUrl && <Avatar size={20} src={account.avatarUrl} />}
                          <Text type="secondary" style={{ fontSize: 13 }}>
                            {account.login || account.name || account.email || '已绑定'}
                          </Text>
                          <Tag color="green" style={{ margin: 0 }}>已绑定</Tag>
                        </Space>
                      </div>
                    ) : (
                      <div style={{ marginTop: 2 }}>
                        <Tag style={{ margin: 0 }}>未绑定</Tag>
                      </div>
                    )}
                  </div>
                </Space>
                {account ? (
                  <Popconfirm
                    title={`确定解绑 ${label} 账号？`}
                    description="解绑后需要重新授权才能恢复"
                    onConfirm={() => handleUnbind(provider)}
                    okText="确定"
                    cancelText="取消"
                    disabled={linkedAccounts.length <= 1}
                  >
                    <Button
                      size="small"
                      danger
                      icon={<DisconnectOutlined />}
                      loading={unbinding === provider}
                      disabled={linkedAccounts.length <= 1}
                      title={linkedAccounts.length <= 1 ? '至少保留一个登录方式' : ''}
                    >
                      解绑
                    </Button>
                  </Popconfirm>
                ) : (
                  <Button
                    size="small"
                    type="primary"
                    icon={<LinkOutlined />}
                    onClick={() => handleBind(provider)}
                  >
                    绑定
                  </Button>
                )}
              </div>
            );
          })}
        </div>
        <div style={{ marginTop: 12 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            绑定后可使用任意已绑定的账号登录同一用户。不同登录方式对应不同的私有仓库访问权限。至少需保留一个登录方式。
          </Text>
        </div>
      </Card>
    </div>
  );
};

export default UserSettings;
