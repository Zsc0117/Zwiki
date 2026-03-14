import React, { useState, useEffect, useCallback } from 'react';
import {
  App,
  Table,
  Button,
  Typography,
  Modal,
  Input,
  Alert,
  Popconfirm,
  Tag,
  theme,
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  CopyOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import { ApiKeyApi } from '../api/apikey';

const { Title, Text, Paragraph } = Typography;

const ApiKeyManagePage = ({ darkMode = false }) => {
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const [keys, setKeys] = useState([]);
  const [loading, setLoading] = useState(false);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [createName, setCreateName] = useState('');
  const [creating, setCreating] = useState(false);
  const [newKeyResult, setNewKeyResult] = useState(null);

  const fetchKeys = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await ApiKeyApi.list();
      if (resp.code === 200) {
        setKeys(resp.data || []);
      } else {
        message.error(resp.msg || '获取 API Key 列表失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '获取 API Key 列表失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    fetchKeys();
  }, [fetchKeys]);

  const handleCreate = async () => {
    if (!createName.trim()) {
      message.warning('请输入 Key 名称');
      return;
    }
    setCreating(true);
    try {
      const resp = await ApiKeyApi.create(createName.trim());
      if (resp.code === 200) {
        setNewKeyResult(resp.data);
        setCreateModalOpen(false);
        setCreateName('');
        fetchKeys();
      } else {
        message.error(resp.msg || '创建失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (keyId) => {
    try {
      const resp = await ApiKeyApi.delete(keyId);
      if (resp.code === 200) {
        message.success('已删除');
        fetchKeys();
      } else {
        message.error(resp.msg || '删除失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '删除失败');
    }
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text).then(() => {
      message.success('已复制到剪贴板');
    }).catch(() => {
      message.error('复制失败，请手动复制');
    });
  };

  const formatDateTime = (dt) => {
    if (!dt) return '-';
    try {
      let date;
      // Handle Java LocalDateTime array format: [year, month, day, hour, minute, second]
      if (Array.isArray(dt)) {
        const [year, month, day, hour = 0, minute = 0, second = 0] = dt;
        date = new Date(year, month - 1, day, hour, minute, second);
      } else {
        date = new Date(dt);
      }
      if (isNaN(date.getTime())) return '-';
      return date.toLocaleString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
      });
    } catch {
      return dt;
    }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <span>
          {text}
          {record.keyId && (
            <Text type="secondary" style={{ fontSize: 12, marginLeft: 4 }}>
              ({record.keyId.substring(0, 6)}...)
            </Text>
          )}
        </span>
      ),
    },
    {
      title: 'API Key ID',
      dataIndex: 'keyId',
      key: 'keyId',
      render: (text) => <Text copyable={{ text }}>{text}</Text>,
    },
    {
      title: 'API Key',
      dataIndex: 'apiKeyMasked',
      key: 'apiKeyMasked',
      render: (text) => <Text code>{text}</Text>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (text) => formatDateTime(text),
    },
    {
      title: '上次使用时间',
      dataIndex: 'lastUsedAt',
      key: 'lastUsedAt',
      render: (text) => text ? formatDateTime(text) : <Tag>未使用</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      render: (_, record) => (
        <Popconfirm
          title="确认删除此 API Key？"
          description="删除后使用此 Key 的服务将无法访问"
          onConfirm={() => handleDelete(record.keyId)}
          okText="删除"
          cancelText="取消"
          okButtonProps={{ danger: true }}
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.4 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Title level={3} style={{ margin: 0 }}>API Key</Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => { setCreateName(''); setCreateModalOpen(true); }}
        >
          添加新的 API Key
        </Button>
      </div>

      <Alert
        type="info"
        showIcon
        icon={<ExclamationCircleOutlined />}
        style={{ marginBottom: 24, borderRadius: 8 }}
        description={
          <div>
            <Paragraph style={{ margin: '4px 0' }}>
              1. 列表展示您账户下的全部 API Key，请妥善保管，勿与他人共享或暴露于浏览器及客户端代码中。
            </Paragraph>
            <Paragraph style={{ margin: '4px 0' }}>
              2. 为保障账户安全，如平台检测到 API Key 已发生公开泄露，可能会自动对相关密钥进行更换或失效处理。
            </Paragraph>
            <Paragraph style={{ margin: '4px 0' }}>
              3. 平台颁发的 API Key 由 <Text strong>API Key ID</Text> 与 <Text strong>签名密钥 secret</Text> 组成，完整格式为：<Text code>{'{API Key ID}.{secret}'}</Text>。
            </Paragraph>
          </div>
        }
      />

      <Table
        columns={columns}
        dataSource={keys}
        rowKey="keyId"
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无 API Key，点击右上角按钮创建' }}
      />

      <Modal
        title="创建新的 API Key"
        open={createModalOpen}
        onOk={handleCreate}
        onCancel={() => setCreateModalOpen(false)}
        confirmLoading={creating}
        okText="创建"
        cancelText="取消"
      >
        <div style={{ marginBottom: 16 }}>
          <Text>Key 名称</Text>
          <Input
            placeholder="例如：我的项目"
            value={createName}
            onChange={(e) => setCreateName(e.target.value)}
            onPressEnter={handleCreate}
            style={{ marginTop: 8 }}
            maxLength={128}
          />
        </div>
      </Modal>

      <Modal
        title="API Key 创建成功"
        open={!!newKeyResult}
        onOk={() => setNewKeyResult(null)}
        onCancel={() => setNewKeyResult(null)}
        okText="我已保存"
        cancelButtonProps={{ style: { display: 'none' } }}
        width={560}
      >
        {newKeyResult && (
          <div>
            <Alert
              type="warning"
              showIcon
              message="请立即复制并妥善保管此 API Key，关闭后将无法再次查看完整密钥！"
              style={{ marginBottom: 16 }}
            />
            <div style={{ marginBottom: 12 }}>
              <Text type="secondary">名称：</Text>
              <Text strong>{newKeyResult.name}</Text>
            </div>
            <div style={{ marginBottom: 12 }}>
              <Text type="secondary">API Key ID：</Text>
              <Text copyable>{newKeyResult.keyId}</Text>
            </div>
            <div style={{
              background: darkMode ? 'rgba(255,255,255,0.04)' : token.colorFillQuaternary,
              padding: '12px 16px',
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              border: `1px solid ${darkMode ? 'rgba(255,255,255,0.1)' : token.colorBorderSecondary}`,
            }}>
              <Text code style={{ fontSize: 14, wordBreak: 'break-all', flex: 1 }}>
                {newKeyResult.apiKey}
              </Text>
              <Button
                type="primary"
                icon={<CopyOutlined />}
                size="small"
                style={{ marginLeft: 12 }}
                onClick={() => copyToClipboard(newKeyResult.apiKey)}
              >
                复制
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </motion.div>
  );
};

export default ApiKeyManagePage;
