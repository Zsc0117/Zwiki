import React, { useEffect, useState } from 'react';
import {
  Modal, Card, Timeline, Tag, Button, Spin, Empty,
  Typography, Collapse, Statistic, Row, Col, Badge
} from 'antd';
import {
  FileTextOutlined, PlusOutlined, EditOutlined,
  DeleteOutlined, HistoryOutlined, DiffOutlined,
  ClockCircleOutlined, BarChartOutlined
} from '@ant-design/icons';
import { DocChangeApi } from '../api/docChange';
import './DocChangeModal.css';

const { Text, Title } = Typography;
const { Panel } = Collapse;

const CHANGE_TYPE_CONFIG = {
  ADD: { color: 'green', icon: <PlusOutlined />, label: '新增' },
  MODIFY: { color: 'blue', icon: <EditOutlined />, label: '修改' },
  DELETE: { color: 'red', icon: <DeleteOutlined />, label: '删除' }
};

const TRIGGER_SOURCE_CONFIG = {
  WEBHOOK: { color: 'purple', label: 'Webhook推送' },
  MANUAL: { color: 'orange', label: '手动触发' },
  SYSTEM: { color: 'default', label: '系统自动' }
};

/**
 * 简单的 Side-by-Side Diff 查看器（无外部依赖）
 */
