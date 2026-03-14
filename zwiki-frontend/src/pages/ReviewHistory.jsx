import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, Card, Input, Modal, Empty, Typography, Button } from 'antd';
import {
  SearchOutlined,
  EyeOutlined,
  BugOutlined,
  WarningOutlined,
  InfoCircleOutlined
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import api from '../api/http';
import { getCenterPalette } from '../theme/centerTheme';

const { Text } = Typography;

const ReviewHistory = ({ darkMode }) => {
  const palette = getCenterPalette(darkMode);
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [repoFilter, setRepoFilter] = useState('');
  const [detailVisible, setDetailVisible] = useState(false);
  const [selectedReview, setSelectedReview] = useState(null);

  const fetchData = useCallback(() => {
    setLoading(true);
    const params = { page, size: pageSize };
    if (repoFilter) params.repo = repoFilter;

    api.get('/review/history', { params })
      .then(res => {
        if (res?.code === 200 && res.data) {
          setData(res.data.content || []);
          setTotal(res.data.totalElements || 0);
        }
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [page, pageSize, repoFilter]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const showDetail = (record) => {
    setSelectedReview(record);
    setDetailVisible(true);
  };

  const columns = [
    {
      title: '仓库',
      dataIndex: 'repoFullName',
      key: 'repoFullName',
      width: 200,
      ellipsis: true,
      render: (v) => <Text strong>{v}</Text>
    },
    {
      title: 'PR',
      dataIndex: 'prNumber',
      key: 'prNumber',
      width: 70,
      render: (v) => <Tag>#{v}</Tag>
    },
    {
      title: '评级',
      dataIndex: 'overallRating',
      key: 'overallRating',
      width: 100,
      render: (v) => {
        if (!v) return '-';
        const color = v.includes('A') ? 'green' : v.includes('B') ? 'cyan' : v.includes('C') ? 'orange' : 'red';
        return <Tag color={color}>{v}</Tag>;
      }
    },
    {
      title: '评论',
      dataIndex: 'commentCount',
      key: 'commentCount',
      width: 70,
      render: (v) => v || 0
    },
    {
      title: '问题分布',
      key: 'issues',
      width: 200,
      render: (_, row) => (
        <div style={{ display: 'flex', gap: 8 }}>
          {(row.errorCount || 0) > 0 && (
            <Tag color="red" icon={<BugOutlined />}>{row.errorCount} 错误</Tag>
          )}
          {(row.warningCount || 0) > 0 && (
            <Tag color="orange" icon={<WarningOutlined />}>{row.warningCount} 警告</Tag>
          )}
          {(row.infoCount || 0) > 0 && (
            <Tag color={palette.primary} icon={<InfoCircleOutlined />}>{row.infoCount} 建议</Tag>
          )}
          {!(row.errorCount || row.warningCount || row.infoCount) && (
            <Text type="secondary">-</Text>
          )}
        </div>
      )
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v) => v || '-'
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => showDetail(record)}>
          详情
        </Button>
      )
    }
  ];

  const parseSummary = (review) => {
    if (!review) return '';
    if (review.summary) return review.summary;
    if (review.reviewDetail) {
      try {
        const detail = typeof review.reviewDetail === 'string' ? JSON.parse(review.reviewDetail) : review.reviewDetail;
        return detail.summary || '';
      } catch { return ''; }
    }
    return '';
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>审查历史</h2>
        <Input.Search
          placeholder="按仓库名筛选"
          allowClear
          onSearch={(v) => { setRepoFilter(v); setPage(0); }}
          style={{ width: 280 }}
          prefix={<SearchOutlined />}
        />
      </div>

      <Card style={{ borderRadius: 12, boxShadow: `0 8px 24px ${palette.shadow}` }}>
        <Table
          dataSource={data}
          columns={columns}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条记录`,
            onChange: (p, s) => { setPage(p - 1); setPageSize(s); }
          }}
          size="middle"
          scroll={{ x: 900 }}
          locale={{ emptyText: <Empty description="暂无审查记录" /> }}
        />
      </Card>

      <Modal
        title={selectedReview ? `${selectedReview.repoFullName} #${selectedReview.prNumber}` : '审查详情'}
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={720}
        styles={{ body: { maxHeight: '60vh', overflowY: 'auto' } }}
      >
        {selectedReview && (
          <div>
            <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
              {selectedReview.overallRating && (
                <Tag color={palette.primary} style={{ fontSize: 14, padding: '4px 12px' }}>
                  评级: {selectedReview.overallRating}
                </Tag>
              )}
              <Tag>{selectedReview.commentCount || 0} 条评论</Tag>
              {(selectedReview.errorCount || 0) > 0 && <Tag color="red">{selectedReview.errorCount} 错误</Tag>}
              {(selectedReview.warningCount || 0) > 0 && <Tag color="orange">{selectedReview.warningCount} 警告</Tag>}
            </div>

            <Card size="small" title="审查摘要" style={{ marginBottom: 16 }}>
              <div className="markdown-content">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {parseSummary(selectedReview)}
                </ReactMarkdown>
              </div>
            </Card>

            <Text type="secondary" style={{ fontSize: 12 }}>
              审查时间: {selectedReview.createdAt || '-'}
            </Text>
          </div>
        )}
      </Modal>
    </motion.div>
  );
};

export default ReviewHistory;
