// Deprecated: ThesisPanel replaced by full-page ThesisPreview.
const ThesisPanel = () => null;

export default ThesisPanel;
/*
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  Drawer,
  Form,
  Input,
  Progress,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
  Upload,
  message
} from 'antd';
import {
  CloudDownloadOutlined,
  FileTextOutlined,
  LoadingOutlined,
  ReloadOutlined,
  SendOutlined,
  UploadOutlined
} from '@ant-design/icons';
import ThesisApi from '../api/thesis';

const { Text, Title } = Typography;
const { TextArea } = Input;

const feedbackTypeOptions = [
  { label: '内容补充', value: 'content' },
  { label: '结构优化', value: 'structure' },
  { label: '表达润色', value: 'polish' },
  { label: '纠错修改', value: 'fix' }
];

const ThesisPanel = ({ open, onClose, taskId, darkMode }) => {
  const [loading, setLoading] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [progress, setProgress] = useState({
    status: 'not_started',
    progress: 0,
    currentStep: '未开始'
  });
  const [previewHtml, setPreviewHtml] = useState('');
  const [versions, setVersions] = useState([]);
  const [selectedVersion, setSelectedVersion] = useState(null);
  const [templatePath, setTemplatePath] = useState('');
  const [templateFile, setTemplateFile] = useState(null);
  const [feedbackForm, setFeedbackForm] = useState({
    section: '',
    feedbackType: 'content',
    feedbackContent: ''
  });
  const [thesisInfo, setThesisInfo] = useState({
    title: '',
    college: '',
    studentName: '',
    studentId: '',
    major: '',
    className: '',
    advisor: '',
    defenseDate: ''
  });

  const pollRef = useRef(null);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const fetchProgress = useCallback(async () => {
    if (!taskId) return;
    try {
      const response = await ThesisApi.progress(taskId);
      if (response.code === 200) {
        setProgress(response.data || {});
        return response.data;
      }
    } catch (error) {
      // ignore
    }
    return null;
  }, [taskId]);

  const fetchVersions = useCallback(async () => {
    if (!taskId) return null;
    try {
      const response = await ThesisApi.versions(taskId);
      if (response.code === 200) {
        const list = response.data || [];
        setVersions(list);
        const current = list.find(item => item.isCurrent);
        const resolvedVersion = current?.version || list?.[0]?.version || null;
        setSelectedVersion(resolvedVersion);
        return resolvedVersion;
      }
    } catch (error) {
      message.error('获取版本列表失败');
    }
    return null;
  }, [taskId]);

  const fetchPreview = useCallback(async (version) => {
    if (!taskId) return;
    setPreviewLoading(true);
    try {
      const response = await ThesisApi.preview(taskId, version || undefined);
      if (response.code === 200) {
        setPreviewHtml(response.data?.htmlContent || '');
      } else {
        message.error(response.msg || '获取预览失败');
      }
    } catch (error) {
      message.error('获取预览失败');
    } finally {
      setPreviewLoading(false);
    }
  }, [taskId]);

  const startPolling = useCallback(() => {
    stopPolling();
    pollRef.current = setInterval(async () => {
      const data = await fetchProgress();
      if (!data) return;
      if (data.status === 'completed' || data.status === 'failed') {
        stopPolling();
        const latestVersion = await fetchVersions();
        await fetchPreview(latestVersion || undefined);
      }
    }, 2000);
  }, [fetchProgress, fetchPreview, fetchVersions, selectedVersion]);

  useEffect(() => {
    if (open) {
      fetchProgress();
      fetchVersions();
      fetchPreview();
    } else {
      stopPolling();
    }
    return () => stopPolling();
  }, [open, fetchProgress, fetchVersions, fetchPreview]);

  const handleGenerate = async (isRegenerate) => {
    if (!taskId) return;
    setLoading(true);
    try {
      const payload = { ...thesisInfo };
      const response = isRegenerate
        ? await ThesisApi.regenerate(taskId, { ...payload, clearOldVersions: true })
        : await ThesisApi.generate(taskId, payload);
      if (response.code === 200) {
        message.success(isRegenerate ? '论文重新生成中' : '论文生成中');
        await fetchProgress();
        startPolling();
      } else {
        message.error(response.msg || '生成失败');
      }
    } catch (error) {
      message.error('生成失败');
    } finally {
      setLoading(false);
    }
  };

  const handleUploadTemplate = async () => {
    if (!taskId || !templateFile) {
      message.warning('请先选择模板文件');
      return;
    }
    setLoading(true);
    try {
      const formData = new FormData();
      formData.append('template', templateFile);
      const response = await ThesisApi.uploadTemplate(taskId, formData);
      if (response.code === 200) {
        message.success('模板上传成功');
        setTemplatePath(response.data?.templatePath || '');
      } else {
        message.error(response.msg || '模板上传失败');
      }
    } catch (error) {
      message.error('模板上传失败');
    } finally {
      setLoading(false);
    }
  };

  const handleFillTemplate = async () => {
    if (!taskId || !templatePath) {
      message.warning('请先上传模板');
      return;
    }
    setLoading(true);
    try {
      const response = await ThesisApi.fill(taskId, templatePath);
      if (response.code === 200) {
        message.success('模板填充中');
        startPolling();
      } else {
        message.error(response.msg || '模板填充失败');
      }
    } catch (error) {
      message.error('模板填充失败');
    } finally {
      setLoading(false);
    }
  };

  const handleFeedbackOptimize = async () => {
    if (!taskId) return;
    if (!feedbackForm.feedbackContent) {
      message.warning('请填写反馈内容');
      return;
    }
    setLoading(true);
    try {
      const payload = {
        taskId,
        version: selectedVersion,
        section: feedbackForm.section || '整体内容',
        feedbackType: feedbackForm.feedbackType,
        feedbackContent: feedbackForm.feedbackContent
      };
      const feedbackRes = await ThesisApi.feedback(payload);
      if (feedbackRes.code !== 200) {
        message.error(feedbackRes.msg || '提交反馈失败');
        return;
      }
      const optimizeRes = await ThesisApi.optimize(taskId, feedbackRes.data?.feedbackId);
      if (optimizeRes.code === 200) {
        message.success('优化中');
        startPolling();
      } else {
        message.error(optimizeRes.msg || '优化失败');
      }
    } catch (error) {
      message.error('优化失败');
    } finally {
      setLoading(false);
    }
  };

  const handleDownload = async (type) => {
    if (!taskId) return;
    setLoading(true);
    try {
      const response = type === 'docx'
        ? await ThesisApi.downloadDocx(taskId, selectedVersion, thesisInfo)
        : await ThesisApi.downloadMarkdown(taskId, selectedVersion);
      if (response.code === 200) {
        const { filename, fileBase64, contentType } = response.data || {};
        if (!fileBase64) {
          message.error('文件内容为空');
          return;
        }
        const link = document.createElement('a');
        link.href = `data:${contentType};base64,${fileBase64}`;
        link.download = filename || `thesis.${type === 'docx' ? 'docx' : 'md'}`;
        document.body.appendChild(link);
        link.click();
        link.remove();
      } else {
        message.error(response.msg || '下载失败');
      }
    } catch (error) {
      message.error('下载失败');
    } finally {
      setLoading(false);
    }
  };

  const handleConfirmVersion = async () => {
    if (!taskId || !selectedVersion) return;
    setLoading(true);
    try {
      const response = await ThesisApi.confirm(taskId, selectedVersion);
      if (response.code === 200) {
        message.success('已确认最终版本');
        fetchVersions();
      } else {
        message.error(response.msg || '确认失败');
      }
    } catch (error) {
      message.error('确认失败');
    } finally {
      setLoading(false);
    }
  };

  const progressStatus = useMemo(() => {
    if (progress.status === 'failed') return 'exception';
    if (progress.status === 'completed') return 'success';
    return 'active';
  }, [progress.status]);

  const previewEmpty = !previewHtml;

  return (
    <Drawer
      title={<span><FileTextOutlined /> 论文预览与生成</span>}
      open={open}
      onClose={onClose}
      width={980}
      destroyOnHidden
      bodyStyle={{ padding: 24, background: darkMode ? '#141414' : '#fff' }}
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="支持生成 → 预览 → 反馈优化 → 下载完整流程"
          description="论文内容优先来自目录内容，图表自动插入。"
        />

        <div style={{
          background: darkMode ? '#1f1f1f' : '#fafafa',
          borderRadius: 12,
          padding: 16
        }}>
          <Space align="center" wrap>
            <Text strong>生成进度：</Text>
            <Progress percent={progress.progress || 0} status={progressStatus} size="small" />
            <Tag color={progress.status === 'failed' ? 'red' : progress.status === 'completed' ? 'green' : 'blue'}>
              {progress.currentStep || '未开始'}
            </Tag>
          </Space>
        </div>

        <Tabs
          defaultActiveKey="generate"
          items={[
            {
              key: 'generate',
              label: '生成设置',
              children: (
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <Form layout="vertical">
                    <Title level={5}>论文信息</Title>
                    <Form.Item label="论文题目">
                      <Input
                        value={thesisInfo.title}
                        onChange={(e) => setThesisInfo(prev => ({ ...prev, title: e.target.value }))}
                        placeholder="默认：项目系统设计与实现"
                      />
                    </Form.Item>
                    <Space wrap style={{ width: '100%' }}>
                      <Form.Item label="学院" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.college}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, college: e.target.value }))}
                        />
                      </Form.Item>
                      <Form.Item label="专业" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.major}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, major: e.target.value }))}
                        />
                      </Form.Item>
                      <Form.Item label="班级" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.className}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, className: e.target.value }))}
                        />
                      </Form.Item>
                    </Space>
                    <Space wrap style={{ width: '100%' }}>
                      <Form.Item label="学生姓名" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.studentName}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, studentName: e.target.value }))}
                        />
                      </Form.Item>
                      <Form.Item label="学号" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.studentId}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, studentId: e.target.value }))}
                        />
                      </Form.Item>
                      <Form.Item label="指导教师" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.advisor}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, advisor: e.target.value }))}
                        />
                      </Form.Item>
                      <Form.Item label="答辩日期" style={{ flex: 1, minWidth: 200 }}>
                        <Input
                          value={thesisInfo.defenseDate}
                          onChange={(e) => setThesisInfo(prev => ({ ...prev, defenseDate: e.target.value }))}
                          placeholder="2026-06-01"
                        />
                      </Form.Item>
                    </Space>
                  </Form>

                  <Space wrap>
                    <Button
                      type="primary"
                      icon={loading ? <LoadingOutlined /> : <SendOutlined />}
                      onClick={() => handleGenerate(false)}
                      loading={loading}
                    >
                      开始生成
                    </Button>
                    <Button
                      icon={<ReloadOutlined />}
                      onClick={() => handleGenerate(true)}
                      loading={loading}
                    >
                      重新生成
                    </Button>
                  </Space>

                  <Divider />

                  <Title level={5}>模板填充（可选）</Title>
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Upload
                      beforeUpload={(file) => {
                        setTemplateFile(file);
                        return false;
                      }}
                      maxCount={1}
                      accept=".docx"
                    >
                      <Button icon={<UploadOutlined />}>选择模板</Button>
                    </Upload>
                    {templatePath && (
                      <Text type="secondary">模板路径：{templatePath}</Text>
                    )}
                    <Space>
                      <Button onClick={handleUploadTemplate} loading={loading}>上传模板</Button>
                      <Button type="primary" onClick={handleFillTemplate} loading={loading}>
                        智能填充模板
                      </Button>
                    </Space>
                  </Space>
                </Space>
              )
            },
            {
              key: 'preview',
              label: '预览',
              children: (
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <Space wrap>
                    <Text strong>版本：</Text>
                    <Select
                      style={{ minWidth: 160 }}
                      value={selectedVersion}
                      onChange={(value) => {
                        setSelectedVersion(value);
                        fetchPreview(value);
                      }}
                      options={versions.map(item => ({
                        label: `V${item.version}${item.isCurrent ? ' (当前)' : ''}`,
                        value: item.version
                      }))}
                    />
                    <Button onClick={() => fetchPreview(selectedVersion)} loading={previewLoading}>
                      刷新预览
                    </Button>
                  </Space>
                  <div style={{
                    border: darkMode ? '1px solid #303030' : '1px solid #f0f0f0',
                    borderRadius: 12,
                    padding: 16,
                    minHeight: 320,
                    background: darkMode ? '#141414' : '#fff'
                  }}>
                    {previewLoading ? (
                      <Spin />
                    ) : previewEmpty ? (
                      <Text type="secondary">暂无预览内容，请先生成论文。</Text>
                    ) : (
                      <iframe
                        title="thesis-preview"
                        srcDoc={previewHtml}
                        style={{
                          width: '100%',
                          minHeight: 640,
                          border: 'none',
                          background: '#fff'
                        }}
                      />
                    )}
                  </div>
                </Space>
              )
            },
            {
              key: 'feedback',
              label: '反馈优化',
              children: (
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <Form layout="vertical">
                    <Form.Item label="需要优化的章节/段落">
                      <Input
                        value={feedbackForm.section}
                        onChange={(e) => setFeedbackForm(prev => ({ ...prev, section: e.target.value }))}
                        placeholder="如：系统设计/数据库设计/结论等"
                      />
                    </Form.Item>
                    <Form.Item label="反馈类型">
                      <Select
                        options={feedbackTypeOptions}
                        value={feedbackForm.feedbackType}
                        onChange={(value) => setFeedbackForm(prev => ({ ...prev, feedbackType: value }))}
                      />
                    </Form.Item>
                    <Form.Item label="反馈内容" required>
                      <TextArea
                        rows={4}
                        value={feedbackForm.feedbackContent}
                        onChange={(e) => setFeedbackForm(prev => ({ ...prev, feedbackContent: e.target.value }))}
                        placeholder="请描述需要优化的内容"
                      />
                    </Form.Item>
                  </Form>
                  <Button
                    type="primary"
                    icon={<SendOutlined />}
                    onClick={handleFeedbackOptimize}
                    loading={loading}
                  >
                    提交反馈并优化
                  </Button>
                </Space>
              )
            },
            {
              key: 'versions',
              label: '版本管理',
              children: (
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  {versions.length === 0 ? (
                    <Text type="secondary">暂无版本记录</Text>
                  ) : (
                    <Space direction="vertical" style={{ width: '100%' }}>
                      {versions.map(item => (
                        <div
                          key={item.id}
                          style={{
                            padding: 12,
                            borderRadius: 10,
                            border: darkMode ? '1px solid #303030' : '1px solid #f0f0f0',
                            background: darkMode ? '#1f1f1f' : '#fff'
                          }}
                        >
                          <Space wrap>
                            <Tag color={item.isCurrent ? 'green' : 'default'}>V{item.version}</Tag>
                            <Text>{item.versionNotes || '无备注'}</Text>
                            <Tag>{item.status}</Tag>
                          </Space>
                        </div>
                      ))}
                    </Space>
                  )}
                  <Button onClick={handleConfirmVersion} disabled={!selectedVersion}>
                    确认当前版本为最终版
                  </Button>
                </Space>
              )
            },
            {
              key: 'download',
              label: '下载',
              children: (
                <Space direction="vertical" size={16}>
                  <Button
                    type="primary"
                    icon={<CloudDownloadOutlined />}
                    onClick={() => handleDownload('docx')}
                    loading={loading}
                  >
                    下载 Word
                  </Button>
                  <Button
                    icon={<CloudDownloadOutlined />}
                    onClick={() => handleDownload('md')}
                    loading={loading}
                  >
                    下载 Markdown
                  </Button>
                </Space>
              )
            }
          ]}
        />
      </Space>
    </Drawer>
  );
};

export default ThesisPanel;
*/
