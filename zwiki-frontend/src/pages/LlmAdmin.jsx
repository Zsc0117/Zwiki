import React, { useState, useEffect, useCallback, useRef } from 'react';
import { 
  Card, 
  Table, 
  Button, 
  Space, 
  Tag, 
  App,
  Typography,
  Form,
  Select,
  InputNumber,
  Switch,
  Row,
  Col,
  Tooltip,
  Spin,
  Modal,
  Input,
  DatePicker,
  Popconfirm
} from 'antd';
import { 
  ThunderboltOutlined, 
  ReloadOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  SettingOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import { LlmApi } from '../api/llm';
import dayjs from 'dayjs';
import { centerPalette } from '../theme/centerTheme';

const { Title } = Typography;
const { TextArea } = Input;

const PROVIDER_OPTIONS = [
  { value: 'dashscope', label: '阿里百炼 (DashScope)', defaultBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode' },
  { value: 'openai', label: 'OpenAI', defaultBaseUrl: 'https://api.openai.com' },
  { value: 'azure', label: 'Azure OpenAI', defaultBaseUrl: '', needsApiVersion: true },
  { value: 'minimax', label: 'MiniMax', defaultBaseUrl: 'https://api.minimax.chat/v1' },
  { value: 'deepseek', label: 'DeepSeek', defaultBaseUrl: 'https://api.deepseek.com' },
  { value: 'moonshot', label: 'Moonshot/Kimi', defaultBaseUrl: 'https://api.moonshot.cn/v1' },
  { value: 'zhipu', label: '智谱 GLM', defaultBaseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  { value: 'custom', label: '自定义 (OpenAI 兼容)', defaultBaseUrl: '' },
];

const getProviderConfig = (provider) => PROVIDER_OPTIONS.find(p => p.value === provider) || PROVIDER_OPTIONS[0];

const LlmAdmin = ({ darkMode }) => {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);
  const [keys, setKeys] = useState([]);
  const [selectedKeyId, setSelectedKeyId] = useState(null);
  const [models, setModels] = useState([]);
  const [config, setConfig] = useState(null);
  const [configLoading, setConfigLoading] = useState(false);
  const [form] = Form.useForm();
  const [modelForm] = Form.useForm();
  const [modalVisible, setModalVisible] = useState(false);
  const [editingModel, setEditingModel] = useState(null);
  const [modalLoading, setModalLoading] = useState(false);
  const [keyModalVisible, setKeyModalVisible] = useState(false);
  const [keyModalLoading, setKeyModalLoading] = useState(false);
  const [editingKey, setEditingKey] = useState(null);
  const tableWrapperRef = useRef(null);
  const topScrollbarRef = useRef(null);
  const topScrollbarInnerRef = useRef(null);
  const [keyForm] = Form.useForm();
  const selectedKeyIdRef = useRef(selectedKeyId);
  selectedKeyIdRef.current = selectedKeyId;

  const fetchModels = useCallback(async (keyId = selectedKeyIdRef.current) => {
    if (!keyId) {
      setModels([]);
      return;
    }
    setLoading(true);
    try {
      const response = await LlmApi.getModelsByKey(keyId);
      if (response.code === 200) {
        setModels(response.data || []);
      } else {
        message.error(response.msg || '获取模型列表失败');
      }
    } catch (error) {
      console.error('获取模型列表出错:', error);
      message.error('获取模型列表失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  const fetchKeys = useCallback(async () => {
    try {
      const response = await LlmApi.getKeys();
      if (response.code === 200) {
        const list = response.data || [];
        setKeys(list);
        if (list.length > 0) {
          const curKeyId = selectedKeyIdRef.current;
          const nextKeyId = curKeyId && list.some(k => k.id === curKeyId)
            ? curKeyId
            : list[0].id;
          setSelectedKeyId(nextKeyId);
          fetchModels(nextKeyId);
        } else {
          setSelectedKeyId(null);
          setModels([]);
        }
      } else {
        message.error(response.msg || '获取Key列表失败');
      }
    } catch (error) {
      message.error('获取Key列表失败');
    }
  }, [fetchModels, message]);

  const fetchConfig = useCallback(async () => {
    setConfigLoading(true);
    try {
      const response = await LlmApi.getBalancerConfig();
      if (response.code === 200) {
        setConfig(response.data);
        form.setFieldsValue(response.data);
      }
    } catch (error) {
      console.error('获取配置出错:', error);
    } finally {
      setConfigLoading(false);
    }
  }, [form]);

  useEffect(() => {
    fetchKeys();
    fetchConfig();
  }, [fetchKeys, fetchConfig]);

  useEffect(() => {
    let cleanupFn = null;
    let isScrolling = false;
    let resizeObserver = null;
    
    const setupScrollSync = () => {
      const wrapper = tableWrapperRef.current;
      const scrollCandidates = wrapper
        ? Array.from(
            wrapper.querySelectorAll(
              '.ant-table-content, .ant-table-body, .ant-table-container'
            )
          )
        : [];

      const tableScrollEl =
        scrollCandidates.find((el) => el.scrollWidth > el.clientWidth + 1) ||
        (wrapper ? wrapper.querySelector('.ant-table-content') : null) ||
        (wrapper ? wrapper.querySelector('.ant-table-body') : null);
      const topScrollbar = topScrollbarRef.current;
      const topScrollbarInner = topScrollbarInnerRef.current;
      
      if (!tableScrollEl || !topScrollbar || !topScrollbarInner) return;

      const updateScrollbarWidth = () => {
        const scrollWidth = tableScrollEl.scrollWidth;
        topScrollbarInner.style.width = `${scrollWidth}px`;
      };

      updateScrollbarWidth();
      topScrollbar.scrollLeft = tableScrollEl.scrollLeft;

      if (typeof ResizeObserver !== 'undefined') {
        const tableEl = tableScrollEl.querySelector('table');
        resizeObserver = new ResizeObserver(() => {
          updateScrollbarWidth();
        });
        if (tableEl) {
          resizeObserver.observe(tableEl);
        } else {
          resizeObserver.observe(tableScrollEl);
        }
      }

      const handleWindowResize = () => {
        updateScrollbarWidth();
      };
      window.addEventListener('resize', handleWindowResize);

      const handleTableScroll = () => {
        if (isScrolling) return;
        isScrolling = true;
        topScrollbar.scrollLeft = tableScrollEl.scrollLeft;
        requestAnimationFrame(() => { isScrolling = false; });
      };

      const handleTopScroll = () => {
        if (isScrolling) return;
        isScrolling = true;
        tableScrollEl.scrollLeft = topScrollbar.scrollLeft;
        requestAnimationFrame(() => { isScrolling = false; });
      };

      tableScrollEl.addEventListener('scroll', handleTableScroll, { passive: true });
      topScrollbar.addEventListener('scroll', handleTopScroll, { passive: true });

      cleanupFn = () => {
        tableScrollEl.removeEventListener('scroll', handleTableScroll);
        topScrollbar.removeEventListener('scroll', handleTopScroll);
        window.removeEventListener('resize', handleWindowResize);
        if (resizeObserver) {
          resizeObserver.disconnect();
          resizeObserver = null;
        }
      };
    };

    const timer = setTimeout(setupScrollSync, 200);
    return () => {
      clearTimeout(timer);
      if (cleanupFn) cleanupFn();
    };
  }, [models, loading]);

  const openCreateKeyModal = () => {
    setEditingKey(null);
    keyForm.resetFields();
    keyForm.setFieldsValue({ provider: 'dashscope', enabled: true });
    setKeyModalVisible(true);
  };

  const openEditKeyModal = () => {
    if (!selectedKeyId) return;
    const currentKey = keys.find(k => k.id === selectedKeyId);
    if (!currentKey) return;
    setEditingKey(currentKey);
    keyForm.setFieldsValue({
      name: currentKey.name,
      provider: currentKey.provider,
      apiKey: '', // Don't show existing key for security
      baseUrl: currentKey.baseUrl || '',
      apiVersion: currentKey.apiVersion || '',
      extraHeaders: currentKey.extraHeaders || '',
      description: currentKey.description || '',
      enabled: currentKey.enabled !== false,
    });
    setKeyModalVisible(true);
  };

  const handleCreateKey = async () => {
    try {
      const values = await keyForm.validateFields();
      setKeyModalLoading(true);
      const payload = {
        name: values.name,
        provider: values.provider,
        apiKey: values.apiKey,
        baseUrl: values.baseUrl || '',
        apiVersion: values.apiVersion || '',
        extraHeaders: values.extraHeaders || '',
        description: values.description || '',
        enabled: String(values.enabled ?? true),
      };
      const response = await LlmApi.createKey(payload);
      if (response.code === 200) {
        message.success('Key创建成功');
        setKeyModalVisible(false);
        await fetchKeys();
        if (response.data?.id) {
          setSelectedKeyId(response.data.id);
          fetchModels(response.data.id);
        }
      } else {
        message.error(response.msg || 'Key创建失败');
      }
    } catch (error) {
      if (error?.errorFields) {
        return;
      }
      message.error('Key创建失败');
    } finally {
      setKeyModalLoading(false);
    }
  };

  const handleUpdateKey = async () => {
    try {
      const values = await keyForm.validateFields();
      setKeyModalLoading(true);
      const payload = {
        name: values.name,
        provider: values.provider,
        baseUrl: values.baseUrl || '',
        apiVersion: values.apiVersion || '',
        extraHeaders: values.extraHeaders || '',
        description: values.description || '',
        enabled: String(values.enabled ?? true),
      };
      // Only include apiKey if user entered a new one
      if (values.apiKey && values.apiKey.trim()) {
        payload.apiKey = values.apiKey;
      }
      const response = await LlmApi.updateKey(editingKey.id, payload);
      if (response.code === 200) {
        message.success('Key更新成功');
        setKeyModalVisible(false);
        setEditingKey(null);
        await fetchKeys();
      } else {
        message.error(response.msg || 'Key更新失败');
      }
    } catch (error) {
      if (error?.errorFields) {
        return;
      }
      message.error('Key更新失败');
    } finally {
      setKeyModalLoading(false);
    }
  };

  const handleKeyModalOk = () => {
    if (editingKey) {
      handleUpdateKey();
    } else {
      handleCreateKey();
    }
  };

  const handleDeleteKey = async () => {
    if (!selectedKeyId) return;
    try {
      const response = await LlmApi.deleteKey(selectedKeyId);
      if (response.code === 200) {
        message.success('Key删除成功');
        fetchKeys();
      } else {
        message.error(response.msg || 'Key删除失败');
      }
    } catch (error) {
      message.error('Key删除失败');
    }
  };

  const handleMarkHealthy = async (modelName) => {
    try {
      const response = await LlmApi.markModelHealthy(modelName);
      if (response.code === 200) {
        message.success(`模型 ${modelName} 已标记为健康`);
        fetchModels(selectedKeyId);
      } else {
        message.error(response.msg || '操作失败');
      }
    } catch (error) {
      message.error('操作失败');
    }
  };

  const handleSaveConfig = async (values) => {
    try {
      const response = await LlmApi.updateBalancerConfig(values);
      if (response.code === 200) {
        message.success('配置保存成功');
        fetchConfig();
      } else {
        message.error(response.msg || '保存失败');
      }
    } catch (error) {
      message.error('保存失败');
    }
  };

  const openAddModal = () => {
    if (!selectedKeyId) {
      message.warning('请先创建并选择一个Key');
      return;
    }
    setEditingModel(null);
    modelForm.resetFields();
    modelForm.setFieldsValue({
      provider: 'dashscope',
      modelType: 'TEXT',
      enabled: true,
      weight: 1,
      priority: 0,
    });
    setModalVisible(true);
  };

  const openEditModal = (record) => {
    setEditingModel(record);
    const capabilitiesArray = record.capabilities 
      ? record.capabilities.split(',').map(s => s.trim()).filter(Boolean)
      : [];
    modelForm.setFieldsValue({
      ...record,
      capabilities: capabilitiesArray,
      quotaResetDate: record.quotaResetDate ? dayjs(record.quotaResetDate) : null,
    });
    setModalVisible(true);
  };

  const handleModalOk = async () => {
    try {
      const values = await modelForm.validateFields();
      setModalLoading(true);

      const capabilitiesStr = Array.isArray(values.capabilities) 
        ? values.capabilities.join(',') 
        : (values.capabilities || '');
      const modelData = {
        ...values,
        capabilities: capabilitiesStr,
        quotaResetDate: values.quotaResetDate ? values.quotaResetDate.format('YYYY-MM-DD') : null,
      };

      let response;
      if (editingModel) {
        response = await LlmApi.updateModel(selectedKeyId, editingModel.id, modelData);
      } else {
        response = await LlmApi.createModel(selectedKeyId, modelData);
      }

      if (response.code === 200) {
        message.success(editingModel ? '模型更新成功' : '模型创建成功');
        setModalVisible(false);
        fetchModels();
      } else {
        message.error(response.message || '操作失败');
      }
    } catch (error) {
      if (error.errorFields) {
        return;
      }
      message.error('操作失败');
    } finally {
      setModalLoading(false);
    }
  };

  const handleDelete = async (id) => {
    try {
      const response = await LlmApi.deleteModel(selectedKeyId, id);
      if (response.code === 200) {
        message.success('模型删除成功');
        fetchModels(selectedKeyId);
      } else {
        message.error(response.message || '删除失败');
      }
    } catch (error) {
      message.error('删除失败');
    }
  };

  const handleToggleEnabled = async (id, enabled) => {
    try {
      const response = await LlmApi.toggleModelEnabled(selectedKeyId, id, enabled);
      if (response.code === 200) {
        message.success(enabled ? '模型已启用' : '模型已禁用');
        fetchModels(selectedKeyId);
      } else {
        message.error(response.message || '操作失败');
      }
    } catch (error) {
      message.error('操作失败');
    }
  };

  const formatCooldown = (millis) => {
    if (!millis || millis <= 0) return '-';
    const seconds = Math.ceil(millis / 1000);
    if (seconds < 60) return `${seconds}秒`;
    const minutes = Math.ceil(seconds / 60);
    return `${minutes}分钟`;
  };

  const formatTokens = (value) => {
    if (value === null || value === undefined || value === 0) {
      return '0';
    }
    if (value >= 1000000) {
      return `${(value / 1000000).toFixed(2)}M`;
    }
    if (value >= 1000) {
      return `${(value / 1000).toFixed(1)}K`;
    }
    return value.toString();
  };

  const formatDateTime = (time) => {
    if (!time) {
      return '-';
    }
    if (Array.isArray(time)) {
      const [y, m, d, hh = 0, mm = 0, ss = 0] = time;
      const dt = new Date(y, (m || 1) - 1, d || 1, hh, mm, ss);
      return Number.isNaN(dt.getTime()) ? '-' : dt.toLocaleString('zh-CN');
    }
    if (typeof time === 'string') {
      const normalized = time.includes('T') ? time : time.replace(' ', 'T');
      const dt = new Date(normalized);
      return Number.isNaN(dt.getTime()) ? '-' : dt.toLocaleString('zh-CN');
    }
    const dt = new Date(time);
    return Number.isNaN(dt.getTime()) ? '-' : dt.toLocaleString('zh-CN');
  };

  const renderTokenUsage = (record) => {
    const { inputTokens = 0, outputTokens = 0, totalTokens = 0 } = record;
    return (
      <Tooltip title={
        <div>
          <div>输入: {formatTokens(inputTokens)}</div>
          <div>输出: {formatTokens(outputTokens)}</div>
          <div>总计: {formatTokens(totalTokens)}</div>
        </div>
      }>
        <span style={{ cursor: 'pointer' }}>
          {formatTokens(totalTokens)}
          {totalTokens > 0 && <Tag color={centerPalette.primary} style={{ marginLeft: 4, fontSize: 10 }}>详情</Tag>}
        </span>
      </Tooltip>
    );
  };

  const modelTypeColorMap = { TEXT: 'green', IMAGE: 'lime', VOICE: 'gold', VIDEO: 'orange', MULTIMODAL: 'cyan' };
  const modelTypeLabelMap = { TEXT: '文本', IMAGE: '图片', VOICE: '语音', VIDEO: '视频', MULTIMODAL: '多模态' };

  const columns = [
    {
      title: '模型名称',
      dataIndex: 'name',
      key: 'name',
      width: 280,
      render: (text, record) => (
        <Space>
          <span>{text}</span>
          {record.displayName && <Tag color={centerPalette.primary}>{record.displayName}</Tag>}
        </Space>
      ),
    },
    {
      title: '模型类型',
      dataIndex: 'modelType',
      key: 'modelType',
      width: 110,
      filters: [
        { text: '文本', value: 'TEXT' },
        { text: '图片', value: 'IMAGE' },
        { text: '语音', value: 'VOICE' },
        { text: '视频', value: 'VIDEO' },
        { text: '多模态', value: 'MULTIMODAL' },
      ],
      onFilter: (value, record) => record.modelType === value,
      render: (type) => (
        <Tag color={modelTypeColorMap[type] || 'default'}>{modelTypeLabelMap[type] || type || '文本'}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'healthy',
      key: 'healthy',
      width: 100,
      render: (healthy, record) => (
        <Space>
          {healthy ? (
            <Tag icon={<CheckCircleOutlined />} color="success">健康</Tag>
          ) : (
            <Tooltip title={`冷却剩余: ${formatCooldown(record.remainingCooldownMillis)}`}>
              <Tag icon={<CloseCircleOutlined />} color="error">不健康</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled, record) => (
        <Switch 
          size="small" 
          checked={enabled} 
          onChange={(checked) => handleToggleEnabled(record.id, checked)}
        />
      ),
    },
    {
      title: '权重',
      dataIndex: 'weight',
      key: 'weight',
      width: 80,
    },
    {
      title: '调用次数',
      dataIndex: 'callCount',
      key: 'callCount',
      width: 100,
    },
    {
      title: '错误次数',
      dataIndex: 'errorCount',
      key: 'errorCount',
      width: 100,
      render: (count) => count > 0 ? <Tag color="red">{count}</Tag> : count,
    },
    {
      title: 'Token 用量',
      key: 'tokenUsage',
      width: 140,
      render: (_, record) => renderTokenUsage(record),
    },
    {
      title: '最后使用',
      dataIndex: 'lastUsedTime',
      key: 'lastUsedTime',
      width: 180,
      render: (time) => formatDateTime(time),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Button 
            type="link" 
            size="small"
            icon={<EditOutlined />}
            onClick={() => openEditModal(record)}
          >
            编辑
          </Button>
          {!record.healthy && (
            <Button 
              type="link" 
              size="small"
              onClick={() => handleMarkHealthy(record.name)}
            >
              解除熔断
            </Button>
          )}
          <Popconfirm
            title="确定要删除这个模型吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button 
              type="link" 
              size="small" 
              danger
              icon={<DeleteOutlined />}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.5 }}
    >
      <Title level={2} style={{ marginBottom: 24 }}>
        <ThunderboltOutlined style={{ marginRight: 8 }} />
        负载均衡管理
      </Title>

      <Card 
        title="策略配置" 
        extra={<SettingOutlined />}
        style={{ marginBottom: 24 }}
      >
        <Spin spinning={configLoading}>
          <Form
            form={form}
            layout="inline"
            onFinish={handleSaveConfig}
            initialValues={config}
          >
            <Form.Item name="enabled" label="启用负载均衡" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="strategy" label="选择策略">
              <Select style={{ width: 150 }}>
                <Select.Option value="round_robin">轮询</Select.Option>
                <Select.Option value="random">随机</Select.Option>
                <Select.Option value="weighted_rr">加权轮询</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="maxAttemptsPerRequest" label="最大重试次数">
              <InputNumber min={1} max={10} />
            </Form.Item>
            <Form.Item name="unhealthyCooldownSeconds" label="熔断冷却(秒)">
              <InputNumber min={60} max={3600} step={60} />
            </Form.Item>
            <Form.Item name="allowFallbackOnExplicitModel" label="显式模型可降级" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit">保存配置</Button>
            </Form.Item>
          </Form>
        </Spin>
      </Card>

      <Card
        title="模型列表"
        extra={
          <Space>
            <Select
              style={{ width: 240 }}
              placeholder="请选择 Key"
              value={selectedKeyId}
              onChange={(value) => {
                setSelectedKeyId(value);
                fetchModels(value);
              }}
              options={keys.map(k => {
                const providerLabel = (PROVIDER_OPTIONS.find(p => p.value === k.provider) || {}).label || k.provider;
                return { value: k.id, label: `${k.name} (${providerLabel})` };
              })}
            />
            <Button icon={<EditOutlined />} onClick={openEditKeyModal} disabled={!selectedKeyId}>编辑Key</Button>
            <Button onClick={openCreateKeyModal}>新增Key</Button>
            <Popconfirm
              title="确定删除当前Key吗？（需先删除其下所有模型）"
              onConfirm={handleDeleteKey}
              okText="确定"
              cancelText="取消"
            >
              <Button danger disabled={!selectedKeyId}>删除Key</Button>
            </Popconfirm>
            <Button type="primary" icon={<PlusOutlined />} onClick={openAddModal}>
              新增模型
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => fetchModels(selectedKeyId)} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      >
        <div className="table-scroll-top-wrapper" ref={tableWrapperRef}>
          <style>{`
            .table-scroll-top-wrapper {
              display: flex;
              flex-direction: column;
            }
            .table-scroll-top-wrapper .ant-table-wrapper {
              order: 2;
            }
            .table-scroll-top-wrapper .top-scrollbar {
              order: 1;
              overflow-x: auto;
              overflow-y: hidden;
              margin-bottom: 8px;
            }
            .table-scroll-top-wrapper .top-scrollbar-inner {
              height: 1px;
            }
          `}</style>
          <div className="top-scrollbar" ref={topScrollbarRef}>
            <div className="top-scrollbar-inner" ref={topScrollbarInnerRef} style={{ width: 1600 }}></div>
          </div>
          <Table
            columns={columns}
            dataSource={models}
            rowKey="id"
            loading={loading}
            scroll={{ x: 1600 }}
            pagination={false}
          />
        </div>
      </Card>

      <Modal
        title={editingKey ? '编辑 Key' : '新增 Key'}
        open={keyModalVisible}
        onOk={handleKeyModalOk}
        onCancel={() => { setKeyModalVisible(false); setEditingKey(null); }}
        confirmLoading={keyModalLoading}
        width={640}
        destroyOnHidden
      >
        <Form form={keyForm} layout="vertical" initialValues={{ provider: 'dashscope', enabled: true }}>
          <Form.Item name="name" label="Key 名称" rules={[{ required: true, message: '请输入Key名称' }]}> 
            <Input placeholder="如：team-dashscope" maxLength={128} />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="provider" label="Provider" rules={[{ required: true, message: '请选择Provider' }]}> 
                <Select
                  onChange={(val) => {
                    const cfg = getProviderConfig(val);
                    if (cfg.defaultBaseUrl) {
                      keyForm.setFieldValue('baseUrl', cfg.defaultBaseUrl);
                    } else {
                      keyForm.setFieldValue('baseUrl', '');
                    }
                    if (!cfg.needsApiVersion) {
                      keyForm.setFieldValue('apiVersion', '');
                    }
                  }}
                >
                  {PROVIDER_OPTIONS.map(opt => (
                    <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item 
            name="apiKey" 
            label={editingKey ? 'API Key (留空则保持不变)' : 'API Key'}
            rules={editingKey ? [] : [{ required: true, message: '请输入API Key' }]}
          > 
            <Input.Password placeholder="请输入Provider对应API Key" />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label="API 端点 (Base URL)"
            tooltip="留空则使用所选 Provider 的默认端点"
          >
            <Input placeholder={(() => {
              const p = keyForm.getFieldValue('provider');
              const cfg = getProviderConfig(p);
              return cfg.defaultBaseUrl || '请输入自定义 API 端点 URL';
            })()}
            />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.provider !== cur.provider}
          >
            {({ getFieldValue }) => {
              const provider = getFieldValue('provider');
              const cfg = getProviderConfig(provider);
              return cfg.needsApiVersion ? (
                <Form.Item name="apiVersion" label="API 版本" tooltip="Azure OpenAI 专用">
                  <Input placeholder="如：2024-02-01" />
                </Form.Item>
              ) : null;
            }}
          </Form.Item>
          <Form.Item
            name="extraHeaders"
            label="自定义请求头"
            tooltip="JSON 格式的额外请求头，用于企业网关等场景"
          >
            <TextArea rows={2} placeholder='{"X-Custom-Header": "value"}' />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <TextArea rows={2} placeholder="可选描述" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editingModel ? '编辑模型' : '新增模型'}
        open={modalVisible}
        onOk={handleModalOk}
        onCancel={() => setModalVisible(false)}
        confirmLoading={modalLoading}
        width={600}
        destroyOnHidden
      >
        <Form
          form={modelForm}
          layout="vertical"
          initialValues={{
            provider: 'dashscope',
            modelType: 'TEXT',
            enabled: true,
            weight: 1,
            priority: 0,
          }}
        >
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="模型名称"
                rules={[{ required: true, message: '请输入模型名称' }]}
              >
                <Input placeholder="如: qwen-max" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="displayName"
                label="显示名称"
              >
                <Input placeholder="如: 通义千问Max" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="provider"
                label="提供商"
              >
                <Select>
                  {PROVIDER_OPTIONS.map(opt => (
                    <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="modelType"
                label="模型类型"
                rules={[{ required: true, message: '请选择模型类型' }]}
              >
                <Select>
                  <Select.Option value="TEXT">文本</Select.Option>
                  <Select.Option value="IMAGE">图片</Select.Option>
                  <Select.Option value="VOICE">语音</Select.Option>
                  <Select.Option value="VIDEO">视频</Select.Option>
                  <Select.Option value="MULTIMODAL">多模态</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="capabilities"
                label="模型能力"
              >
                <Select mode="tags" placeholder="如: chat, vision, code">
                  <Select.Option value="chat">chat</Select.Option>
                  <Select.Option value="vision">vision</Select.Option>
                  <Select.Option value="code">code</Select.Option>
                  <Select.Option value="ocr">ocr</Select.Option>
                  <Select.Option value="tool">tool</Select.Option>
                </Select>
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="weight"
                label="权重"
              >
                <InputNumber min={1} max={100} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="priority"
                label="优先级"
              >
                <InputNumber min={0} max={100} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="enabled"
                label="启用"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="quotaLimit"
                label="配额限制 (Token)"
              >
                <InputNumber min={0} style={{ width: '100%' }} placeholder="1000000" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="quotaResetDate"
                label="配额重置日期"
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="description"
            label="描述"
          >
            <TextArea rows={2} placeholder="模型描述信息" />
          </Form.Item>
        </Form>
      </Modal>
    </motion.div>
  );
};

export default LlmAdmin;
