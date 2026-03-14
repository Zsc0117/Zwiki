import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  App,
  Button,
  Alert,
  Empty,
  Popconfirm,
  Modal,
  Form,
  Input,
  Switch,
  Typography,
  Tag,
  Tooltip,
  Descriptions,
  Spin
} from 'antd';
import { 
  DownOutlined,
  DownloadOutlined,
  LinkOutlined,
  HistoryOutlined,
  FolderOutlined,
  FileOutlined,
  RightOutlined,
  CodeOutlined,
  HighlightOutlined,
  EditOutlined,
  QuestionCircleOutlined,
  ShareAltOutlined
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import 'highlight.js/styles/github-dark.css';
import { TaskApi } from '../api/task';
import { WikiWebhookApi } from '../api/wikiWebhook';
import { formatDateTime } from '../utils/dateFormat';
import MermaidChart from '../components/MermaidChart';
import ChatMarkdown from '../components/ChatMarkdown';
import DocChangeModal from '../components/DocChangeModal';
import useSavedDiagrams from '../hooks/useSavedDiagrams';
import hljs from 'highlight.js';

import { extractMediaUrls, extractDrawioUrls, removeDrawioUrlsFromText, removeMediaUrlsFromText, proxyMediaUrlsToMinio, normalizeAiTextForDisplay } from '../components/formatAiResponse';
import { RepoApi } from '../api/repo';
import { ChatHistoryApi } from '../api/chatHistory';
import { useAuth } from '../auth/AuthContext';
import { readSseResponse } from '../utils/sseStream';

const RepoDetail = () => {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { taskId } = useParams();
  const { me } = useAuth();
  const userId = me?.userId;
  const [previewUrl, setPreviewUrl] = useState(null);

  const [task, setTask] = useState(null);
  const [catalogueTree, setCatalogueTree] = useState([]);
  const [selectedContent, setSelectedContent] = useState('');
  const [selectedTitle, setSelectedTitle] = useState('');
  const [error, setError] = useState('');
  const [expandedKeys, setExpandedKeys] = useState([]);
  const [activeKey, setActiveKey] = useState('');
  const [showTocDropdown, setShowTocDropdown] = useState(false);
  const [showAIPanel, setShowAIPanel] = useState(false);
  const [collapsedRootKeys, setCollapsedRootKeys] = useState([]);
  const [webhookModalOpen, setWebhookModalOpen] = useState(false);
  const [webhookConfig, setWebhookConfig] = useState(null);
  const [webhookLoading, setWebhookLoading] = useState(false);
  const [webhookForm] = Form.useForm();
  const [docChangeModalOpen, setDocChangeModalOpen] = useState(false);

  const [fileTree, setFileTree] = useState([]);
  const [fileTreeLoading, setFileTreeLoading] = useState(false);
  const [fileTreeExpanded, setFileTreeExpanded] = useState({});
  const [viewingCode, setViewingCode] = useState(false);
  const [codeContent, setCodeContent] = useState('');
  const [codeFilePath, setCodeFilePath] = useState('');
  const [codeFileName, setCodeFileName] = useState('');
  const [codeLoading, setCodeLoading] = useState(false);
  const [codeTreeCollapsed, setCodeTreeCollapsed] = useState(false);
  const [navCollapsed, setNavCollapsed] = useState(false);
  const [selectionToolbar, setSelectionToolbar] = useState(null);
  const [selectedCodeText, setSelectedCodeText] = useState('');
  const [askCodeModalOpen, setAskCodeModalOpen] = useState(false);
  const [askCodeQuestion, setAskCodeQuestion] = useState('');

  // ======== CodeLens: symbol annotations & Find Usages ========
  const [symbolAnnotations, setSymbolAnnotations] = useState([]); // [{symbol, line, usageCount, kind}]
  const [symbolRefsPopup, setSymbolRefsPopup] = useState(null); // {symbol, kind, references:[], loading}
  const [highlightLine, setHighlightLine] = useState(null); // line number to flash-highlight

  const contentRef = useRef(null);
  const messagesRef = useRef(null);
  const inputRef = useRef(null);
  const headingIdsRef = useRef([]);
  const headingIndexRef = useRef(0);
  const lastContentRef = useRef(null);

  const [aiQuestion, setAiQuestion] = useState('');
  const [aiMessages, setAiMessages] = useState([]);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiHistoryLoaded, setAiHistoryLoaded] = useState(false);

  const {
    hydrateSavedDrawioDiagrams,
    getSavedDiagramByUrl,
    autoSaveDrawioDiagrams,
  } = useSavedDiagrams({
    taskId,
    defaultBaseName: 'AI生成图表',
    logPrefix: 'RepoDetail',
  });

  useEffect(() => {
    if (!showAIPanel) return;
    hydrateSavedDrawioDiagrams();
  }, [showAIPanel, hydrateSavedDrawioDiagrams]);

  const persistAiMessages = async (userText, botText) => {
    if (!userId || !taskId) return;
    try {
      await ChatHistoryApi.saveBatch([
        { userId, taskId, role: 'user', content: userText },
        { userId, taskId, role: 'assistant', content: botText },
      ]);
    } catch (e) {
      console.warn('[RepoDetail] save chat history failed:', e);
    }
  };

  useEffect(() => {
    if (!showAIPanel || !userId || !taskId || aiHistoryLoaded) return;
    (async () => {
      try {
        const res = await ChatHistoryApi.getHistory(userId, taskId);
        if (res?.code === 200 && Array.isArray(res.data) && res.data.length > 0) {
          const loaded = res.data.map((m, idx) => ({
            id: m.id || idx,
            role: m.role === 'user' ? 'user' : 'bot',
            content: m.content || '',
          }));
          setAiMessages(loaded);
        }
      } catch (e) {
        console.warn('[RepoDetail] load AI history failed:', e);
      } finally {
        setAiHistoryLoaded(true);
      }
    })();
  }, [showAIPanel, userId, taskId, aiHistoryLoaded]);

  useEffect(() => {
    document.body.classList.add('zwiki-docs-body');
    return () => {
      document.body.classList.remove('zwiki-docs-body');
    };
  }, []);

  // 查找第一个有内容的叶子节点
  const findFirstLeafWithContent = useCallback((data) => {
    for (const item of data) {
      if (item.children && item.children.length > 0) {
        const found = findFirstLeafWithContent(item.children);
        if (found) return found;
      } else if (item.content) {
        return item;
      }
    }
    return null;
  }, []);

  const fetchData = useCallback(async () => {
    try {
      const [taskResponse, treeResponse] = await Promise.all([
        TaskApi.getTaskDetail(taskId),
        TaskApi.getCatalogueTree(taskId)
      ]);

      if (taskResponse.code === 200) {
        setTask(taskResponse.data);
      } else {
        setError(taskResponse.msg || '获取仓库详情失败');
        return;
      }

      if (treeResponse.code === 200) {
        const treeData = buildTreeData(treeResponse.data);
        setCatalogueTree(treeData);

        const parentKeys = collectParentKeys(treeData);
        setExpandedKeys(parentKeys);

        const firstLeaf = findFirstLeafWithContent(treeResponse.data);
        if (firstLeaf) {
          setSelectedContent(firstLeaf.content || '');
          setSelectedTitle(firstLeaf.name || firstLeaf.title || '');
          setActiveKey(firstLeaf.catalogueId || '');
        }
      }

      setError('');
    } catch (error) {
      console.error('获取数据失败:', error);
      setError('获取数据失败');
    }
  }, [taskId, findFirstLeafWithContent]);

  // 获取任务详情和目录树
  useEffect(() => {
    if (taskId) {
      fetchData();
    } else {
      setError('无效的仓库ID');
    }
  }, [taskId, fetchData]);

  const slugify = useCallback((text) => {
    return String(text || '')
      .trim()
      .toLowerCase()
      .replace(/\s+/g, '-')
      .replace(/[^\w-]/g, '');
  }, []);

  const anchors = React.useMemo(() => {
    if (!selectedContent) return [];

    const headings = [];
    const lines = selectedContent.split('\n');
    const seen = new Map();

    const makeUniqueId = (title, index) => {
      const baseSlug = slugify(title);
      const encoded = encodeURIComponent(String(title || '').trim())
        .replace(/%/g, '')
        .toLowerCase();
      const base = baseSlug || (encoded ? `h-${encoded}` : `heading-${index}`);

      const count = seen.get(base) || 0;
      seen.set(base, count + 1);
      return count === 0 ? base : `${base}-${count}`;
    };

    let idx = 0;
    lines.forEach((line) => {
      const match = line.match(/^(#{1,3})\s+(.+)/);
      if (match) {
        const level = match[1].length;
        const title = match[2].trim();
        const id = makeUniqueId(title, idx);
        headings.push({ id, title, level });
        idx += 1;
      }
    });

    return headings;
  }, [selectedContent, slugify]);

  if (lastContentRef.current !== selectedContent) {
    lastContentRef.current = selectedContent;
    headingIndexRef.current = 0;
    headingIdsRef.current = anchors.map((a) => a.id);
  }

  headingIndexRef.current = 0;

  const collectParentKeys = (nodes) => {
    const keys = [];
    const walk = (arr) => {
      for (const n of arr) {
        if (n.children && n.children.length > 0) {
          keys.push(n.key);
          walk(n.children);
        }
      }
    };
    walk(nodes);
    return keys;
  };

  // 构建树形数据结构（用于自定义左侧导航）
  const buildTreeData = (data) => {
    const buildNode = (item, level = 0) => ({
      title: item.name,
      key: item.catalogueId,
      content: item.content,
      name: item.name,
      level: level,
      isLeaf: !item.children || item.children.length === 0,
      isParent: item.children && item.children.length > 0,
      children: item.children ? item.children.map(child => buildNode(child, level + 1)) : []
    });

    return data.map(item => buildNode(item, 0));
  };

  // 查找节点内容
  const findNodeContent = (treeData, key) => {
    for (const node of treeData) {
      if (node.key === key) {
        return { content: node.content, title: node.title, isParent: node.isParent };
      }
      if (node.children && node.children.length > 0) {
        const found = findNodeContent(node.children, key);
        if (found) return found;
      }
    }
    return null;
  };

  // 处理节点选择
  const handleSelectNode = (key) => {
    const nodeData = findNodeContent(catalogueTree, key);
    if (nodeData && nodeData.content) {
      setViewingCode(false);
      setActiveKey(key);
      setSelectedContent(nodeData.content || '暂无内容');
      setSelectedTitle(nodeData.title || '');
      if (contentRef.current) {
        contentRef.current.scrollTop = 0;
      }
    }
  };

  // 处理节点展开收缩
  const toggleNodeExpand = (key) => {
    setExpandedKeys((prev) => {
      if (prev.includes(key)) {
        return prev.filter((k) => k !== key);
      }
      return [...prev, key];
    });
  };

  const toggleRootCollapse = (key) => {
    setCollapsedRootKeys((prev) => {
      if (prev.includes(key)) {
        return prev.filter((k) => k !== key);
      }
      return [...prev, key];
    });
  };

  const renderNavNode = (node, depth = 0) => {
    const hasChildren = node.children && node.children.length > 0;
    const isExpanded = expandedKeys.includes(node.key);
    const isActive = activeKey === node.key;

    if (hasChildren) {
      return (
        <div key={node.key} className="nav-item-group">
          <div
            className={`nav-group-item ${isActive ? 'active' : ''} depth-${depth}`}
            onClick={() => {
              if (node.content) {
                handleSelectNode(node.key);
                return;
              }
              toggleNodeExpand(node.key);
            }}
          >
            <span className="nav-item-title">{node.title}</span>
            <DownOutlined
              className={`expand-icon ${isExpanded ? 'expanded' : ''}`}
              onClick={(e) => {
                e.stopPropagation();
                toggleNodeExpand(node.key);
              }}
            />
          </div>
          {isExpanded && (
            <div className="nav-children">
              {node.children.map((child) => renderNavNode(child, depth + 1))}
            </div>
          )}
        </div>
      );
    }

    return (
      <div
        key={node.key}
        className={`nav-leaf-item ${isActive ? 'active' : ''} depth-${depth}`}
        onClick={() => handleSelectNode(node.key)}
      >
        <span className="nav-item-title">{node.title}</span>
      </div>
    );
  };

  // ======== 代码库文件树 ========
  const fetchFileTree = useCallback(async () => {
    if (!taskId) return;
    setFileTreeLoading(true);
    try {
      const res = await RepoApi.getFileTree(taskId);
      if (res?.code === 200) {
        setFileTree(res.data || []);
      }
    } catch (e) {
      console.error('获取代码库文件树失败:', e);
    } finally {
      setFileTreeLoading(false);
    }
  }, [taskId]);

  useEffect(() => {
    if (taskId) {
      fetchFileTree();
    }
  }, [taskId, fetchFileTree]);

  const toggleFileTreeNode = (path) => {
    setFileTreeExpanded((prev) => ({ ...prev, [path]: !prev[path] }));
  };

  const getFileIcon = (name) => {
    const ext = name.lastIndexOf('.') > 0 ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : '';
    const baseName = name.replace(/\.[^.]+$/, '');
    if (ext === 'java') {
      if (/^I[A-Z]/.test(baseName) || baseName.endsWith('Service') || baseName.endsWith('Repository') || baseName.endsWith('Mapper') || baseName.endsWith('Dao') || baseName.endsWith('Client') || baseName.endsWith('Listener') || baseName.endsWith('Handler') || baseName.endsWith('Callback') || baseName.endsWith('Provider') || baseName.endsWith('Factory')) {
        return <span className="file-tree-icon file-icon-interface" title="Interface">I</span>;
      }
      if (baseName.endsWith('Enum') || /^[A-Z][a-z]+(?:Type|Status|Level|Mode|Kind)$/.test(baseName)) {
        return <span className="file-tree-icon file-icon-enum" title="Enum">E</span>;
      }
      return <span className="file-tree-icon file-icon-class" title="Class">C</span>;
    }
    if (ext === 'xml' || ext === 'html' || ext === 'svg') return <span className="file-tree-icon file-icon-markup" title="Markup">&lt;/&gt;</span>;
    if (ext === 'yml' || ext === 'yaml' || ext === 'properties' || ext === 'conf' || ext === 'ini' || ext === 'env' || ext === 'toml') return <span className="file-tree-icon file-icon-config" title="Config">{'\u2699'}</span>;
    if (ext === 'json') return <span className="file-tree-icon file-icon-json" title="JSON">{'{}'}</span>;
    if (ext === 'js' || ext === 'jsx' || ext === 'mjs') return <span className="file-tree-icon file-icon-js" title="JavaScript">JS</span>;
    if (ext === 'ts' || ext === 'tsx') return <span className="file-tree-icon file-icon-ts" title="TypeScript">TS</span>;
    if (ext === 'py') return <span className="file-tree-icon file-icon-python" title="Python">Py</span>;
    if (ext === 'go') return <span className="file-tree-icon file-icon-go" title="Go">Go</span>;
    if (ext === 'css' || ext === 'scss' || ext === 'less') return <span className="file-tree-icon file-icon-css" title="Style">#</span>;
    if (ext === 'md') return <span className="file-tree-icon file-icon-md" title="Markdown">M</span>;
    if (ext === 'sql') return <span className="file-tree-icon file-icon-sql" title="SQL">SQ</span>;
    if (ext === 'sh' || ext === 'bash' || ext === 'bat' || ext === 'cmd') return <span className="file-tree-icon file-icon-shell" title="Script">$</span>;
    if (ext === 'kt' || ext === 'kts') return <span className="file-tree-icon file-icon-kotlin" title="Kotlin">Kt</span>;
    if (name === 'Dockerfile' || name.startsWith('docker-compose')) return <span className="file-tree-icon file-icon-docker" title="Docker">{'\uD83D\uDC33'}</span>;
    if (name === '.gitignore' || name === '.gitattributes') return <span className="file-tree-icon file-icon-git" title="Git">G</span>;
    return <FileOutlined className="file-tree-icon file" />;
  };

  const renderFileTreeNode = (node, depth = 0) => {
    const isDir = node.type === 'directory';
    const isExpanded = fileTreeExpanded[node.path];
    const isActive = viewingCode && codeFilePath === node.path;

    return (
      <div key={node.path}>
        <div
          className={`sidebar-file-item ${isActive ? 'active' : ''}`}
          style={{ paddingLeft: 12 + depth * 16 }}
          onClick={() => handleFileClick(node)}
        >
          {isDir ? (
            <RightOutlined className={`file-tree-arrow ${isExpanded ? 'expanded' : ''}`} />
          ) : (
            <span className="file-tree-arrow-placeholder" />
          )}
          {isDir ? (
            <FolderOutlined className="file-tree-icon folder" />
          ) : (
            getFileIcon(node.name)
          )}
          <span className="file-tree-name">{node.name}</span>
        </div>
        {isDir && isExpanded && node.children && (
          <div>
            {node.children.map((child) => renderFileTreeNode(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  const handleFileClick = useCallback(async (node, targetLine) => {
    if (node.type === 'directory') {
      toggleFileTreeNode(node.path);
      return;
    }
    setSymbolAnnotations([]);
    setSymbolRefsPopup(null);
    setHighlightLine(null);
    setCodeFilePath(node.path);
    setCodeFileName(node.name);
    setCodeLoading(true);
    setViewingCode(true);
    if (contentRef.current) {
      contentRef.current.scrollTop = 0;
    }
    try {
      const res = await RepoApi.getFileContent(taskId, node.path);
      if (res?.code === 200) {
        setCodeContent(res.data || '');
        // Async: fetch symbol annotations (CodeLens)
        RepoApi.getSymbolAnnotations(taskId, node.path).then((annRes) => {
          if (annRes?.code === 200 && annRes.data) {
            setSymbolAnnotations(annRes.data);
          }
        }).catch(() => {});
      } else {
        setCodeContent(`// 加载失败: ${res?.msg || '未知错误'}`);
      }
    } catch (e) {
      setCodeContent(`// 加载失败: ${e?.message || '网络错误'}`);
    } finally {
      setCodeLoading(false);
      // Scroll to target line after rendering
      if (targetLine) {
        setTimeout(() => scrollToLine(targetLine), 150);
      }
    }
  }, [taskId]);

  const scrollToLine = (lineNumber) => {
    setHighlightLine(lineNumber);
    const container = contentRef.current;
    if (!container) return;
    // Each line height is ~20.8px (13px font * 1.6 line-height)
    const lineHeight = 20.8;
    const targetTop = (lineNumber - 1) * lineHeight;
    container.scrollTo({ top: Math.max(targetTop - 100, 0), behavior: 'smooth' });
    // Clear highlight after animation
    setTimeout(() => setHighlightLine(null), 2500);
  };

  const handleCodeLensBadgeClick = useCallback(async (annotation) => {
    if (annotation.usageCount === 0) return;
    setSymbolRefsPopup({ symbol: annotation.symbol, kind: annotation.kind, references: [], loading: true });
    try {
      const res = await RepoApi.getSymbolReferences(taskId, annotation.symbol, codeFilePath, annotation.line);
      if (res?.code === 200) {
        const refs = res.data || [];
        if (refs.length === 1) {
          // Single reference — navigate directly
          setSymbolRefsPopup(null);
          const ref = refs[0];
          handleFileClick({ type: 'file', path: ref.filePath, name: ref.filePath.split('/').pop() }, ref.lineNumber);
        } else {
          setSymbolRefsPopup({ symbol: annotation.symbol, kind: annotation.kind, references: refs, loading: false });
        }
      } else {
        setSymbolRefsPopup(null);
        message.error('搜索引用失败');
      }
    } catch {
      setSymbolRefsPopup(null);
      message.error('搜索引用失败');
    }
  }, [taskId, codeFilePath, handleFileClick]);

  const handleRefClick = (ref) => {
    setSymbolRefsPopup(null);
    handleFileClick({ type: 'file', path: ref.filePath, name: ref.filePath.split('/').pop() }, ref.lineNumber);
  };

  const getFileExtension = (filename) => {
    const dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(dot + 1).toLowerCase() : '';
  };

  const highlightCode = (code, filename) => {
    const ext = getFileExtension(filename);
    const langMap = {
      js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
      py: 'python', rb: 'ruby', java: 'java', kt: 'kotlin', go: 'go',
      rs: 'rust', cpp: 'cpp', c: 'c', h: 'c', cs: 'csharp',
      html: 'html', css: 'css', scss: 'scss', less: 'less',
      json: 'json', xml: 'xml', yaml: 'yaml', yml: 'yaml',
      md: 'markdown', sql: 'sql', sh: 'bash', bash: 'bash',
      dockerfile: 'dockerfile', vue: 'xml', svelte: 'xml',
    };
    const lang = langMap[ext];
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value;
      } catch { /* fallback */ }
    }
    try {
      return hljs.highlightAuto(code).value;
    } catch {
      return code.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }
  };

  // Helper: split highlighted HTML after first occurrence of ( or { in visible text
  const splitHtmlAtChar = (html, chars) => {
    const parts = html.split(/(<[^>]*>)/);
    let pos = 0;
    for (const part of parts) {
      if (part.startsWith('<')) { pos += part.length; continue; }
      for (let j = 0; j < part.length; j++) {
        if (chars.includes(part[j])) {
          const splitPos = pos + j + 1;
          return [html.slice(0, splitPos), html.slice(splitPos)];
        }
      }
      pos += part.length;
    }
    return null;
  };

  // Helper: wrap first whole-word occurrence of symbol in HTML with interactive span
  const wrapSymbolInHtml = (html, symbol) => {
    const parts = html.split(/(<[^>]*>)/);
    const escaped = symbol.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(`\\b(${escaped})\\b`);
    let found = false;
    const result = parts.map(part => {
      if (found || part.startsWith('<')) return part;
      if (regex.test(part)) {
        found = true;
        return part.replace(regex, `<span class="codelens-symbol" data-symbol="${symbol}">$1</span>`);
      }
      return part;
    });
    return result.join('');
  };

  // Event delegation: handle clicks on .codelens-badge and Ctrl+Click on .codelens-symbol
  const handleCodeContainerClick = (e) => {
    const badge = e.target.closest('.codelens-badge');
    if (badge) {
      e.stopPropagation();
      const symbol = badge.dataset.symbol;
      const ann = symbolAnnotations.find(a => a.symbol === symbol);
      if (ann) handleCodeLensBadgeClick(ann);
      return;
    }
    if (e.ctrlKey || e.metaKey) {
      const symbolEl = e.target.closest('.codelens-symbol');
      if (symbolEl) {
        e.preventDefault();
        const symbol = symbolEl.dataset.symbol;
        const ann = symbolAnnotations.find(a => a.symbol === symbol);
        if (ann) handleCodeLensBadgeClick(ann);
      }
    }
  };

  // ======== 代码选中工具栏 ========
  const handleCodeMouseUp = (e) => {
    const selection = window.getSelection();
    const text = selection?.toString()?.trim();
    if (!text) {
      setSelectionToolbar(null);
      return;
    }
    setSelectedCodeText(text);
    const range = selection.getRangeAt(0);
    const rect = range.getBoundingClientRect();
    const containerRect = contentRef.current?.getBoundingClientRect() || { top: 0, left: 0 };
    setSelectionToolbar({
      top: rect.top - containerRect.top + contentRef.current.scrollTop - 44,
      left: rect.left - containerRect.left + rect.width / 2,
    });
  };

  const handleAskCodeSubmit = () => {
    const question = askCodeQuestion.trim();
    if (!question && !selectedCodeText) return;
    const fullQuestion = selectedCodeText
      ? `关于以下代码：\n\`\`\`\n${selectedCodeText}\n\`\`\`\n\n${question || '请解释这段代码'}`
      : question;
    setAskCodeModalOpen(false);
    setAskCodeQuestion('');
    setSelectionToolbar(null);
    setSelectedCodeText('');
    setShowAIPanel(true);
    setAiQuestion(fullQuestion);
    setTimeout(() => {
      setAiQuestion('');
      setAiLoading(true);
      const userMsg = { id: Date.now(), role: 'user', content: fullQuestion };
      const botId = Date.now() + 1;
      setAiMessages((prev) => ([...prev, userMsg, { id: botId, role: 'bot', content: '' }]));

      const recentHistory = aiMessages.slice(-10).map(m => ({
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content,
      }));

      (async () => {
        try {
          const response = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({
              query: fullQuestion,
              intent: 'code_qa',
              ...(taskId ? { taskId } : {}),
              ...(userId ? { userId } : {}),
              history: recentHistory,
            }),
          });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);

          const { content: accumulated, hasErrorEvent, errorMessage } = await readSseResponse(response, {
            onMessage: (content) => {
              setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content } : m)));
            },
            onError: (msg) => {
              setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: msg } : m)));
            },
          });

          const botContent = hasErrorEvent
            ? (errorMessage || '抱歉，服务出现问题。')
            : (accumulated || '抱歉，我现在无法回答您的问题。');
          if (!accumulated && !hasErrorEvent) {
            setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: botContent } : m)));
          }
          // Proxy media URLs to MinIO before persisting
          let contentToSave = botContent;
          try {
            const proxied = await proxyMediaUrlsToMinio(botContent);
            if (proxied !== botContent) {
              contentToSave = proxied;
              setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: proxied } : m)));
            }
          } catch (e) {
            console.warn('[RepoDetail] media proxy before persist failed:', e);
          }
          persistAiMessages(fullQuestion, contentToSave);
          const { firstDiagram, newCount } = await autoSaveDrawioDiagrams(contentToSave, fullQuestion);
          if (newCount > 0) {
            message.success('图表已自动保存到架构绘图');
          }
          if (firstDiagram?.diagramId) {
            navigate(`/repo/${taskId}/diagrams?open=${firstDiagram.diagramId}`);
          }
        } catch (e) {
          setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: '抱歉，处理您的问题时出现了错误。' } : m)));
        } finally {
          setAiLoading(false);
        }
      })();
    }, 100);
  };

  useEffect(() => {
    const handleClick = (e) => {
      if (selectionToolbar && !e.target.closest('.code-selection-toolbar') && !e.target.closest('.ask-code-modal-inner')) {
        const sel = window.getSelection()?.toString()?.trim();
        if (!sel) {
          setSelectionToolbar(null);
        }
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [selectionToolbar]);

  // ESC to close Find Usages popup
  useEffect(() => {
    if (!symbolRefsPopup) return;
    const handleEsc = (e) => {
      if (e.key === 'Escape') setSymbolRefsPopup(null);
    };
    document.addEventListener('keydown', handleEsc);
    return () => document.removeEventListener('keydown', handleEsc);
  }, [symbolRefsPopup]);

  // Ctrl+B to trigger Find Usages on hovered symbol
  useEffect(() => {
    if (!viewingCode || symbolAnnotations.length === 0) return;
    const handleCtrlB = (e) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 'b' || e.key === 'B')) {
        const hovered = document.querySelector('.codelens-symbol:hover');
        if (hovered) {
          e.preventDefault();
          const symbol = hovered.dataset.symbol;
          const ann = symbolAnnotations.find(a => a.symbol === symbol);
          if (ann) handleCodeLensBadgeClick(ann);
        }
      }
    };
    document.addEventListener('keydown', handleCtrlB);
    return () => document.removeEventListener('keydown', handleCtrlB);
  }, [viewingCode, symbolAnnotations, handleCodeLensBadgeClick]);

  const scrollToAnchor = (id) => {
    const element = document.getElementById(id);
    const container = contentRef.current;
    if (element && container) {
      const containerRect = container.getBoundingClientRect();
      const elRect = element.getBoundingClientRect();
      const top = elRect.top - containerRect.top + container.scrollTop;
      container.scrollTo({ top: Math.max(top - 12, 0), behavior: 'smooth' });
    } else if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    setShowTocDropdown(false);
  };

  // Webhook configuration helpers
  const gatewayUrl = process.env.REACT_APP_GATEWAY_URL || window.location.origin;
  const webhookUrl = `${gatewayUrl}/api/webhook/wiki/push`;

  const loadWebhookConfig = async () => {
    setWebhookLoading(true);
    try {
      const res = await WikiWebhookApi.getByTask(taskId);
      if (res?.code === 200) {
        setWebhookConfig(res.data);
        if (res.data) {
          webhookForm.setFieldsValue({
            webhookSecret: res.data.webhookSecret || '',
            enabled: res.data.enabled !== false,
          });
        } else {
          webhookForm.resetFields();
        }
      }
    } catch { /* ignore */ }
    finally { setWebhookLoading(false); }
  };

  const handleOpenWebhookModal = () => {
    setWebhookModalOpen(true);
    loadWebhookConfig();
  };

  const handleSaveWebhook = async () => {
    try {
      const values = await webhookForm.validateFields();
      setWebhookLoading(true);
      if (webhookConfig) {
        const res = await WikiWebhookApi.update(webhookConfig.id, values);
        if (res?.code === 200) {
          message.success('Webhook 配置已更新');
          setWebhookConfig(res.data);
        } else {
          message.error(res?.msg || '更新失败');
        }
      } else {
        const res = await WikiWebhookApi.create({ taskId, ...values });
        if (res?.code === 200) {
          message.success('Webhook 已启用');
          setWebhookConfig(res.data);
        } else {
          message.error(res?.msg || '创建失败');
        }
      }
    } catch (e) {
      if (e?.errorFields) return;
      message.error('保存失败');
    } finally {
      setWebhookLoading(false);
    }
  };

  const handleDeleteWebhook = async () => {
    if (!webhookConfig) return;
    try {
      setWebhookLoading(true);
      const res = await WikiWebhookApi.delete(webhookConfig.id);
      if (res?.code === 200) {
        message.success('Webhook 已删除');
        setWebhookConfig(null);
        webhookForm.resetFields();
      } else {
        message.error(res?.msg || '删除失败');
      }
    } catch { message.error('删除失败'); }
    finally { setWebhookLoading(false); }
  };

  const scrollMessagesToBottom = () => {
    const el = messagesRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  };

  useEffect(() => {
    scrollMessagesToBottom();
  }, [aiMessages]);

  useEffect(() => {
    if (showAIPanel) {
      setTimeout(() => {
        try {
          inputRef.current?.focus();
        } catch {
          // ignore
        }
      }, 0);
    } else {
      setAiLoading(false);
    }
  }, [showAIPanel]);

  const handleSendAI = async (directQuery) => {
    const question = String(directQuery || aiQuestion || '').trim();
    if (!question || aiLoading) return;

    setAiQuestion('');
    setAiLoading(true);

    const userMsg = { id: Date.now(), role: 'user', content: question };
    const botId = Date.now() + 1;
    setAiMessages((prev) => ([...prev, userMsg, { id: botId, role: 'bot', content: '' }]));

    // Build conversation history for context continuity (last 10 messages)
    const recentHistory = aiMessages.slice(-10).map(m => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content,
    }));

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          query: question,
          intent: 'project',
          ...(taskId ? { taskId } : {}),
          ...(userId ? { userId } : {}),
          history: recentHistory,
        }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const { content: accumulated, hasErrorEvent, errorMessage } = await readSseResponse(response, {
        onMessage: (content) => {
          setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content } : m)));
        },
        onError: (msg) => {
          setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: msg } : m)));
        },
      });

      const botContent = hasErrorEvent
        ? (errorMessage || '抱歉，服务出现问题。')
        : (accumulated || '抱歉，我现在无法回答您的问题。');
      if (!accumulated && !hasErrorEvent) {
        setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: botContent } : m)));
      }
      // Proxy media URLs to MinIO before persisting
      let contentToSave = botContent;
      try {
        const proxied = await proxyMediaUrlsToMinio(botContent);
        if (proxied !== botContent) {
          contentToSave = proxied;
          setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: proxied } : m)));
        }
      } catch (e) {
        console.warn('[RepoDetail] media proxy before persist failed:', e);
      }
      persistAiMessages(question, contentToSave);
      const { firstDiagram, newCount } = await autoSaveDrawioDiagrams(contentToSave, question);
      if (newCount > 0) {
        message.success('图表已自动保存到架构绘图');
      }
      if (firstDiagram?.diagramId) {
        navigate(`/repo/${taskId}/diagrams?open=${firstDiagram.diagramId}`);
      }
    } catch (e) {
      setAiMessages((prev) => prev.map((m) => (m.id === botId ? { ...m, content: '抱歉，处理您的问题时出现了错误。' } : m)));
    } finally {
      setAiLoading(false);
    }
  };

  // 渲染加载状态
  if (error) {
    return (
      <div style={{ 
        height: '100vh', 
        display: 'flex', 
        alignItems: 'center', 
        justifyContent: 'center',
        padding: '20px' 
      }}>
        <Alert
          message="错误"
          description={error}
          type="error"
          showIcon
          action={
            <Button type="primary" onClick={() => navigate('/')}>
              返回首页
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="zwiki-docs-page">
      <header className="docs-header">
        <div className="header-left">
          <Button onClick={() => navigate('/')}>返回首页</Button>
          <span className="last-indexed">
            上次索引: {formatDateTime(task?.updateTime, 'YYYY-MM-DD HH:mm') || '-'}
          </span>
        </div>
        <div className="header-right">
          <Tooltip title="配置 GitHub Push Webhook 自动更新文档">
            <Button icon={<LinkOutlined />} onClick={handleOpenWebhookModal}>
              Webhook
            </Button>
          </Tooltip>
          <Tooltip title="查看文档变更历史">
            <Button icon={<HistoryOutlined />} onClick={() => setDocChangeModalOpen(true)}>
              变更历史
            </Button>
          </Tooltip>
          <Button
            icon={<DownloadOutlined />}
            onClick={() => {
              const link = document.createElement('a');
              link.href = `/api/task/catalogue/export?taskId=${taskId}`;
              link.click();
            }}
          >
            导出 Markdown
          </Button>
          <button className="ask-ai-btn" onClick={() => setShowAIPanel(true)}>
            <span className="sparkle-icon">✨</span>
            Ask AI
          </button>
        </div>
      </header>

      <div className="docs-main">
        <aside className={`docs-nav ${navCollapsed ? 'nav-collapsed' : ''}`}>
          <div className="zwiki-doc-nav">
            <div className="nav-collapse-toggle" onClick={() => setNavCollapsed(!navCollapsed)}>
              {navCollapsed ? <span>展开</span> : <span>收起目录</span>}
            </div>
            <div className="nav-tree">
              {catalogueTree.length > 0 ? (
                <div className="nav-sections">
                  {catalogueTree.map((group) => {
                    const isRootCollapsed = collapsedRootKeys.includes(group.key);
                    return (
                    <div key={group.key} className="nav-root">
                      {(group.children || []).length > 0 ? (
                        <>
                          <div 
                            className="nav-root-title"
                            onClick={() => toggleRootCollapse(group.key)}
                          >
                            <span className="nav-root-title-text">{group.title}</span>
                            <DownOutlined
                              className={`root-expand-icon ${isRootCollapsed ? '' : 'expanded'}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                toggleRootCollapse(group.key);
                              }}
                            />
                          </div>
                          {!isRootCollapsed && (
                            <div className="nav-root-list">
                              {(group.children || []).map((node) => renderNavNode(node, 0))}
                            </div>
                          )}
                        </>
                      ) : (
                        <div className="nav-root-list">{renderNavNode(group, 0)}</div>
                      )}
                    </div>
                    );
                  })}
                </div>
              ) : (
                <div className="nav-empty">
                  <Empty description="暂无文档结构" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                </div>
              )}
            </div>
          </div>
        </aside>

        <main className="docs-content" ref={contentRef}>
          <div className={`zwiki-doc-content ${viewingCode ? 'viewing-code' : ''}`}>
            {viewingCode ? (
              <>
                <div className="content-header code-content-header">
                  <div className="header-main" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <Button size="small" onClick={() => setViewingCode(false)}>返回文档</Button>
                    <span className="code-view-path">{codeFilePath}</span>
                  </div>
                </div>
                <div className="content-body code-content-body" onMouseUp={handleCodeMouseUp}>
                  {codeLoading ? (
                    <div style={{ display: 'flex', justifyContent: 'center', padding: 60 }}>
                      <Spin><div style={{ padding: 12, color: 'rgba(0,0,0,0.45)' }}>加载中...</div></Spin>
                    </div>
                  ) : (
                    <div className="inline-code-viewer">
                      {(() => {
                        const lines = codeContent.split('\n');
                        const highlightedHtml = highlightCode(codeContent, codeFileName);
                        const highlightedLines = highlightedHtml.split('\n');
                        const annMap = {};
                        symbolAnnotations.forEach((a) => { annMap[a.line] = a; });
                        return (
                          <div className="inline-code-body">
                            <div className="inline-line-numbers">
                              {lines.map((_, i) => (
                                <div key={i} className="inline-line-num">{i + 1}</div>
                              ))}
                            </div>
                            <div className="inline-code-pre-wrapper" onClick={handleCodeContainerClick}>
                              <pre className="inline-code-pre">
                                <code>
                                  {lines.map((_, i) => {
                                    let lineHtml = highlightedLines[i] || '';
                                    const ann = annMap[i + 1];
                                    const isHighlighted = highlightLine === i + 1;
                                    if (ann && ann.usageCount > 0) {
                                      lineHtml = wrapSymbolInHtml(lineHtml || '\n', ann.symbol);
                                      const badgeHtml = `<span class="codelens-badge" data-symbol="${ann.symbol}" data-kind="${ann.kind}" title="${ann.symbol}: ${ann.usageCount} 个用法">${ann.usageCount} 个用法</span>`;
                                      if (ann.kind === 'FIELD') {
                                        lineHtml = lineHtml + ' ' + badgeHtml;
                                      } else {
                                        const split = splitHtmlAtChar(lineHtml, '{');
                                        if (split) {
                                          lineHtml = split[0] + ' ' + badgeHtml + split[1];
                                        } else {
                                          lineHtml = lineHtml + ' ' + badgeHtml;
                                        }
                                      }
                                    }
                                    return (
                                      <div
                                        key={i}
                                        className={`code-line${isHighlighted ? ' line-highlight-flash' : ''}`}
                                        dangerouslySetInnerHTML={{ __html: lineHtml || '\n' }}
                                      />
                                    );
                                  })}
                                </code>
                              </pre>
                            </div>
                          </div>
                        );
                      })()}
                      {symbolRefsPopup && (
                        <div className="symbol-usages-overlay" onClick={() => setSymbolRefsPopup(null)}>
                          <div className="symbol-usages-popup" onClick={(e) => e.stopPropagation()}>
                            <div className="symbol-usages-header">
                              <span className="symbol-usages-title">
                                <span className="symbol-usages-kind">{symbolRefsPopup.kind === 'CLASS' ? '类' : symbolRefsPopup.kind === 'FIELD' ? '字段' : '方法'}</span>
                                {' '}<strong>{symbolRefsPopup.symbol}</strong>
                                <span className="symbol-usages-count">{symbolRefsPopup.loading ? '...' : `${symbolRefsPopup.references.length} 个用法`}</span>
                              </span>
                              <button className="symbol-usages-close" onClick={() => setSymbolRefsPopup(null)}>×</button>
                            </div>
                            <div className="symbol-usages-list">
                              {symbolRefsPopup.loading ? (
                                <div style={{ textAlign: 'center', padding: 20 }}><Spin size="small" /></div>
                              ) : symbolRefsPopup.references.length === 0 ? (
                                <div style={{ textAlign: 'center', padding: 20, color: '#9ca3af' }}>未找到引用</div>
                              ) : (
                                symbolRefsPopup.references.map((ref, idx) => {
                                  const fileName = ref.filePath.split('/').pop();
                                  const escapedSymbol = symbolRefsPopup.symbol.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
                                  const parts = ref.lineContent.split(new RegExp(`(\\b${escapedSymbol}\\b)`, 'g'));
                                  return (
                                    <div
                                      key={idx}
                                      className="symbol-usages-item"
                                      onClick={() => handleRefClick(ref)}
                                    >
                                      <FileOutlined className="symbol-usages-file-icon" />
                                      <span className="symbol-usages-filename">{fileName}</span>
                                      <span className="symbol-usages-line-num">{ref.lineNumber}</span>
                                      <span className="symbol-usages-code">
                                        {parts.map((part, pi) =>
                                          part === symbolRefsPopup.symbol
                                            ? <strong key={pi} className="symbol-usages-highlight">{part}</strong>
                                            : <span key={pi}>{part}</span>
                                        )}
                                      </span>
                                    </div>
                                  );
                                })
                              )}
                            </div>
                            {!symbolRefsPopup.loading && symbolRefsPopup.references.length > 0 && (
                              <div className="symbol-usages-footer">
                                {codeFilePath}
                              </div>
                            )}
                          </div>
                        </div>
                      )}
                      {selectionToolbar && (
                        <div
                          className="code-selection-toolbar"
                          style={{ top: selectionToolbar.top, left: selectionToolbar.left }}
                        >
                          <button className="sel-toolbar-btn" onClick={() => { message.info('划线功能开发中'); }}>
                            <HighlightOutlined />
                            <span>划线</span>
                          </button>
                          <button className="sel-toolbar-btn" onClick={() => { message.info('写想法功能开发中'); }}>
                            <EditOutlined />
                            <span>写想法</span>
                          </button>
                          <button className="sel-toolbar-btn" onClick={() => { setAskCodeModalOpen(true); }}>
                            <QuestionCircleOutlined />
                            <span>提问</span>
                          </button>
                          <button className="sel-toolbar-btn" onClick={() => { message.info('分享功能开发中'); }}>
                            <ShareAltOutlined />
                            <span>分享</span>
                          </button>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </>
            ) : (
              <>
                <div className="content-header">
                  <div className="header-main">
                    <h1 className="content-title">{selectedTitle || '欢迎'}</h1>
                  </div>
                </div>
                <div className="content-body">
                  {selectedContent ? (
                    <div className="markdown-content">
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        rehypePlugins={[rehypeHighlight]}
                        components={{
                          h1: ({ children }) => {
                            const text = Array.isArray(children) ? children.join('') : String(children || '');
                            const id = headingIdsRef.current[headingIndexRef.current] || slugify(text);
                            headingIndexRef.current += 1;
                            return <h1 id={id}>{children}</h1>;
                          },
                          h2: ({ children }) => {
                            const text = Array.isArray(children) ? children.join('') : String(children || '');
                            const id = headingIdsRef.current[headingIndexRef.current] || slugify(text);
                            headingIndexRef.current += 1;
                            return <h2 id={id}>{children}</h2>;
                          },
                          h3: ({ children }) => {
                            const text = Array.isArray(children) ? children.join('') : String(children || '');
                            const id = headingIdsRef.current[headingIndexRef.current] || slugify(text);
                            headingIndexRef.current += 1;
                            return <h3 id={id}>{children}</h3>;
                          },
                          pre: ({ children }) => {
                            const className = children?.props?.className || '';
                            const raw = children?.props?.children;
                            const textContent = Array.isArray(raw) ? raw.join('') : raw;
                            let trimmed = typeof textContent === 'string' ? textContent.trim() : '';
                            // 检测并解码 URL 编码的内容（AI 有时会输出 URL 编码的 Mermaid）
                            if (/%[0-9A-Fa-f]{2}/.test(trimmed)) {
                              try {
                                const decoded = decodeURIComponent(trimmed);
                                if (decoded !== trimmed && decoded.length > 0) trimmed = decoded.trim();
                              } catch { /* ignore decode errors */ }
                            }
                            const mermaidStarters = [
                              'graph', 'flowchart', 'sequenceDiagram', 'classDiagram',
                              'stateDiagram', 'stateDiagram-v2', 'erDiagram',
                              'gantt', 'journey', 'pie', 'gitgraph'
                            ];
                            const looksLikeMermaid = mermaidStarters.some(starter => trimmed.startsWith(starter));
                            if (className.includes('language-mermaid') || looksLikeMermaid) {
                              return <MermaidChart chart={trimmed} />;
                            }
                            return <pre>{children}</pre>;
                          }
                        }}
                      >
                        {selectedContent}
                      </ReactMarkdown>
                    </div>
                  ) : (
                    <div className="content-empty">
                      <Empty description="选择一个章节开始阅读" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    </div>
                  )}
                </div>
              </>
            )}
          </div>
        </main>

        {!viewingCode && anchors.length > 0 && (
          <div
            className="floating-toc"
            onMouseEnter={() => setShowTocDropdown(true)}
            onMouseLeave={() => setShowTocDropdown(false)}
          >
            <div className={`toc-trigger ${showTocDropdown ? 'active' : ''}`}>
              <div className="toc-indicator"></div>
              <div className="toc-lines">
                {Array.from({ length: Math.min(anchors.length, 10) }).map((_, idx) => (
                  <span key={idx} className="toc-line"></span>
                ))}
              </div>
            </div>
            <div className="toc-dropdown" style={{ display: showTocDropdown ? 'block' : 'none' }}>
              <ul className="toc-list">
                {anchors.map((item) => (
                  <li
                    key={item.id}
                    className={`toc-item level-${item.level}`}
                    onClick={() => scrollToAnchor(item.id)}
                  >
                    {item.title}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}

        <aside className="docs-sidebar">
          <div className="zwiki-doc-sidebar">
            <div className="thesis-card">
              <div className="thesis-card-bg">
                <div className="bg-circle bg-circle-1"></div>
                <div className="bg-circle bg-circle-2"></div>
                <div className="bg-circle bg-circle-3"></div>
              </div>
              <div className="thesis-card-content">
                <div className="thesis-icon">
                  <span className="thesis-icon-inner">📓</span>
                </div>
                <div className="thesis-info">
                  <h3 className="thesis-title">论文预览与生成</h3>
                  <p className="thesis-desc">基于项目分析自动生成毕业论文</p>
                </div>
                <button className="thesis-btn" onClick={() => navigate(`/thesis?taskId=${taskId}`)}>
                  <span>进入生成</span>
                  <span className="btn-arrow">→</span>
                </button>
              </div>
            </div>

            <div className="thesis-card" style={{ marginTop: 12, background: 'linear-gradient(135deg, #06b6d4 0%, #0891b2 50%, #0e7490 100%)', boxShadow: '0 4px 20px rgba(6, 182, 212, 0.3)' }}>
              <div className="thesis-card-content">
                <div className="thesis-icon">
                  <span className="thesis-icon-inner">📐</span>
                </div>
                <div className="thesis-info">
                  <h3 className="thesis-title">架构绘图</h3>
                  <p className="thesis-desc">使用 Draw.io 创建流程图与架构图</p>
                </div>
                <button className="thesis-btn" style={{ color: '#0891b2' }} onClick={() => navigate(`/repo/${taskId}/diagrams`)}>
                  <span>进入绘图</span>
                  <span className="btn-arrow">→</span>
                </button>
              </div>
            </div>

            <div className="sidebar-code-tree">
              <div
                className="sidebar-code-tree-header"
                onClick={() => setCodeTreeCollapsed(!codeTreeCollapsed)}
              >
                <CodeOutlined style={{ color: '#10b981', fontSize: 15 }} />
                <span className="sidebar-code-tree-title">代码库</span>
                <DownOutlined className={`sidebar-code-tree-arrow ${codeTreeCollapsed ? '' : 'expanded'}`} />
              </div>
              {!codeTreeCollapsed && (
                <div className="sidebar-code-tree-body">
                  {fileTreeLoading ? (
                    <div style={{ textAlign: 'center', padding: '16px 0' }}>
                      <Spin size="small" />
                    </div>
                  ) : fileTree.length > 0 ? (
                    <div className="sidebar-file-tree">
                      {fileTree.map((node) => renderFileTreeNode(node, 0))}
                    </div>
                  ) : (
                    <div style={{ textAlign: 'center', padding: '12px 0', color: '#9ca3af', fontSize: 13 }}>
                      暂无代码文件
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </aside>
      </div>

      {showAIPanel && (
        <div className="ask-ai-panel">
          <div className="panel-overlay" onClick={() => setShowAIPanel(false)}></div>
          <div className="panel-content">
            <div className="panel-header">
              <div className="header-title">
                <span className="ai-icon">✨</span>
                <span>Ask AI</span>
              </div>
              <button className="close-btn" onClick={() => setShowAIPanel(false)}>
                <span>×</span>
              </button>
            </div>

            <div className="messages-area" ref={messagesRef}>
              {aiMessages.length === 0 ? (
                <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                  <div className="placeholder-text" style={{ marginBottom: 8 }}>提问任何有关此仓库的问题</div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {[
                      { icon: '🏗️', text: '生成项目架构图', query: '请分析这个项目的整体架构，生成一张项目架构图（用 draw.io）' },
                      { icon: '📊', text: '生成类关系图', query: '请分析项目的核心类，生成一张类关系图（用 draw.io classDiagram）' },
                      { icon: '🔄', text: '生成接口时序图', query: '请分析项目的主要API接口调用链路，生成一张时序图（用 draw.io sequenceDiagram）' },
                      { icon: '🗄️', text: '生成数据库ER图', query: '请分析项目的数据库实体类，生成一张ER关系图（用 draw.io erDiagram）' },
                      { icon: '🚀', text: '生成部署架构图', query: '请分析这个项目的部署方式和微服务结构，生成一张部署架构图（用 draw.io）' },
                    ].map((item, idx) => (
                      <div
                        key={idx}
                        onClick={() => handleSendAI(item.query)}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 10,
                          padding: '10px 14px', borderRadius: 10,
                          background: '#f8fafc', border: '1px solid #e2e8f0',
                          cursor: 'pointer', transition: 'all 0.2s', fontSize: 13,
                        }}
                        onMouseEnter={e => { e.currentTarget.style.background = '#f0f9ff'; e.currentTarget.style.borderColor = '#bae6fd'; }}
                        onMouseLeave={e => { e.currentTarget.style.background = '#f8fafc'; e.currentTarget.style.borderColor = '#e2e8f0'; }}
                      >
                        <span style={{ fontSize: 18 }}>{item.icon}</span>
                        <span style={{ color: '#334155' }}>{item.text}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : (
                <div className="message-list">
                  {aiMessages.map((msg) => (
                    <div key={msg.id} className={`message ${msg.role}`}>
                      <div className="message-content">
                        {msg.role === 'bot' ? (
                          (() => {
                            const { imageUrls, videoUrls } = extractMediaUrls(String(msg.content || ''));
                            const drawioUrls = extractDrawioUrls(String(msg.content || ''));
                            const mediaUrls = [...(imageUrls || []), ...(videoUrls || [])];
                            let textOnly = removeMediaUrlsFromText(String(msg.content || ''), mediaUrls);
                            if (drawioUrls.length > 0) {
                              textOnly = removeDrawioUrlsFromText(textOnly, drawioUrls);
                            }
                            const displayText = normalizeAiTextForDisplay(textOnly);
                            return (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                                {(imageUrls || []).map((u) => (
                                  <div key={u} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                    <img
                                      src={u}
                                      alt="generated"
                                      onDoubleClick={() => setPreviewUrl(u)}
                                      style={{ maxWidth: '100%', borderRadius: 10, cursor: 'zoom-in' }}
                                    />
                                  </div>
                                ))}
                                {(videoUrls || []).map((u) => (
                                  <div key={u} style={{ display: 'flex', flexDirection: 'column', gap: 6, position: 'relative', zIndex: 1 }}>
                                    <video
                                      src={u}
                                      controls
                                      muted
                                      playsInline
                                      loop
                                      preload="metadata"
                                      style={{ 
                                        maxWidth: '100%', 
                                        borderRadius: 10,
                                        pointerEvents: 'auto',
                                        userSelect: 'none'
                                      }}
                                      onError={(e) => {
                                        console.error('Video load error:', e);
                                        const video = e.target;
                                        if (video.src && !video.src.includes('?')) {
                                          video.src = u + '?t=' + Date.now();
                                        }
                                      }}
                                    />
                                  </div>
                                ))}
                                {drawioUrls.map((u, idx) => {
                                  const saved = getSavedDiagramByUrl(u);
                                  return (
                                  <div
                                    key={`drawio-${idx}`}
                                    onClick={() => {
                                      if (saved) {
                                        navigate(`/repo/${taskId}/diagrams?open=${saved.diagramId}`);
                                      } else {
                                        navigate(`/repo/${taskId}/diagrams`);
                                      }
                                    }}
                                    style={{
                                      display: 'flex', alignItems: 'center', gap: 10,
                                      padding: '10px 14px', borderRadius: 10,
                                      background: 'linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%)',
                                      border: '1px solid #bae6fd', cursor: 'pointer',
                                      transition: 'all 0.2s',
                                    }}
                                    onMouseEnter={e => { e.currentTarget.style.boxShadow = '0 2px 8px rgba(14,165,233,0.15)'; }}
                                    onMouseLeave={e => { e.currentTarget.style.boxShadow = 'none'; }}
                                  >
                                    <span style={{ fontSize: 22 }}>📐</span>
                                    <div style={{ flex: 1, minWidth: 0 }}>
                                      <div style={{ fontWeight: 600, fontSize: 13, color: '#0369a1' }}>
                                        {saved ? `查看图表: ${saved.name}` : '在架构绘图中查看图表'}
                                      </div>
                                      <div style={{ fontSize: 11, color: '#64748b', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                        {saved ? '点击跳转到架构绘图页面查看和编辑' : '图表保存中...'}
                                      </div>
                                    </div>
                                    <span style={{ color: '#0ea5e9', fontSize: 16 }}>→</span>
                                  </div>
                                  );
                                })}
                                {textOnly ? (
                                  <ChatMarkdown
                                    content={displayText}
                                    navigate={navigate}
                                    catalogueTree={catalogueTree}
                                    onSelectNode={handleSelectNode}
                                  />
                                ) : (!msg.content && aiLoading) ? (
                                  <div style={{ color: '#94a3b8', fontSize: 14, display: 'flex', alignItems: 'center', gap: 6 }}>
                                    <span style={{ display: 'inline-flex', gap: 3 }}>
                                      正在思考中
                                      <span className="thinking-dots" style={{ display: 'inline-flex', gap: 2 }}>
                                        <span style={{ animation: 'thinkingDot 1.4s infinite', animationDelay: '0s' }}>.</span>
                                        <span style={{ animation: 'thinkingDot 1.4s infinite', animationDelay: '0.2s' }}>.</span>
                                        <span style={{ animation: 'thinkingDot 1.4s infinite', animationDelay: '0.4s' }}>.</span>
                                      </span>
                                    </span>
                                  </div>
                                ) : null}
                              </div>
                            );
                          })()
                        ) : (
                          <ChatMarkdown
                            content={String(msg.content || '')}
                            navigate={navigate}
                            catalogueTree={catalogueTree}
                            onSelectNode={handleSelectNode}
                          />
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="input-area">
              <div className="input-wrapper">
                <input
                  ref={inputRef}
                  value={aiQuestion}
                  onChange={(e) => setAiQuestion(e.target.value)}
                  type="text"
                  className="question-input"
                  placeholder="提出后续问题..."
                  onKeyUp={(e) => {
                    if (e.key === 'Enter') {
                      handleSendAI();
                    }
                  }}
                  disabled={aiLoading}
                />
                <span className="enter-hint">↵</span>
              </div>
            </div>
            <div className="panel-footer">回答由AI生成，可能包含错误。</div>
          </div>
        </div>
      )}

      {previewUrl && (
        <div
          role="dialog"
          aria-modal="true"
          onClick={() => setPreviewUrl(null)}
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 9999,
            background: 'rgba(0,0,0,0.6)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 16,
          }}
        >
          <img
            src={previewUrl}
            alt="preview"
            onClick={(e) => e.stopPropagation()}
            style={{
              maxWidth: 'min(1100px, 96vw)',
              maxHeight: '90vh',
              borderRadius: 10,
              boxShadow: '0 10px 40px rgba(0,0,0,0.35)',
              background: '#fff',
            }}
          />
        </div>
      )}
      <Modal
        title="Wiki Webhook 自动更新配置"
        open={webhookModalOpen}
        onCancel={() => setWebhookModalOpen(false)}
        footer={[
          webhookConfig && (
            <Popconfirm key="del" title="确认删除此 Webhook 配置？" onConfirm={handleDeleteWebhook}>
              <Button danger loading={webhookLoading}>删除</Button>
            </Popconfirm>
          ),
          <Button key="cancel" onClick={() => setWebhookModalOpen(false)}>取消</Button>,
          <Button key="save" type="primary" loading={webhookLoading} onClick={handleSaveWebhook}>
            {webhookConfig ? '更新' : '启用 Webhook'}
          </Button>,
        ].filter(Boolean)}
        width={600}
      >
        <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label="Webhook URL">
            <Typography.Text code copyable={{ text: webhookUrl }} style={{ fontSize: 12 }}>
              {webhookUrl}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Content type">
            <Tag>application/json</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="触发事件">
            <Tag color="blue">push</Tag>
          </Descriptions.Item>
          {webhookConfig && (
            <Descriptions.Item label="统计">
              已触发 <strong>{webhookConfig.triggerCount || 0}</strong> 次
              {webhookConfig.lastTriggerAt && (
                <span style={{ marginLeft: 8, color: '#999' }}>
                  最后触发: {webhookConfig.lastTriggerAt}
                </span>
              )}
            </Descriptions.Item>
          )}
        </Descriptions>

        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="配置步骤"
          description={
            <ol style={{ margin: 0, paddingLeft: 20 }}>
              <li>打开 GitHub 仓库 → Settings → Webhooks → Add webhook</li>
              <li>Payload URL 填写上方的 Webhook URL</li>
              <li>Content type 选择 application/json</li>
              <li>Secret 填写下方设置的密钥（可选）</li>
              <li>选择 "Just the push event"</li>
            </ol>
          }
        />

        <Form form={webhookForm} layout="vertical">
          <Form.Item name="webhookSecret" label="Webhook Secret（可选，用于签名验证）">
            <Input.Password placeholder="留空则不验证签名" />
          </Form.Item>
          <Form.Item name="enabled" label="启用自动更新" valuePropName="checked" initialValue={true}>
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={null}
        open={askCodeModalOpen}
        onCancel={() => { setAskCodeModalOpen(false); setAskCodeQuestion(''); }}
        footer={null}
        width={520}
        closable={false}
        className="ask-code-modal"
      >
        <div className="ask-code-modal-inner">
          <Input.TextArea
            value={askCodeQuestion}
            onChange={(e) => setAskCodeQuestion(e.target.value)}
            placeholder="在这里输入你的问题..."
            autoSize={{ minRows: 3, maxRows: 6 }}
            style={{ border: 'none', boxShadow: 'none', resize: 'none', fontSize: 15, padding: '12px 0' }}
          />
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
            <Button onClick={handleAskCodeSubmit} style={{ borderRadius: 6 }}>
              Ask AI
            </Button>
          </div>
        </div>
      </Modal>

      <DocChangeModal
        open={docChangeModalOpen}
        onClose={() => setDocChangeModalOpen(false)}
        taskId={taskId}
        taskName={task?.projectName || ''}
      />

    </div>
  );
};

export default RepoDetail;