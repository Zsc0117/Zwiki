import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Empty, Spin, Input, App, Modal } from 'antd';
import {
  FolderOutlined,
  FolderOpenOutlined,
  FileOutlined,
  ArrowLeftOutlined,
  SearchOutlined,
  QuestionCircleOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import hljs from 'highlight.js';
import 'highlight.js/styles/github-dark.css';
import { RepoApi } from '../api/repo';
import { TaskApi } from '../api/task';
import PageLoading from '../components/PageLoading';

const { TextArea } = Input;

// ---------- helpers ----------

const getLanguageFromPath = (path) => {
  const ext = (path || '').split('.').pop().toLowerCase();
  const map = {
    js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
    java: 'java', py: 'python', rb: 'ruby', go: 'go', rs: 'rust',
    c: 'c', cpp: 'cpp', h: 'c', hpp: 'cpp', cs: 'csharp',
    html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less',
    json: 'json', xml: 'xml', yaml: 'yaml', yml: 'yaml', toml: 'toml',
    md: 'markdown', sql: 'sql', sh: 'bash', bash: 'bash', zsh: 'bash',
    dockerfile: 'dockerfile', makefile: 'makefile',
    vue: 'html', svelte: 'html', kt: 'kotlin', swift: 'swift',
    gradle: 'groovy', groovy: 'groovy', properties: 'properties',
  };
  return map[ext] || 'plaintext';
};

const formatFileSize = (bytes) => {
  if (!bytes) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const getFileIcon = (name) => {
  const ext = (name || '').split('.').pop().toLowerCase();
  const iconColors = {
    java: '#b07219', js: '#f1e05a', jsx: '#f1e05a', ts: '#3178c6', tsx: '#3178c6',
    py: '#3572A5', go: '#00ADD8', rs: '#dea584', rb: '#701516',
    html: '#e34c26', css: '#563d7c', scss: '#c6538c', vue: '#41b883',
    json: '#292929', xml: '#0060ac', yml: '#cb171e', yaml: '#cb171e',
    md: '#083fa1', sql: '#e38c00', sh: '#89e051',
    c: '#555555', cpp: '#f34b7d', cs: '#178600', kt: '#A97BFF',
  };
  return iconColors[ext] || '#6b7280';
};

// ---------- FileTreeItem ----------

const FileTreeItem = ({ node, depth, expandedDirs, onToggleDir, onSelectFile, activeFilePath }) => {
  const isDir = node.type === 'directory';
  const isExpanded = expandedDirs.has(node.path);
  const isActive = activeFilePath === node.path;

  if (isDir) {
    return (
      <div className="cb-tree-group">
        <div
          className={`cb-tree-item cb-tree-dir depth-${Math.min(depth, 5)}`}
          onClick={() => onToggleDir(node.path)}
        >
          {isExpanded
            ? <FolderOpenOutlined style={{ color: '#f59e0b', marginRight: 6 }} />
            : <FolderOutlined style={{ color: '#f59e0b', marginRight: 6 }} />}
          <span className="cb-tree-name">{node.name}</span>
        </div>
        {isExpanded && node.children && (
          <div className="cb-tree-children">
            {node.children.map((child) => (
              <FileTreeItem
                key={child.path}
                node={child}
                depth={depth + 1}
                expandedDirs={expandedDirs}
                onToggleDir={onToggleDir}
                onSelectFile={onSelectFile}
                activeFilePath={activeFilePath}
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <div
      className={`cb-tree-item cb-tree-file depth-${Math.min(depth, 5)} ${isActive ? 'active' : ''}`}
      onClick={() => onSelectFile(node)}
    >
      <FileOutlined style={{ color: getFileIcon(node.name), marginRight: 6, fontSize: 13 }} />
      <span className="cb-tree-name">{node.name}</span>
      {node.size != null && (
        <span className="cb-tree-size">{formatFileSize(node.size)}</span>
      )}
    </div>
  );
};

// ---------- CodeBrowser ----------

const CodeBrowser = () => {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [searchParams] = useSearchParams();
  const taskId = searchParams.get('taskId');

  const [task, setTask] = useState(null);
  const [treeData, setTreeData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [fileLoading, setFileLoading] = useState(false);
  const [fileContent, setFileContent] = useState('');
  const [activeFile, setActiveFile] = useState(null);
  const [expandedDirs, setExpandedDirs] = useState(new Set());
  const [searchText, setSearchText] = useState('');
  const [error, setError] = useState('');

  // Selection toolbar state
  const [selectionToolbar, setSelectionToolbar] = useState(null); // { x, y, text }
  // Ask AI dialog state
  const [askAiVisible, setAskAiVisible] = useState(false);
  const [askAiQuestion, setAskAiQuestion] = useState('');
  const [askAiSelectedCode, setAskAiSelectedCode] = useState('');
  const [askAiLoading, setAskAiLoading] = useState(false);
  const [askAiAnswer, setAskAiAnswer] = useState('');

  const codeContainerRef = useRef(null);
  const codeRef = useRef(null);

  // Fetch data
  useEffect(() => {
    if (!taskId) {
      setError('缺少 taskId 参数');
      setLoading(false);
      return;
    }
    const fetchData = async () => {
      setLoading(true);
      try {
        const [taskRes, treeRes] = await Promise.all([
          TaskApi.getTaskDetail(taskId),
          RepoApi.getFileTree(taskId),
        ]);
        if (taskRes?.code === 200) setTask(taskRes.data);
        else { setError(taskRes?.msg || '获取任务信息失败'); return; }

        if (treeRes?.code === 200) {
          setTreeData(treeRes.data || []);
          // Auto-expand first level directories
          const firstLevel = new Set();
          (treeRes.data || []).forEach((n) => {
            if (n.type === 'directory') firstLevel.add(n.path);
          });
          setExpandedDirs(firstLevel);
        } else {
          setError(treeRes?.msg || '获取文件树失败');
        }
      } catch (e) {
        console.error(e);
        setError('加载失败');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [taskId]);

  // Highlight code when content changes
  useEffect(() => {
    if (codeRef.current && fileContent) {
      hljs.highlightElement(codeRef.current);
    }
  }, [fileContent, activeFile]);

  // ---- Tree actions ----
  const handleToggleDir = useCallback((path) => {
    setExpandedDirs((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }, []);

  const handleSelectFile = useCallback(async (node) => {
    if (activeFile?.path === node.path) return;
    setActiveFile(node);
    setFileContent('');
    setFileLoading(true);
    setSelectionToolbar(null);
    try {
      const res = await RepoApi.getFileContent(taskId, node.path);
      if (res?.code === 200) {
        setFileContent(res.data || '');
      } else {
        message.error(res?.msg || '读取文件失败');
        setFileContent('');
      }
    } catch (e) {
      message.error('读取文件失败');
      setFileContent('');
    } finally {
      setFileLoading(false);
    }
  }, [taskId, activeFile]);

  // ---- Text selection ----
  const handleMouseUp = useCallback(() => {
    const selection = window.getSelection();
    const text = selection?.toString()?.trim();
    if (!text || text.length < 2) {
      setSelectionToolbar(null);
      return;
    }

    const range = selection.getRangeAt(0);
    const rect = range.getBoundingClientRect();
    const containerRect = codeContainerRef.current?.getBoundingClientRect();
    if (!containerRect) return;

    setSelectionToolbar({
      x: rect.left - containerRect.left + rect.width / 2,
      y: rect.top - containerRect.top - 48,
      text,
    });
  }, []);

  const handleAskClick = useCallback(() => {
    if (!selectionToolbar?.text) return;
    setAskAiSelectedCode(selectionToolbar.text);
    setAskAiQuestion('');
    setAskAiAnswer('');
    setAskAiVisible(true);
    setSelectionToolbar(null);
    window.getSelection()?.removeAllRanges();
  }, [selectionToolbar]);

  // ---- Ask AI ----
  const handleAskAi = useCallback(async () => {
    const q = askAiQuestion.trim();
    if (!q) { message.warning('请输入你的问题'); return; }
    setAskAiLoading(true);
    setAskAiAnswer('');

    const fullQuery = `以下是代码片段（来自文件 ${activeFile?.path || ''})：\n\`\`\`\n${askAiSelectedCode}\n\`\`\`\n\n我的问题：${q}`;

    try {
      const params = new URLSearchParams({
        query: fullQuery,
        intent: 'project',
        ...(taskId ? { taskId } : {}),
      });
      const response = await fetch(`/api/chat/stream?${params.toString()}`, { credentials: 'include' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let accumulated = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (line.startsWith('event:done')) break;
          if (line.startsWith('event:error')) continue;
          if (line.startsWith('data:')) {
            accumulated += line.slice(5);
            setAskAiAnswer(accumulated);
          }
        }
      }
      if (!accumulated) setAskAiAnswer('抱歉，我现在无法回答您的问题。');
    } catch (e) {
      console.error(e);
      setAskAiAnswer('抱歉，处理您的问题时出现了错误。');
    } finally {
      setAskAiLoading(false);
    }
  }, [askAiQuestion, askAiSelectedCode, activeFile, taskId]);

  // ---- Filter tree ----
  const filterTree = useCallback((nodes, text) => {
    if (!text) return nodes;
    const lower = text.toLowerCase();
    const filter = (list) => {
      const result = [];
      for (const node of list) {
        if (node.type === 'directory') {
          const filteredChildren = filter(node.children || []);
          if (filteredChildren.length > 0 || node.name.toLowerCase().includes(lower)) {
            result.push({ ...node, children: filteredChildren });
          }
        } else {
          if (node.name.toLowerCase().includes(lower) || node.path.toLowerCase().includes(lower)) {
            result.push(node);
          }
        }
      }
      return result;
    };
    return filter(nodes);
  }, []);

  const filteredTree = useMemo(() => filterTree(treeData, searchText), [treeData, searchText, filterTree]);

  // ---- Line numbers ----
  const lineNumbers = useMemo(() => {
    if (!fileContent) return [];
    return fileContent.split('\n').map((_, i) => i + 1);
  }, [fileContent]);

  // ---- Breadcrumb ----
  const breadcrumb = useMemo(() => {
    if (!activeFile) return [];
    return activeFile.path.split('/');
  }, [activeFile]);

  // ---- Render ----
  if (loading) return <PageLoading tip="加载代码库..." />;

  if (error) {
    return (
      <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description={error}>
          <Button type="primary" onClick={() => navigate(-1)}>返回</Button>
        </Empty>
      </div>
    );
  }

  return (
    <div className="cb-page">
      {/* Header */}
      <header className="cb-header">
        <div className="cb-header-left">
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>返回</Button>
          <CodeOutlined style={{ fontSize: 18, color: '#3b82f6', marginLeft: 8 }} />
          <span className="cb-project-name">{task?.projectName || '代码库'}</span>
          {breadcrumb.length > 0 && (
            <span className="cb-breadcrumb">
              {breadcrumb.map((seg, i) => (
                <span key={i}>
                  <span className="cb-breadcrumb-sep">/</span>
                  <span className={i === breadcrumb.length - 1 ? 'cb-breadcrumb-active' : ''}>{seg}</span>
                </span>
              ))}
            </span>
          )}
        </div>
      </header>

      {/* Main */}
      <div className="cb-main">
        {/* Left: File Tree */}
        <aside className="cb-sidebar">
          <div className="cb-search-box">
            <Input
              prefix={<SearchOutlined style={{ color: '#9ca3af' }} />}
              placeholder="搜索文件..."
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
              allowClear
              size="small"
            />
          </div>
          <div className="cb-tree">
            {filteredTree.length > 0 ? (
              filteredTree.map((node) => (
                <FileTreeItem
                  key={node.path}
                  node={node}
                  depth={0}
                  expandedDirs={expandedDirs}
                  onToggleDir={handleToggleDir}
                  onSelectFile={handleSelectFile}
                  activeFilePath={activeFile?.path}
                />
              ))
            ) : (
              <div className="cb-tree-empty">
                <Empty description="没有文件" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              </div>
            )}
          </div>
        </aside>

        {/* Center: Code Viewer */}
        <main className="cb-content" ref={codeContainerRef} onMouseUp={handleMouseUp}>
          {!activeFile ? (
            <div className="cb-content-empty">
              <Empty description="选择左侧文件查看代码" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          ) : fileLoading ? (
            <div className="cb-content-loading">
              <Spin><div style={{ padding: 12, color: 'rgba(0,0,0,0.45)' }}>加载中...</div></Spin>
            </div>
          ) : (
            <div className="cb-code-wrapper">
              <div className="cb-code-header">
                <FileOutlined style={{ color: getFileIcon(activeFile.name), marginRight: 6 }} />
                <span className="cb-code-filename">{activeFile.name}</span>
                {activeFile.size != null && (
                  <span className="cb-code-filesize">{formatFileSize(activeFile.size)}</span>
                )}
              </div>
              <div className="cb-code-body">
                <div className="cb-line-numbers">
                  {lineNumbers.map((n) => (
                    <div key={n} className="cb-line-num">{n}</div>
                  ))}
                </div>
                <pre className="cb-code-pre">
                  <code
                    ref={codeRef}
                    className={`language-${getLanguageFromPath(activeFile.path)}`}
                  >
                    {fileContent}
                  </code>
                </pre>
              </div>
            </div>
          )}

          {/* Selection toolbar */}
          {selectionToolbar && (
            <div
              className="cb-selection-toolbar"
              style={{
                left: selectionToolbar.x,
                top: selectionToolbar.y,
              }}
            >
              <button className="cb-toolbar-btn" onClick={handleAskClick}>
                <QuestionCircleOutlined />
                <span>提问</span>
              </button>
            </div>
          )}
        </main>
      </div>

      {/* Ask AI Modal */}
      <Modal
        title={
          <span>
            <QuestionCircleOutlined style={{ color: '#3b82f6', marginRight: 8 }} />
            Ask AI
          </span>
        }
        open={askAiVisible}
        onCancel={() => { setAskAiVisible(false); setAskAiAnswer(''); }}
        footer={null}
        width={600}
        destroyOnHidden
      >
        <div className="cb-ask-modal">
          <div className="cb-ask-code-preview">
            <div className="cb-ask-code-label">选中的代码：</div>
            <pre className="cb-ask-code-block">
              <code>{askAiSelectedCode}</code>
            </pre>
          </div>
          <TextArea
            placeholder="在这里输入你的问题..."
            value={askAiQuestion}
            onChange={(e) => setAskAiQuestion(e.target.value)}
            rows={3}
            disabled={askAiLoading}
            onPressEnter={(e) => {
              if (!e.shiftKey) { e.preventDefault(); handleAskAi(); }
            }}
          />
          <div className="cb-ask-actions">
            <Button
              type="primary"
              onClick={handleAskAi}
              loading={askAiLoading}
              disabled={!askAiQuestion.trim()}
            >
              Ask AI
            </Button>
          </div>
          {askAiAnswer && (
            <div className="cb-ask-answer">
              <div className="cb-ask-answer-label">AI 回答：</div>
              <div className="cb-ask-answer-content">{askAiAnswer}</div>
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
};

export default CodeBrowser;