const SimpleDiffViewer = ({ oldValue, newValue }) => {
  const oldLines = (oldValue || '').split('\n');
  const newLines = (newValue || '').split('\n');
  const maxLen = Math.max(oldLines.length, newLines.length);

  const lineStyle = (type) => ({
    padding: '2px 8px',
    fontFamily: 'monospace',
    fontSize: 13,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    lineHeight: '1.6',
    background: type === 'add' ? '#e6ffed' : type === 'del' ? '#ffeef0' : 'transparent',
    borderLeft: type === 'add' ? '3px solid #28a745' : type === 'del' ? '3px solid #d73a49' : '3px solid transparent',
    minHeight: 24,
  });

  const rows = [];
  for (let i = 0; i < maxLen; i++) {
    const o = i < oldLines.length ? oldLines[i] : undefined;
    const n = i < newLines.length ? newLines[i] : undefined;
    const changed = o !== n;
    rows.push(
      <tr key={i}>
        <td style={{ color: '#999', textAlign: 'right', padding: '2px 6px', userSelect: 'none', fontSize: 12, width: 36 }}>{o !== undefined ? i + 1 : ''}</td>
        <td style={{ ...lineStyle(changed && o !== undefined ? 'del' : ''), width: '50%' }}>{o ?? ''}</td>
        <td style={{ color: '#999', textAlign: 'right', padding: '2px 6px', userSelect: 'none', fontSize: 12, width: 36 }}>{n !== undefined ? i + 1 : ''}</td>
        <td style={{ ...lineStyle(changed && n !== undefined ? 'add' : ''), width: '50%' }}>{n ?? ''}</td>
      </tr>
    );
  }

  return (
    <div style={{ border: '1px solid #e8e8e8', borderRadius: 6, overflow: 'auto', maxHeight: 500 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
        <thead>
          <tr style={{ background: '#fafafa', borderBottom: '1px solid #e8e8e8' }}>
            <th colSpan={2} style={{ padding: '6px 8px', textAlign: 'left', fontSize: 13, color: '#666' }}>变更前</th>
            <th colSpan={2} style={{ padding: '6px 8px', textAlign: 'left', fontSize: 13, color: '#666' }}>变更后</th>
          </tr>
        </thead>
        <tbody>{rows}</tbody>
      </table>
    </div>
  );
};

/**
 * 文档变更历史弹窗组件
 * 展示智能文档对比与变更追踪
 */
const DocChangeModal = ({ open, onClose, taskId, taskName }) => {
  const [loading, setLoading] = useState(true);
  const [changes, setChanges] = useState([]);
  const [stats, setStats] = useState(null);
  const [selectedChange, setSelectedChange] = useState(null);
  const [detailModalOpen, setDetailModalOpen] = useState(false);

  useEffect(() => {
    if (open && taskId) {
      loadChanges();
      loadStats();
    }
  }, [open, taskId]);

  const loadChanges = async () => {
    setLoading(true);
    try {
      const res = await DocChangeApi.getChanges(taskId, 0, 50);
      if (res?.code === 200) {
        setChanges(res.data?.content || []);
      }
    } catch (e) {
      console.error('加载变更历史失败', e);
    } finally {
      setLoading(false);
    }
  };

  const loadStats = async () => {
    try {
      const res = await DocChangeApi.getChangeStats(taskId);
      if (res?.code === 200) {
        setStats(res.data);
      }
    } catch (e) {
      console.error('加载变更统计失败', e);
    }
  };

  const handleManualCompare = async () => {
    try {
      setLoading(true);
      const res = await DocChangeApi.manualCompare(taskId);
      if (res?.code === 200) {
        // 重新加载变更列表
        await loadChanges();
        await loadStats();
      }
    } catch (e) {
      console.error('手动对比失败', e);
    } finally {
      setLoading(false);
    }
  };

  const handleViewDetail = (change) => {
    setSelectedChange(change);
    setDetailModalOpen(true);
  };

  const formatDate = (dateStr) => {
    if (!dateStr) return '-';
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  // 渲染统计卡片
  const renderStats = () => {
    if (!stats) return null;
    return (
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="总变更数"
              value={stats.totalChanges || 0}
              prefix={<HistoryOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="新增章节"
              value={stats.addCount || 0}
              valueStyle={{ color: '#3f8600' }}
              prefix={<PlusOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="修改章节"
              value={stats.modifyCount || 0}
              valueStyle={{ color: '#1890ff' }}
              prefix={<EditOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="删除章节"
              value={stats.deleteCount || 0}
              valueStyle={{ color: '#cf1322' }}
              prefix={<DeleteOutlined />}
            />
          </Card>
        </Col>
      </Row>
    );
  };

  // 渲染时间线
  const renderTimeline = () => {
    if (changes.length === 0) {
      return (
        <Empty
          description="暂无变更记录"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      );
    }

    // 按日期分组
    const grouped = changes.reduce((acc, change) => {
      const date = change.createdAt?.split('T')[0] || '未知日期';
      if (!acc[date]) acc[date] = [];
      acc[date].push(change);
      return acc;
    }, {});

    return (
      <Timeline mode="left">
        {Object.entries(grouped).map(([date, dayChanges]) => (
          <Timeline.Item
            key={date}
            dot={<ClockCircleOutlined style={{ fontSize: '16px' }} />}
            label={
              <Text strong style={{ fontSize: 16 }}>
                {date}
              </Text>
            }
          >
            <Collapse ghost>
              {dayChanges.map((change) => {
                const config = CHANGE_TYPE_CONFIG[change.changeType] || CHANGE_TYPE_CONFIG.MODIFY;
                const trigger = TRIGGER_SOURCE_CONFIG[change.triggerSource] || TRIGGER_SOURCE_CONFIG.SYSTEM;

                return (
                  <Panel
                    key={change.id}
                    header={
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        <Tag color={config.color} icon={config.icon}>
                          {config.label}
                        </Tag>
                        <Text strong>
                          {change.catalogueId ? `章节: ${change.catalogueId}` : '整篇文档'}
                        </Text>
                        <Tag color={trigger.color} size="small">
                          {trigger.label}
                        </Tag>
                        <Text type="secondary" style={{ marginLeft: 'auto' }}>
                          {formatDate(change.createdAt)}
                        </Text>
                      </div>
                    }
                  >
                    <Card size="small" style={{ background: '#f5f5f5' }}>
                      <Row gutter={16}>
                        <Col span={12}>
                          <Text type="secondary">变更前摘要:</Text>
                          <div className="content-summary">
                            {change.beforeContentSummary || '(无内容)'}
                          </div>
                        </Col>
                        <Col span={12}>
                          <Text type="secondary">变更后摘要:</Text>
                          <div className="content-summary">
                            {change.afterContentSummary || '(无内容)'}
                          </div>
                        </Col>
                      </Row>
                      <Button
                        type="primary"
                        size="small"
                        icon={<DiffOutlined />}
                        onClick={() => handleViewDetail(change)}
                        style={{ marginTop: 12 }}
                      >
                        查看完整 Diff
                      </Button>
                    </Card>
                  </Panel>
                );
              })}
            </Collapse>
          </Timeline.Item>
        ))}
      </Timeline>
    );
  };

  return (
    <>
      <Modal
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <HistoryOutlined />
            <span>文档变更历史 - {taskName}</span>
            <Badge count={stats?.totalChanges || 0} style={{ backgroundColor: '#1890ff' }} />
          </div>
        }
        open={open}
        onCancel={onClose}
        width={900}
        footer={[
          <Button key="manual" onClick={handleManualCompare} loading={loading}>
            <DiffOutlined /> 手动对比当前文档
          </Button>,
          <Button key="refresh" onClick={loadChanges} loading={loading}>
            刷新
          </Button>,
          <Button key="close" type="primary" onClick={onClose}>
            关闭
          </Button>
        ]}
      >
        <Spin spinning={loading}>
          {renderStats()}
          <Card
            title={
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <FileTextOutlined />
                <span>变更时间线</span>
              </div>
            }
          >
            {renderTimeline()}
          </Card>
        </Spin>
      </Modal>

      {/* 详细 Diff 弹窗 */}
      <Modal
        title="详细变更对比"
        open={detailModalOpen}
        onCancel={() => setDetailModalOpen(false)}
        width={1200}
        footer={[
          <Button key="close" onClick={() => setDetailModalOpen(false)}>
            关闭
          </Button>
        ]}
      >
        {selectedChange && (
          <>
            <div style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col>
                  <Tag color={CHANGE_TYPE_CONFIG[selectedChange.changeType]?.color}>
                    {CHANGE_TYPE_CONFIG[selectedChange.changeType]?.label}
                  </Tag>
                </Col>
                <Col>
                  <Text type="secondary">章节: {selectedChange.catalogueId || '整篇文档'}</Text>
                </Col>
                <Col>
                  <Text type="secondary">时间: {formatDate(selectedChange.createdAt)}</Text>
                </Col>
              </Row>
            </div>
            <SimpleDiffViewer
              oldValue={selectedChange.beforeContentSummary || ''}
              newValue={selectedChange.afterContentSummary || ''}
            />
            {selectedChange.diffContent && (
              <Card
                title="统一 Diff 格式"
                size="small"
                style={{ marginTop: 16 }}
              >
                <pre style={{ fontSize: 12, overflow: 'auto', maxHeight: 300 }}>
                  {selectedChange.diffContent}
                </pre>
              </Card>
            )}
          </>
        )}
      </Modal>
    </>
  );
};

export default DocChangeModal;
