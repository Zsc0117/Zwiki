import React, { useState, useEffect, useCallback } from 'react';
import { App, Table, Button, Card, Space, Tag, Modal, Spin, Empty, Typography, Tooltip } from 'antd';
import { ReloadOutlined, ExclamationCircleOutlined, CheckCircleOutlined, SyncOutlined } from '@ant-design/icons';
import { getFailedTasks, getIncompleteCatalogues, retryIncompleteCatalogues } from '../api/taskCompensation';
import dayjs from 'dayjs';

const { Title, Text } = Typography;

// 处理Java LocalDateTime数组格式 [2026, 2, 22, 16, 55, 16] 或字符串
const formatDateTime = (time) => {
    if (!time) return '-';
    try {
        if (Array.isArray(time)) {
            // Java LocalDateTime序列化为数组: [year, month, day, hour, minute, second, nano]
            const [year, month, day, hour = 0, minute = 0, second = 0] = time;
            return dayjs(new Date(year, month - 1, day, hour, minute, second)).format('YYYY-MM-DD HH:mm:ss');
        }
        return dayjs(time).format('YYYY-MM-DD HH:mm:ss');
    } catch (e) {
        return '-';
    }
};

const TaskCompensationPage = () => {
    const { message } = App.useApp();
    const [loading, setLoading] = useState(false);
    const [failedTasks, setFailedTasks] = useState([]);
    const [selectedTask, setSelectedTask] = useState(null);
    const [incompleteCatalogues, setIncompleteCatalogues] = useState([]);
    const [catalogueModalVisible, setCatalogueModalVisible] = useState(false);
    const [catalogueLoading, setCatalogueLoading] = useState(false);
    const [retryingTaskId, setRetryingTaskId] = useState(null);

    const fetchFailedTasks = useCallback(async () => {
        setLoading(true);
        try {
            const res = await getFailedTasks();
            if (res.code === 200) {
                setFailedTasks(res.data || []);
            } else {
                message.error(res.msg || '获取失败任务列表失败');
            }
        } catch (error) {
            message.error('获取失败任务列表失败');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchFailedTasks();
    }, [fetchFailedTasks]);

    const handleViewCatalogues = async (task) => {
        setSelectedTask(task);
        setCatalogueModalVisible(true);
        setCatalogueLoading(true);
        try {
            const res = await getIncompleteCatalogues(task.taskId);
            if (res.code === 200) {
                setIncompleteCatalogues(res.data || []);
            } else {
                message.error(res.msg || '获取未完成目录失败');
            }
        } catch (error) {
            message.error('获取未完成目录失败');
        } finally {
            setCatalogueLoading(false);
        }
    };

    const handleRetryTask = async (taskId) => {
        Modal.confirm({
            title: '确认重试',
            icon: <ExclamationCircleOutlined />,
            content: '确定要重新生成该任务中所有失败的文档吗？',
            okText: '确认重试',
            cancelText: '取消',
            onOk: async () => {
                setRetryingTaskId(taskId);
                try {
                    const res = await retryIncompleteCatalogues(taskId);
                    if (res.code === 200) {
                        message.success(res.data?.message || '重试任务已提交');
                        fetchFailedTasks();
                    } else {
                        message.error(res.msg || '重试失败');
                    }
                } catch (error) {
                    message.error('重试失败: ' + (error.message || '未知错误'));
                } finally {
                    setRetryingTaskId(null);
                }
            }
        });
    };

    const handleRetryCatalogue = async (catalogueId) => {
        if (!selectedTask) return;
        
        try {
            const res = await retryIncompleteCatalogues(selectedTask.taskId, [catalogueId]);
            if (res.code === 200) {
                message.success('重试任务已提交');
                // 刷新目录列表
                const catalogueRes = await getIncompleteCatalogues(selectedTask.taskId);
                if (catalogueRes.code === 200) {
                    setIncompleteCatalogues(catalogueRes.data || []);
                }
                fetchFailedTasks();
            } else {
                message.error(res.msg || '重试失败');
            }
        } catch (error) {
            message.error('重试失败');
        }
    };

    const getStatusTag = (status) => {
        switch (status) {
            case 1:
                return <Tag icon={<SyncOutlined spin />} color="processing">进行中</Tag>;
            case 2:
                return <Tag icon={<CheckCircleOutlined />} color="success">已完成</Tag>;
            case 3:
                return <Tag icon={<ExclamationCircleOutlined />} color="error">失败</Tag>;
            default:
                return <Tag color="default">未知</Tag>;
        }
    };

    const getTaskStatusTag = (status) => {
        switch (status) {
            case 'failed':
                return <Tag icon={<ExclamationCircleOutlined />} color="error">失败</Tag>;
            case 'pending':
                return <Tag icon={<SyncOutlined spin />} color="processing">进行中</Tag>;
            case 'completed':
                return <Tag icon={<CheckCircleOutlined />} color="success">已完成</Tag>;
            default:
                return <Tag color="default">{status || '未知'}</Tag>;
        }
    };

    const taskColumns = [
        {
            title: '项目名称',
            dataIndex: 'projectName',
            key: 'projectName',
            ellipsis: true,
            render: (text, record) => (
                <Tooltip title={record.projectUrl}>
                    <Text strong>{text || '未命名项目'}</Text>
                </Tooltip>
            )
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (status) => getTaskStatusTag(status)
        },
        {
            title: '失败原因',
            dataIndex: 'failReason',
            key: 'failReason',
            ellipsis: true,
            width: 300,
            render: (text) => (
                <Tooltip title={text}>
                    <Text type="secondary">{text || '-'}</Text>
                </Tooltip>
            )
        },
        {
            title: '未完成章节',
            dataIndex: 'incompleteCatalogueCount',
            key: 'incompleteCatalogueCount',
            width: 120,
            align: 'center',
            render: (count) => (
                <Tag color={count > 0 ? 'warning' : 'success'}>{count} 个</Tag>
            )
        },
        {
            title: '更新时间',
            dataIndex: 'updateTime',
            key: 'updateTime',
            width: 180,
            render: (time) => formatDateTime(time)
        },
        {
            title: '操作',
            key: 'action',
            width: 200,
            render: (_, record) => (
                <Space>
                    <Button
                        size="small"
                        onClick={() => handleViewCatalogues(record)}
                    >
                        查看详情
                    </Button>
                    <Button
                        type="primary"
                        size="small"
                        icon={<ReloadOutlined />}
                        loading={retryingTaskId === record.taskId}
                        onClick={() => handleRetryTask(record.taskId)}
                        disabled={record.incompleteCatalogueCount === 0}
                    >
                        重试
                    </Button>
                </Space>
            )
        }
    ];

    const catalogueColumns = [
        {
            title: '章节名称',
            dataIndex: 'name',
            key: 'name',
            ellipsis: true,
            render: (text, record) => (
                <div>
                    <Text strong>{text}</Text>
                    {record.title && record.title !== text && (
                        <div><Text type="secondary" style={{ fontSize: 12 }}>{record.title}</Text></div>
                    )}
                </div>
            )
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (status) => getStatusTag(status)
        },
        {
            title: '失败原因',
            dataIndex: 'failReason',
            key: 'failReason',
            ellipsis: true,
            width: 250,
            render: (text) => (
                <Tooltip title={text}>
                    <Text type="secondary">{text || '-'}</Text>
                </Tooltip>
            )
        },
        {
            title: '操作',
            key: 'action',
            width: 100,
            render: (_, record) => (
                <Button
                    type="link"
                    size="small"
                    icon={<ReloadOutlined />}
                    onClick={() => handleRetryCatalogue(record.catalogueId)}
                >
                    重试
                </Button>
            )
        }
    ];

    return (
        <div style={{ padding: 24 }}>
            <Card>
                <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Title level={4} style={{ margin: 0 }}>任务补偿</Title>
                    <Button
                        icon={<ReloadOutlined />}
                        onClick={fetchFailedTasks}
                        loading={loading}
                    >
                        刷新
                    </Button>
                </div>
                
                <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
                    以下是生成失败或未完成的文档任务，您可以重新生成失败的章节。
                </Text>

                {loading ? (
                    <div style={{ textAlign: 'center', padding: 50 }}>
                        <Spin size="large" />
                    </div>
                ) : failedTasks.length === 0 ? (
                    <Empty
                        description="没有失败的任务"
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                    />
                ) : (
                    <Table
                        columns={taskColumns}
                        dataSource={failedTasks}
                        rowKey="taskId"
                        pagination={{ pageSize: 10 }}
                    />
                )}
            </Card>

            <Modal
                title={`未完成章节 - ${selectedTask?.projectName || ''}`}
                open={catalogueModalVisible}
                onCancel={() => setCatalogueModalVisible(false)}
                footer={[
                    <Button key="close" onClick={() => setCatalogueModalVisible(false)}>
                        关闭
                    </Button>,
                    <Button
                        key="retryAll"
                        type="primary"
                        icon={<ReloadOutlined />}
                        onClick={() => {
                            if (selectedTask) {
                                handleRetryTask(selectedTask.taskId);
                                setCatalogueModalVisible(false);
                            }
                        }}
                        disabled={incompleteCatalogues.length === 0}
                    >
                        全部重试
                    </Button>
                ]}
                width={800}
            >
                {catalogueLoading ? (
                    <div style={{ textAlign: 'center', padding: 50 }}>
                        <Spin />
                    </div>
                ) : incompleteCatalogues.length === 0 ? (
                    <Empty
                        description="没有未完成的章节"
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                    />
                ) : (
                    <Table
                        columns={catalogueColumns}
                        dataSource={incompleteCatalogues}
                        rowKey="catalogueId"
                        pagination={false}
                        size="small"
                    />
                )}
            </Modal>
        </div>
    );
};

export default TaskCompensationPage;
