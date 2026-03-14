import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { App, Modal } from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  ExclamationCircleOutlined
} from '@ant-design/icons';
import ThesisApi from '../api/thesis';
import './ThesisPreview.css';

const ThesisPreview = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { message } = App.useApp();
  const searchParams = new URLSearchParams(location.search);
  const taskId = searchParams.get('taskId');
  const initialDocType = searchParams.get('docType') || 'thesis';
  const progressTimerRef = useRef(null);

  const [docType, setDocType] = useState(initialDocType);

  const [htmlContent, setHtmlContent] = useState(
    '<div style="padding: 40px; text-align: center; color: #64748b;">加载中...</div>'
  );
  const [selectedText, setSelectedText] = useState('');
  const [selectedParagraphIndex, setSelectedParagraphIndex] = useState(-1);
  const [currentVersion, setCurrentVersion] = useState(1);
  const [versionNote, setVersionNote] = useState('初始版本');
  const [versions, setVersions] = useState([]);
  const [optimizing, setOptimizing] = useState(false);
  const [notGenerated, setNotGenerated] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [generateProgress, setGenerateProgress] = useState(0);
  const [generateStep, setGenerateStep] = useState('');
  const [downloadingDocx, setDownloadingDocx] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [isFinalConfirmed, setIsFinalConfirmed] = useState(false);

  const [thesisInfoForm] = useState({
    title: '',
    college: '',
    studentName: '',
    studentId: '',
    major: '',
    className: '',
    advisor: '',
    defenseDate: ''
  });

  const [feedbackForm, setFeedbackForm] = useState({
    taskId,
    docType: initialDocType,
    version: 1,
    section: '',
    feedbackType: 'not_detailed',
    feedbackContent: ''
  });

  const DOC_TYPES = ['thesis', 'task_book', 'opening_report'];

  const docTitleOf = useCallback((type) => {
    if (type === 'task_book') return '任务书';
    if (type === 'opening_report') return '开题报告';
    return '毕业论文';
  }, []);

  const getDocTitle = useCallback(() => {
    return docTitleOf(docType);
  }, [docType]);

  const normalizeData = (result) => result?.data || result;

  const isSuccess = (result) => {
    const code = result?.code;
    return code === '0' || code === 0 || code === 200;
  };

  const stopProgressPolling = () => {
    if (progressTimerRef.current) {
      window.clearInterval(progressTimerRef.current);
      progressTimerRef.current = null;
    }
  };

  const loadPreview = useCallback(
    async (version, overrideDocType) => {
      if (!taskId) return true;
      try {
        const currentDocType = overrideDocType || docType;
        const result = await ThesisApi.preview(taskId, version, currentDocType);
        const data = normalizeData(result);

        const isNotGenerated = data?.notGenerated || !data?.htmlContent;
        if (isNotGenerated) {
          setNotGenerated(true);
          setHtmlContent('');
          return true;
        }

        setNotGenerated(false);
        setHtmlContent(
          data.htmlContent || '<div style="padding: 40px; text-align: center;">暂无内容</div>'
        );
        return false;
      } catch (error) {
        setNotGenerated(true);
        setHtmlContent('');
        return true;
      }
    },
    [taskId, docType]
  );

  const loadVersions = useCallback(
    async (overrideDocType) => {
      if (!taskId) return;
      try {
        const currentDocType = overrideDocType || docType;
        const result = await ThesisApi.versions(taskId, currentDocType);
        const data = normalizeData(result) || [];
        const list = Array.isArray(data) ? data : [];
        setVersions(list);
        if (list.length > 0) {
          const current = list.find((item) => item.isCurrent);
          if (current) {
            setCurrentVersion(current.version);
            setVersionNote(current.versionNotes || '初始版本');
            setIsFinalConfirmed(current.status === 'final');
          } else {
            setCurrentVersion(list[0].version);
            setVersionNote(list[0].versionNotes || '初始版本');
            setIsFinalConfirmed(list[0].status === 'final');
          }
        } else {
          setIsFinalConfirmed(false);
        }
      } catch (error) {
        setVersions([]);
        setIsFinalConfirmed(false);
      }
    },
    [taskId, docType]
  );

  const fetchProgressOnce = useCallback(async () => {
    if (!taskId) return;
    try {
      const result = await ThesisApi.progress(taskId, docType);
      const data = normalizeData(result);
      if (!data) return;
      if (typeof data.progress === 'number') {
        setGenerateProgress(data.progress);
      }
      if (data.currentStep) {
        setGenerateStep(data.currentStep);
      }
      if (data.status === 'completed' || data.status === 'failed') {
        stopProgressPolling();
      }
    } catch (error) {
      // ignore
    }
  }, [taskId, docType]);

  const startProgressPolling = useCallback(() => {
    stopProgressPolling();
    fetchProgressOnce();
    progressTimerRef.current = window.setInterval(() => {
      fetchProgressOnce();
    }, 1000);
  }, [fetchProgressOnce]);

  useEffect(() => {
    if (!taskId) {
      message.error('缺少任务ID');
      navigate('/');
      return;
    }

    const init = async () => {
      const isNotGenerated = await loadPreview(undefined, docType);
      if (!isNotGenerated) {
        await loadVersions(docType);
      }
    };

    init();

    return () => stopProgressPolling();
  }, [taskId, docType, navigate, loadPreview, loadVersions]);

  useEffect(() => {
    const selectable = document.querySelectorAll(
      '.thesis-content p, .thesis-content li, .thesis-content td, .thesis-content th, .thesis-content h1, .thesis-content h2, .thesis-content h3, .thesis-content h4, .thesis-content h5, .thesis-content h6, .thesis-content blockquote, .thesis-content .sec-title, .thesis-content .sec-content, .thesis-content .section, .thesis-content .content-text, .thesis-content .section-header, .thesis-content td.value'
    );
    let index = 0;
    selectable.forEach((el) => {
      const text = (el.textContent || '').trim();
      if (!text) return;
      el.classList.add('zwiki-selectable');
      el.setAttribute('data-index', index.toString());
      index += 1;
    });
  }, [htmlContent]);

  useEffect(() => {
    setFeedbackForm((prev) => ({
      ...prev,
      taskId,
      docType,
      version: currentVersion
    }));
  }, [taskId, docType, currentVersion]);

  useEffect(() => {
    setCurrentVersion(1);
    setVersionNote('初始版本');
    setVersions([]);
    setSelectedText('');
    setSelectedParagraphIndex(-1);
    setNotGenerated(false);
    setHtmlContent('<div style="padding: 40px; text-align: center; color: #64748b;">加载中...</div>');
    const init = async () => {
      const isNotGenerated = await loadPreview(undefined, docType);
      if (!isNotGenerated) {
        await loadVersions(docType);
      }
    };
    if (taskId) {
      init();
    }
  }, [docType, taskId, loadPreview, loadVersions]);

  const handleBackToRepo = () => {
    if (taskId) {
      navigate(`/repo/${taskId}`);
      return;
    }
    navigate('/');
  };

  const handleGenerateThesis = async () => {
    if (!taskId) return;
    setGenerating(true);
    setGenerateProgress(0);
    setGenerateStep('正在准备生成三份文档（论文/任务书/开题报告）...');
    startProgressPolling();

    try {
      const thesisInfo = {};
      if (thesisInfoForm.title) thesisInfo.title = thesisInfoForm.title;
      if (thesisInfoForm.studentName) thesisInfo.studentName = thesisInfoForm.studentName;
      if (thesisInfoForm.studentId) thesisInfo.studentId = thesisInfoForm.studentId;
      if (thesisInfoForm.major) thesisInfo.major = thesisInfoForm.major;
      if (thesisInfoForm.advisor) thesisInfo.advisor = thesisInfoForm.advisor;

      const results = await Promise.allSettled(
        DOC_TYPES.map((type) => ThesisApi.generate(taskId, thesisInfo, type))
      );

      const failed = [];
      results.forEach((r, idx) => {
        const type = DOC_TYPES[idx];
        if (r.status === 'rejected') {
          failed.push(`${docTitleOf(type)}：${r.reason?.message || '生成失败'}`);
          return;
        }
        if (!isSuccess(r.value)) {
          failed.push(`${docTitleOf(type)}：${r.value?.message || '生成失败'}`);
        }
      });

      if (failed.length === DOC_TYPES.length) {
        throw new Error(failed.join('；'));
      }
      if (failed.length > 0) {
        message.warning(`部分生成失败：${failed.join('；')}`);
      }

      setGenerateProgress(95);
      setGenerateStep(`正在加载${getDocTitle()}预览...`);
      await loadPreview(undefined, docType);
      await loadVersions(docType);

      setGenerateProgress(100);
      setGenerateStep(`${getDocTitle()}生成完成！`);
      message.success(`${getDocTitle()}生成成功！`);
    } catch (error) {
      message.error(error?.message || `${getDocTitle()}生成失败，请重试`);
    } finally {
      stopProgressPolling();
      setGenerating(false);
      setGenerateProgress(0);
      setGenerateStep('');
    }
  };

  const handleRegenerateThesis = async () => {
    if (!taskId) return;
    if (isFinalConfirmed) {
      message.warning('已确认最终版，无法修改');
      return;
    }
    setRegenerating(true);
    setGenerateProgress(0);
    setGenerateStep(`正在清理旧数据并重新生成${getDocTitle()}...`);
    startProgressPolling();

    try {
      const thesisInfo = {};
      if (thesisInfoForm.title) thesisInfo.title = thesisInfoForm.title;
      if (thesisInfoForm.studentName) thesisInfo.studentName = thesisInfoForm.studentName;
      if (thesisInfoForm.studentId) thesisInfo.studentId = thesisInfoForm.studentId;
      if (thesisInfoForm.major) thesisInfo.major = thesisInfoForm.major;
      if (thesisInfoForm.advisor) thesisInfo.advisor = thesisInfoForm.advisor;

      setGenerateProgress(10);
      setGenerateStep(`AI正在重新分析项目并生成${getDocTitle()}...`);

      const result = await ThesisApi.regenerate(
        taskId,
        {
          ...thesisInfo,
          clearOldVersions: true
        },
        docType
      );

      if (!isSuccess(result)) {
        throw new Error(result?.message || '重新生成失败');
      }

      setGenerateProgress(90);
      setGenerateStep(`正在加载${getDocTitle()}预览...`);
      await loadPreview(undefined, docType);
      await loadVersions(docType);

      setGenerateProgress(100);
      setGenerateStep(`${getDocTitle()}重新生成完成！`);
      message.success(`${getDocTitle()}重新生成成功！`);
    } catch (error) {
      message.error(error?.message || `${getDocTitle()}重新生成失败，请重试`);
    } finally {
      stopProgressPolling();
      setRegenerating(false);
      setGenerateProgress(0);
      setGenerateStep('');
    }
  };

  const handleParagraphClick = (event) => {
    const eventTarget = event.target;
    const element = eventTarget instanceof Element ? eventTarget : eventTarget?.parentElement;
    if (!element) return;

    const inThesisContent = element.closest('.thesis-content');
    if (!inThesisContent) return;

    const target = element.closest('.zwiki-selectable');
    if (!target) {
      document.querySelectorAll('.thesis-content .selected').forEach((el) => el.classList.remove('selected'));
      setSelectedText('');
      setSelectedParagraphIndex(-1);
      setFeedbackForm((prev) => ({
        ...prev,
        section: ''
      }));
      return;
    }

    document.querySelectorAll('.thesis-content .selected').forEach((el) => el.classList.remove('selected'));
    target.classList.add('selected');

    const text = target.textContent || '';
    setSelectedText(text);
    setSelectedParagraphIndex(Number(target.dataset.index || -1));
    setFeedbackForm((prev) => ({
      ...prev,
      section: text ? `${text.substring(0, 50)}...` : ''
    }));
  };

  const submitFeedback = async () => {
    if (!selectedText || !feedbackForm.feedbackContent) {
      message.warning('请选择段落并输入反馈内容');
      return;
    }

    setOptimizing(true);

    try {
      const feedbackResult = await ThesisApi.feedback({
        ...feedbackForm,
        taskId,
        docType
      });
      const feedbackData = normalizeData(feedbackResult);
      const feedbackId = feedbackData?.feedbackId;

      if (feedbackId === undefined || feedbackId === null) {
        throw new Error('提交反馈失败，未获取到反馈ID');
      }

      message.info('AI正在优化中，请稍候...');
      const optimizeResult = await ThesisApi.optimize(taskId, feedbackId);
      if (!isSuccess(optimizeResult)) {
        throw new Error(optimizeResult?.message || '优化失败');
      }

      setCurrentVersion((prev) => prev + 1);
      await loadPreview(undefined, docType);
      await loadVersions(docType);

      setFeedbackForm((prev) => ({
        ...prev,
        feedbackContent: ''
      }));
      setSelectedText('');
      document.querySelectorAll('.thesis-content .selected').forEach((el) => el.classList.remove('selected'));

      message.success('优化完成！');
    } catch (error) {
      message.error(error?.message || '优化失败');
    } finally {
      setOptimizing(false);
    }
  };

  const switchVersion = (version) => {
    setCurrentVersion(version);
    loadPreview(version, docType);
  };

  const confirmFinal = async () => {
    if (!taskId) return;
    if (isFinalConfirmed) {
      message.warning('已确认最终版，无法修改');
      return;
    }
    Modal.confirm({
      title: '确认最终版本',
      icon: <ExclamationCircleOutlined />,
      content: `确定要将当前${getDocTitle()}版本确认为最终版吗？确认后将无法再修改。`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        try {
          const result = await ThesisApi.confirm(taskId, currentVersion, docType);
          if (!isSuccess(result)) {
            throw new Error(result?.message || '操作失败');
          }
          setIsFinalConfirmed(true);
          message.success('已确认为最终版本');
        } catch (error) {
          message.error(error?.message || '操作失败');
        }
      }
    });
  };

  const handleDownloadDocx = async () => {
    if (!taskId) return;
    setDownloadingDocx(true);

    try {
      const response = await ThesisApi.downloadDocx(taskId, currentVersion, docType, thesisInfoForm);
      const data = normalizeData(response);

      if (data?.downloadUrl) {
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.filename || `${getDocTitle()}_v${currentVersion}.docx`;
        link.target = '_blank';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        message.success('Word 文档下载成功！');
      } else if (data?.fileBase64) {
        const binary = window.atob(data.fileBase64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) {
          bytes[i] = binary.charCodeAt(i);
        }
        const blob = new Blob([bytes], {
          type:
            data.contentType ||
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        });
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = data.filename || `${getDocTitle()}_v${currentVersion}.docx`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(url);
        message.success('Word 文档下载成功！');
      } else {
        throw new Error('获取下载内容失败');
      }
    } catch (error) {
      message.error(error?.message || '下载失败，请重试');
    } finally {
      setDownloadingDocx(false);
    }
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

  return (
    <div className="thesis-preview">
      <div className="container">
        {notGenerated ? (
          <div className="generate-guide">
            <button type="button" className="back-link floating" onClick={handleBackToRepo}>
              <ArrowLeftOutlined />
              返回仓库
            </button>
            <div className="guide-card">
              <div className="guide-icon">📄</div>
              <h2>毕业论文/任务书/开题报告生成</h2>
              <p>AI智能一键生成全套毕业设计文档</p>
              <p className="sub-tip">包括：毕业论文、任务书、开题报告</p>

              <div className="generate-action">
                <button
                  type="button"
                  className="primary-button"
                  onClick={handleGenerateThesis}
                  disabled={generating}
                >
                  {!generating && <ThunderboltOutlined />}
                  {generating ? '正在生成全套文档...' : '一键生成全套文档'}
                </button>
                <p className="generate-tip">包含三份文档，生成可能需要几分钟，请耐心等待</p>
              </div>

              {(generating || generateProgress > 0) && (
                <div className="progress-info">
                  <div className="progress-bar">
                    <div
                      className="progress-bar-inner"
                      style={{ width: `${generateProgress}%` }}
                    />
                  </div>
                  <p>{generateStep}</p>
                </div>
              )}
            </div>
          </div>
        ) : (
          <>
            <div className="preview-section">
              <div className="toolbar">
                <div className="toolbar-left">
                  <button type="button" className="back-link" onClick={handleBackToRepo}>
                    <ArrowLeftOutlined />
                    返回仓库
                  </button>
                  <div className="doc-type-tabs">
                    <button
                      type="button"
                      className={`doc-type-tab ${docType === 'thesis' ? 'active' : ''}`}
                      onClick={() => setDocType('thesis')}
                    >
                      毕业论文
                    </button>
                    <button
                      type="button"
                      className={`doc-type-tab ${docType === 'task_book' ? 'active' : ''}`}
                      onClick={() => setDocType('task_book')}
                    >
                      任务书
                    </button>
                    <button
                      type="button"
                      className={`doc-type-tab ${docType === 'opening_report' ? 'active' : ''}`}
                      onClick={() => setDocType('opening_report')}
                    >
                      开题报告
                    </button>
                  </div>
                  <span className="version-tag">v{currentVersion}</span>
                </div>
                <div className="toolbar-actions">
                  <span
                    className={`action-link ${regenerating || isFinalConfirmed ? 'disabled' : ''}`}
                    onClick={() => {
                      if (!regenerating && !isFinalConfirmed) handleRegenerateThesis();
                    }}
                    title={isFinalConfirmed ? '已确认最终版，无法修改' : ''}
                  >
                    {!regenerating ? <ReloadOutlined /> : <span className="loading-dots">...</span>}
                    {regenerating ? '重新生成中' : '重新生成'}
                  </span>
                  <span
                    className={`action-link primary ${downloadingDocx ? 'disabled' : ''}`}
                    onClick={() => {
                      if (!downloadingDocx) handleDownloadDocx();
                    }}
                  >
                    <svg className="word-icon" viewBox="0 0 24 24" width="14" height="14">
                      <path
                        fill="currentColor"
                        d="M6 2C4.89 2 4 2.89 4 4V20C4 21.11 4.89 22 6 22H18C19.11 22 20 21.11 20 20V8L14 2H6M13 3.5L18.5 9H13V3.5M7 13L8.5 18L10.5 13H12L14 18L15.5 13H17L14.5 20H13L11 15L9 20H7.5L5 13H7Z"
                      />
                    </svg>
                    下载 Word
                  </span>
                  <span
                    className={`action-link success ${isFinalConfirmed ? 'disabled' : ''}`}
                    onClick={() => {
                      if (!isFinalConfirmed) confirmFinal();
                    }}
                    title={isFinalConfirmed ? '已确认最终版，无法修改' : ''}
                  >
                    <CheckCircleOutlined />
                    {isFinalConfirmed ? '已确认最终版' : '确认最终版'}
                  </span>
                </div>
              </div>

              {regenerating && (
                <div className="regenerate-progress">
                  <div className="progress-bar">
                    <div
                      className="progress-bar-inner"
                      style={{ width: `${generateProgress}%` }}
                    />
                  </div>
                  <p>{generateStep}</p>
                </div>
              )}

              <div
                className={`thesis-content doc-${docType}`}
                onClick={handleParagraphClick}
                dangerouslySetInnerHTML={{ __html: htmlContent }}
              />
            </div>

            <div className="feedback-section">
              <div className="feedback-header">
                <h2>💬 反馈与优化</h2>
              </div>

              <div className="feedback-content">
                <form className="feedback-form" onSubmit={(event) => event.preventDefault()}>
                  <div className="form-item">
                    <label className="form-label">选中的段落</label>
                    <div className={`selected-text ${selectedText ? '' : 'empty'}`}>
                      {selectedText || '请点击左侧论文内容选择需要优化的段落'}
                    </div>
                  </div>

                  <div className="form-item">
                    <label className="form-label">问题类型</label>
                    <select
                      className="form-select"
                      value={feedbackForm.feedbackType}
                      onChange={(event) =>
                        setFeedbackForm((prev) => ({
                          ...prev,
                          feedbackType: event.target.value
                        }))
                      }
                    >
                      <option value="not_detailed">不够详细</option>
                      <option value="unclear">表述模糊</option>
                      <option value="incorrect">内容不对</option>
                      <option value="other">其他问题</option>
                    </select>
                  </div>

                  <div className="form-item">
                    <label className="form-label">详细说明</label>
                    <textarea
                      className="form-textarea"
                      rows={4}
                      value={feedbackForm.feedbackContent}
                      onChange={(event) =>
                        setFeedbackForm((prev) => ({
                          ...prev,
                          feedbackContent: event.target.value
                        }))
                      }
                      placeholder="请具体说明需要改进的地方..."
                    />
                  </div>

                  <button
                    type="button"
                    className="primary-button full-width"
                    onClick={submitFeedback}
                    disabled={optimizing || !selectedText || isFinalConfirmed}
                    title={isFinalConfirmed ? '已确认最终版，无法修改' : ''}
                  >
                    <ThunderboltOutlined />
                    {isFinalConfirmed ? '已确认最终版' : '提交反馈并优化'}
                  </button>
                </form>

                <div className="version-history">
                  <h3>📋 版本历史</h3>
                  {versions.length > 0 ? (
                    <div className="version-list">
                      {versions.map((version) => (
                        <div
                          key={version.id || version.version}
                          className={`version-item ${
                            version.version === currentVersion ? 'active' : ''
                          }`}
                          onClick={() => switchVersion(version.version)}
                        >
                          <div className="version-badge">v{version.version}</div>
                          <div className="version-details">
                            <div className="version-notes">
                              {version.versionNotes || '自动生成版本'}
                            </div>
                            <div className="version-time">{formatTime(version.createdAt)}</div>
                          </div>
                          {version.version === currentVersion && (
                            <span className="version-tag success">当前</span>
                          )}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="no-versions">暂无版本记录</div>
                  )}
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default ThesisPreview;
