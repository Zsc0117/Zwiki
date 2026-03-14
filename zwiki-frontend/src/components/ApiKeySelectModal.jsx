import React, { useState, useEffect } from 'react';
import { Modal, Table, Button, Empty, Spin, App, Typography } from 'antd';
import { PlusOutlined, KeyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { ApiKeyApi } from '../api/apikey';

const { Text } = Typography;

const ApiKeySelectModal = ({ open, onSelect, onCancel }) => {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [keys, setKeys] = useState([]);
  const [loading, setLoading] = useState(false);
  const [revealingKeyId, setRevealingKeyId] = useState(null);

  useEffect(() => {
    if (open) {
      fetchKeys();
    }
  }, [open]);

  const fetchKeys = async () => {
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
  };

  const handleSelect = async (keyId) => {
    setRevealingKeyId(keyId);
    try {
      const resp = await ApiKeyApi.reveal(keyId);
      if (resp.code === 200 && resp.data?.apiKey) {
        onSelect(resp.data.apiKey);
      } else {
        message.error(resp.msg || '获取 API Key 失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '获取 API Key 失败');
    } finally {
      setRevealingKeyId(null);
    }
  };

  const formatDateTime = (dt) => {
    if (!dt) return '-';
    try {
      let date;
      // Handle Java LocalDateTime array format: [year, month, day, hour, minute, second]
      if (Array.isArray(dt)) {
        const [year, month, day, hour = 0, minute = 0] = dt;
        date = new Date(year, month - 1, day, hour, minute);
      } else {
        date = new Date(dt);
      }
      if (isNaN(date.getTime())) return '-';
      return date.toLocaleString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit',
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
      render: (text) => <Text strong>{text}</Text>,
    },
    {
      title: 'Key ID',
      dataIndex: 'keyId',
      key: 'keyId',
      width: 140,
      render: (text) => <Text code style={{ fontSize: 12 }}>{text}</Text>,
    },
    {
      title: 'API Key',
      dataIndex: 'apiKeyMasked',
      key: 'apiKeyMasked',
      width: 140,
      render: (text) => <Text type="secondary" style={{ fontSize: 12 }}>{text}</Text>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (text) => <Text type="secondary" style={{ fontSize: 12 }}>{formatDateTime(text)}</Text>,
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => (
        <Button
          type="primary"
          size="small"
          loading={revealingKeyId === record.keyId}
          onClick={() => handleSelect(record.keyId)}
        >
          选择
        </Button>
      ),
    },
  ];

  return (
    <Modal
      title={
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <KeyOutlined />
          选择 API Key
        </span>
      }
      open={open}
      onCancel={onCancel}
      footer={null}
      width={720}
      destroyOnHidden
    >
      <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
        请选择一个 API Key，它将被自动填入配置并复制到剪贴板。
      </Text>

      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0' }}>
          <Spin />
        </div>
      ) : keys.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无 API Key"
        >
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              onCancel();
              navigate('/center/apikeys');
            }}
          >
            立即创建 API Key
          </Button>
        </Empty>
      ) : (
        <>
          <Table
            columns={columns}
            dataSource={keys}
            rowKey="keyId"
            pagination={false}
            size="small"
          />
          <div style={{ marginTop: 12, textAlign: 'right' }}>
            <Button
              type="link"
              icon={<PlusOutlined />}
              onClick={() => {
                onCancel();
                navigate('/center/apikeys');
              }}
            >
              管理 API Key
            </Button>
          </div>
        </>
      )}
    </Modal>
  );
};

export default ApiKeySelectModal;
