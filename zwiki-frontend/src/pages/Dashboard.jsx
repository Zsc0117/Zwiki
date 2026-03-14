import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { App, Card, Row, Col, Statistic, Table, Tag, Spin, Empty, Progress, Segmented, Typography } from 'antd';
import {
  FileTextOutlined,
  RobotOutlined,
  MessageOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  WarningOutlined,
  PieChartOutlined,
  LineChartOutlined,
  KeyOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import { Pie, Line } from '@ant-design/charts';
import dayjs from 'dayjs';
import api from '../api/http';
import { LlmApi } from '../api/llm';
import { getCenterPalette } from '../theme/centerTheme';

const { Title, Text } = Typography;

const PROVIDER_LABEL_MAP = {
  dashscope: '阿里百炼 (DashScope)',
  openai: 'OpenAI',
  azure: 'Azure OpenAI',
  minimax: 'MiniMax',
  deepseek: 'DeepSeek',
  moonshot: 'Moonshot/Kimi',
  zhipu: '智谱 GLM',
  custom: '自定义',
};

const MODEL_TYPE_COLOR_MAP = {
  TEXT: 'green',
  IMAGE: 'lime',
  VOICE: 'gold',
  VIDEO: 'orange',
  MULTIMODAL: 'cyan',
};

const MODEL_TYPE_LABEL_MAP = {
  TEXT: '文本',
  IMAGE: '图片',
  VOICE: '语音',
  VIDEO: '视频',
  MULTIMODAL: '多模态',
};

const formatTokens = (num) => {
  if (!num || num === 0) return '0';
  if (num >= 1_000_000) return `${(num / 1_000_000).toFixed(1)}M`;
  if (num >= 1_000) return `${(num / 1_000).toFixed(1)}K`;
  return String(num);
};

const formatDateTime = (time) => {
  if (!time) return '-';
  if (Array.isArray(time)) {
    const [y, m, d, hh = 0, mm = 0, ss = 0] = time;
    const dt = new Date(y, (m || 1) - 1, d || 1, hh, mm, ss);
    return Number.isNaN(dt.getTime()) ? '-' : dt.toLocaleString('zh-CN');
  }
  const dt = new Date(time);
  return Number.isNaN(dt.getTime()) ? '-' : dt.toLocaleString('zh-CN');
};

const Dashboard = ({ darkMode }) => {
  const { message } = App.useApp();
  const palette = getCenterPalette(darkMode);
  const [loading, setLoading] = useState(true);
  const [trendLoading, setTrendLoading] = useState(false);
  const [stats, setStats] = useState(null);
  const [aggregatedStats, setAggregatedStats] = useState(null);
  const [providerStats, setProviderStats] = useState([]);
  const [keyStats, setKeyStats] = useState([]);
  const [trendData, setTrendData] = useState([]);
  const [trendDimension, setTrendDimension] = useState('MODEL');

  const fetchBaseStats = useCallback(async () => {
    setLoading(true);
    const results = await Promise.allSettled([
      api.get('/stats/overview'),
      LlmApi.getAggregatedStats(),
      LlmApi.getProviderStats(),
      LlmApi.getKeyStats(),
    ]);

    const [overviewResult, aggregatedResult, providerResult, keyResult] = results;
    let successCount = 0;

    if (overviewResult.status === 'fulfilled' && overviewResult.value?.code === 200) {
      setStats(overviewResult.value.data);
      successCount += 1;
    }

    if (aggregatedResult.status === 'fulfilled' && aggregatedResult.value?.code === 200) {
      setAggregatedStats(aggregatedResult.value.data || null);
      successCount += 1;
    }

    if (providerResult.status === 'fulfilled' && providerResult.value?.code === 200) {
      setProviderStats(providerResult.value.data || []);
      successCount += 1;
    }

    if (keyResult.status === 'fulfilled' && keyResult.value?.code === 200) {
      setKeyStats(keyResult.value.data || []);
      successCount += 1;
    }

    if (successCount === 0) {
      message.error('获取统计数据失败');
    }

    setLoading(false);
  }, [message]);

  const fetchTrendData = useCallback(async (dimension) => {
    setTrendLoading(true);
    try {
      const endDate = dayjs().format('YYYY-MM-DD');
      const startDate = dayjs().subtract(30, 'day').format('YYYY-MM-DD');
      const response = await LlmApi.getUsageTrend({
        dimensionType: dimension,
        dimensionId: 'ALL',
        startDate,
        endDate,
      });
      if (response.code === 200 && response.data?.data) {
        setTrendData(response.data.data.map((item) => ({
          date: item.date,
          totalTokens: item.totalTokens || 0,
          callCount: item.callCount || 0,
          errorCount: item.errorCount || 0,
        })));
      } else {
        setTrendData([]);
      }
    } catch (error) {
      console.error('获取趋势数据失败:', error);
      setTrendData([]);
      message.error('获取趋势数据失败');
    } finally {
      setTrendLoading(false);
    }
  }, [message]);

  useEffect(() => {
    fetchBaseStats();
  }, [fetchBaseStats]);

  useEffect(() => {
    fetchTrendData(trendDimension);
  }, [fetchTrendData, trendDimension]);

  const modelStats = useMemo(() => stats?.modelStats || [], [stats]);

  const totalCalls = aggregatedStats?.totalCalls ?? modelStats.reduce((sum, item) => sum + (item.callCount || 0), 0);
  const totalErrors = aggregatedStats?.totalErrors ?? modelStats.reduce((sum, item) => sum + (item.errorCount || 0), 0);
  const errorRate = totalCalls > 0 ? ((totalErrors / totalCalls) * 100).toFixed(1) : '0.0';

  const topTokenModels = useMemo(() => {
    return [...modelStats]
      .sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0))
      .slice(0, 5);
  }, [modelStats]);

  const providerChartData = useMemo(() => {
    return providerStats
      .filter((item) => (item.totalTokens || 0) > 0)
      .map((item) => ({
        type: PROVIDER_LABEL_MAP[item.provider] || item.provider || '-',
        value: item.totalTokens || 0,
      }));
  }, [providerStats]);

  const modelColumns = [
    {
      title: '模型名称',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      width: 220,
    },
    {
      title: '供应商',
      dataIndex: 'provider',
      key: 'provider',
      width: 140,
      render: (value) => <Tag>{PROVIDER_LABEL_MAP[value] || value || '-'}</Tag>,
    },
    {
      title: '类型',
      dataIndex: 'modelType',
      key: 'modelType',
      width: 100,
      render: (value) => <Tag color={MODEL_TYPE_COLOR_MAP[value] || 'default'}>{MODEL_TYPE_LABEL_MAP[value] || value || '文本'}</Tag>,
    },
    {
      title: '调用次数',
      dataIndex: 'callCount',
      key: 'callCount',
      width: 110,
      sorter: (a, b) => (a.callCount || 0) - (b.callCount || 0),
      render: (value) => <span style={{ fontWeight: 600 }}>{value || 0}</span>,
    },
    {
      title: 'Token 消耗',
      dataIndex: 'totalTokens',
      key: 'totalTokens',
      width: 120,
      sorter: (a, b) => (a.totalTokens || 0) - (b.totalTokens || 0),
      render: (value) => formatTokens(value),
    },
    {
      title: '输入 / 输出',
      key: 'io',
      width: 160,
      render: (_, row) => (
        <span style={{ fontSize: 12, color: palette.textMuted }}>
          {formatTokens(row.inputTokens)} / {formatTokens(row.outputTokens)}
        </span>
      ),
    },
    {
      title: '错误数',
      dataIndex: 'errorCount',
      key: 'errorCount',
      width: 90,
      render: (value) => value > 0 ? <Tag color="red">{value}</Tag> : <span style={{ color: palette.textMuted }}>0</span>,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (value) => value ? <Tag color="green">启用</Tag> : <Tag color="default">禁用</Tag>,
    },
  ];

  const keyColumns = [
    {
      title: 'Key 名称',
      dataIndex: 'name',
      key: 'name',
      width: 220,
      render: (value, row) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span style={{ fontWeight: 600 }}>{value || '-'}</span>
          <Text type="secondary" style={{ fontSize: 12 }}>{PROVIDER_LABEL_MAP[row.provider] || row.provider || '-'}</Text>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      render: (value) => value ? <Tag color="green">启用</Tag> : <Tag color="default">禁用</Tag>,
    },
    {
      title: '调用次数',
      dataIndex: 'callCount',
      key: 'callCount',
      width: 110,
      sorter: (a, b) => (a.callCount || 0) - (b.callCount || 0),
      render: (value) => value || 0,
    },
    {
      title: '错误次数',
      dataIndex: 'errorCount',
      key: 'errorCount',
      width: 110,
      render: (value) => value > 0 ? <Tag color="red">{value}</Tag> : 0,
    },
    {
      title: 'Token 总量',
      dataIndex: 'totalTokens',
      key: 'totalTokens',
      width: 120,
      render: (value) => formatTokens(value),
    },
    {
      title: '输入 / 输出',
      key: 'tokenIo',
      width: 160,
      render: (_, row) => (
        <span style={{ fontSize: 12, color: palette.textMuted }}>
          {formatTokens(row.inputTokens)} / {formatTokens(row.outputTokens)}
        </span>
      ),
    },
    {
      title: '最近使用',
      dataIndex: 'lastUsedTime',
      key: 'lastUsedTime',
      width: 180,
      render: (value) => formatDateTime(value),
    },
  ];

  const cardStyle = {
    borderRadius: 16,
    boxShadow: `0 8px 24px ${palette.shadow}`,
    height: '100%',
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 24, color: palette.textMuted }}>加载统计数据...</div>
        </Spin>
      </div>
    );
  }

  if (!stats && !aggregatedStats && providerStats.length === 0 && keyStats.length === 0) {
    return <Empty description="暂无统计数据" />;
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
    >
      <Title level={2} style={{ marginBottom: 24, color: palette.heading }}>
        数据统计
      </Title>

      {stats && (
        <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
          <Col xs={24} sm={12} md={6}>
            <Card style={cardStyle}>
              <Statistic
                title="总任务数"
                value={stats.totalTasks || 0}
                prefix={<FileTextOutlined style={{ color: palette.primary }} />}
                valueStyle={{ color: palette.primary }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card style={cardStyle}>
              <Statistic
                title="LLM 模型数"
                value={stats.totalModels || 0}
                prefix={<RobotOutlined style={{ color: '#10b981' }} />}
                valueStyle={{ color: '#10b981' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card style={cardStyle}>
              <Statistic
                title="总 Token 消耗"
                value={formatTokens(stats.totalTokensUsed)}
                prefix={<ThunderboltOutlined style={{ color: '#f59e0b' }} />}
                valueStyle={{ color: '#f59e0b' }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card style={cardStyle}>
              <Statistic
                title="聊天消息数"
                value={stats.totalChatMessages || 0}
                prefix={<MessageOutlined style={{ color: palette.accent }} />}
                valueStyle={{ color: palette.accent }}
              />
            </Card>
          </Col>
        </Row>
      )}

      {aggregatedStats && (
        <Card style={{ ...cardStyle, marginBottom: 24 }} title="负载均衡全局统计">
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} md={6}>
              <Statistic
                title="健康模型数"
                value={aggregatedStats.healthyModels || 0}
                suffix={`/ ${aggregatedStats.enabledModels || 0}`}
                prefix={<CheckCircleOutlined style={{ color: palette.success }} />}
              />
              <div style={{ fontSize: 12, color: palette.textMuted, marginTop: 8 }}>
                {aggregatedStats.typeStats && Object.entries(aggregatedStats.typeStats).map(([type, group]) => (
                  <Tag key={type} color={MODEL_TYPE_COLOR_MAP[type] || 'default'} style={{ marginTop: 4 }}>
                    {MODEL_TYPE_LABEL_MAP[type] || type}: {group[1]}/{group[0]}
                  </Tag>
                ))}
              </div>
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Statistic title="总调用次数" value={aggregatedStats.totalCalls || 0} prefix={<ApiOutlined style={{ color: palette.primary }} />} />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Statistic
                title="总错误次数"
                value={aggregatedStats.totalErrors || 0}
                prefix={<WarningOutlined style={{ color: palette.danger }} />}
                valueStyle={{ color: (aggregatedStats.totalErrors || 0) > 0 ? palette.danger : undefined }}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <Statistic title="Token 用量总计" value={formatTokens(aggregatedStats.totalTokens)} prefix={<ThunderboltOutlined style={{ color: '#f59e0b' }} />} />
              <div style={{ fontSize: 12, color: palette.textMuted, marginTop: 8 }}>
                输入 {formatTokens(aggregatedStats.totalInputTokens)} / 输出 {formatTokens(aggregatedStats.totalOutputTokens)}
              </div>
            </Col>
          </Row>
        </Card>
      )}

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={12} md={8}>
          <Card style={cardStyle} title="LLM 调用总览">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <Statistic title="总调用次数" value={totalCalls} prefix={<ApiOutlined />} />
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span style={{ fontSize: 13, color: palette.textMuted }}>错误率</span>
                  <span style={{ fontSize: 13, fontWeight: 600 }}>{errorRate}%</span>
                </div>
                <Progress
                  percent={Number(errorRate)}
                  strokeColor={Number(errorRate) > 5 ? palette.danger : palette.success}
                  showInfo={false}
                  size="small"
                />
              </div>
              {totalErrors > 0 && (
                <Statistic
                  title="总错误数"
                  value={totalErrors}
                  prefix={<WarningOutlined />}
                  valueStyle={{ color: palette.danger }}
                />
              )}
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} md={16}>
          <Card style={cardStyle} title="Token 消耗 Top 5">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {topTokenModels.length > 0 ? topTokenModels.map((item, index) => {
                const maxTokens = topTokenModels[0]?.totalTokens || 1;
                const pct = maxTokens > 0 ? ((item.totalTokens || 0) / maxTokens) * 100 : 0;
                return (
                  <div key={`${item.provider || 'provider'}-${item.name || 'model'}-${index}`}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
                      <span style={{ fontSize: 13, fontWeight: 500 }}>{item.name}</span>
                      <span style={{ fontSize: 12, color: palette.textMuted }}>{formatTokens(item.totalTokens)}</span>
                    </div>
                    <Progress
                      percent={pct}
                      showInfo={false}
                      size="small"
                      strokeColor={['#20b486', '#32c798', '#57d4a7', '#87e3c4', '#c5f3e3'][index] || palette.primary}
                    />
                  </div>
                );
              }) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无模型消耗数据" />}
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} lg={8}>
          <Card style={cardStyle} title={<><PieChartOutlined style={{ marginRight: 8 }} />Provider 用量分布</>}>
            {providerChartData.length > 0 ? (
              <Pie
                data={providerChartData}
                angleField="value"
                colorField="type"
                radius={0.8}
                innerRadius={0.5}
                height={240}
                label={{ text: 'type', position: 'outside' }}
                legend={{ position: 'bottom' }}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Provider 数据" />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={16}>
          <Card
            style={cardStyle}
            title={<><LineChartOutlined style={{ marginRight: 8 }} />近 30 天用量趋势</>}
            extra={
              <Segmented
                size="small"
                options={[
                  { label: '按模型', value: 'MODEL' },
                  { label: '按 Key', value: 'KEY' },
                  { label: '按 Provider', value: 'PROVIDER' },
                ]}
                value={trendDimension}
                onChange={(value) => setTrendDimension(value)}
              />
            }
          >
            <Spin spinning={trendLoading}>
              {trendData.length > 0 ? (
                <Line
                  data={trendData}
                  xField="date"
                  yField="totalTokens"
                  height={240}
                  point={{ size: 3 }}
                  tooltip={{
                    formatter: (datum) => ({
                      name: 'Token 用量',
                      value: formatTokens(datum.totalTokens),
                    }),
                  }}
                  xAxis={{
                    label: {
                      formatter: (value) => dayjs(value).format('MM-DD'),
                    },
                  }}
                  yAxis={{
                    label: {
                      formatter: (value) => formatTokens(value),
                    },
                  }}
                />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无趋势数据" />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>

      <Card style={{ ...cardStyle, marginBottom: 24 }} title={<><KeyOutlined style={{ marginRight: 8 }} />Key 维度统计</>}>
        <Table
          dataSource={keyStats}
          columns={keyColumns}
          rowKey={(row) => row.id || row.name}
          pagination={false}
          size="small"
          scroll={{ x: 980 }}
          locale={{ emptyText: '暂无 Key 统计数据' }}
        />
      </Card>

      <Card style={cardStyle} title="模型详细数据">
        <Table
          dataSource={modelStats}
          columns={modelColumns}
          rowKey={(row, index) => `${row.provider || 'provider'}-${row.name || 'model'}-${index}`}
          pagination={false}
          size="small"
          scroll={{ x: 980 }}
          locale={{ emptyText: '暂无模型统计数据' }}
        />
      </Card>
    </motion.div>
  );
};

export default Dashboard;
