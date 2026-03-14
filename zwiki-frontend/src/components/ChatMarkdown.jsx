import React, { useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import MermaidChart from './MermaidChart';
import { isRepoUrl, resolveTaskId, findCatalogueByTitle } from '../utils/chatLinkResolver';

/**
 * 聊天消息的 Markdown 渲染组件。
 * 支持：
 * - 标题、加粗、斜体、列表、表格、代码块等完整 Markdown 渲染
 * - Mermaid 图表检测与渲染
 * - GitHub/Gitee 仓库链接 → 点击跳转项目 Wiki 文档页
 * - 目录章节名 → 点击跳转对应文档（仅 RepoDetail 上下文）
 *
 * Props:
 *   content       - Markdown 文本
 *   navigate      - react-router navigate 函数
 *   catalogueTree - 可选，目录树数据（RepoDetail 上下文传入）
 *   onSelectNode  - 可选，选中目录节点回调 (key) => void
 */
const ChatMarkdown = ({ content, navigate, catalogueTree, onSelectNode }) => {
  const handleLinkClick = useCallback(async (e, href) => {
    if (!href || !navigate) return;

    // 仓库链接 → 跳转项目 Wiki 文档页
    if (isRepoUrl(href)) {
      e.preventDefault();
      try {
        const taskId = await resolveTaskId(href);
        if (taskId) {
          navigate(`/repo/${taskId}`);
        } else {
          window.open(href, '_blank', 'noopener');
        }
      } catch {
        window.open(href, '_blank', 'noopener');
      }
      return;
    }
  }, [navigate]);

  const handleCatalogueClick = useCallback((e, title) => {
    if (!catalogueTree || !onSelectNode) return;
    const found = findCatalogueByTitle(catalogueTree, title);
    if (found && found.key) {
      e.preventDefault();
      onSelectNode(found.key);
    }
  }, [catalogueTree, onSelectNode]);

  const components = {
    // 自定义链接：拦截仓库 URL 跳转到内部 Wiki 页面
    a: ({ href, children, ...rest }) => {
      if (isRepoUrl(href)) {
        return (
          <a
            href={href}
            onClick={(e) => handleLinkClick(e, href)}
            style={{ color: '#2563eb', cursor: 'pointer', textDecoration: 'none' }}
            title="点击查看项目 Wiki 文档"
            {...rest}
          >
            {children}
            <span style={{ fontSize: 11, marginLeft: 4, opacity: 0.7 }}>📄</span>
          </a>
        );
      }
      return (
        <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
          {children}
        </a>
      );
    },
    // 列表项：检测是否匹配目录章节名，可点击跳转
    li: ({ children, ...rest }) => {
      if (catalogueTree && onSelectNode) {
        const textContent = extractTextFromChildren(children);
        if (textContent) {
          const found = findCatalogueByTitle(catalogueTree, textContent);
          if (found && found.key && found.content) {
            return (
              <li {...rest}>
                <span
                  onClick={(e) => handleCatalogueClick(e, textContent)}
                  style={{ color: '#2563eb', cursor: 'pointer', borderBottom: '1px dashed #93c5fd' }}
                  title="点击跳转到该章节"
                >
                  {children}
                </span>
              </li>
            );
          }
        }
      }
      return <li {...rest}>{children}</li>;
    },
    // Mermaid 代码块检测
    pre: ({ children }) => {
      const className = children?.props?.className || '';
      const raw = children?.props?.children;
      const textContent = Array.isArray(raw) ? raw.join('') : raw;
      let trimmed = typeof textContent === 'string' ? textContent.trim() : '';
      if (/%[0-9A-Fa-f]{2}/.test(trimmed)) {
        try {
          const decoded = decodeURIComponent(trimmed);
          if (decoded !== trimmed && decoded.length > 0) trimmed = decoded.trim();
        } catch { /* ignore */ }
      }
      const mermaidStarters = [
        'graph', 'flowchart', 'sequenceDiagram', 'classDiagram',
        'stateDiagram', 'stateDiagram-v2', 'erDiagram',
        'gantt', 'journey', 'pie', 'gitgraph'
      ];
      if (className.includes('language-mermaid') || mermaidStarters.some(s => trimmed.startsWith(s))) {
        return <MermaidChart chart={trimmed} />;
      }
      return <pre>{children}</pre>;
    },
  };

  return (
    <div className="chat-markdown" style={{ lineHeight: 1.7, fontSize: 14 }}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {content}
      </ReactMarkdown>
    </div>
  );
};

/**
 * 从 React children 中提取纯文本内容
 */
function extractTextFromChildren(children) {
  if (!children) return '';
  if (typeof children === 'string') return children.trim();
  if (Array.isArray(children)) {
    return children.map(c => extractTextFromChildren(c)).join('').trim();
  }
  if (children?.props?.children) {
    return extractTextFromChildren(children.props.children);
  }
  return '';
}

export default ChatMarkdown;
