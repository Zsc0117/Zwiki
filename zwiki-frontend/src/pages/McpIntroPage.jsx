import React, { useState } from 'react';
import { Typography, Card, Tag, Row, Col, Button, App, Divider, theme } from 'antd';
import {
  CopyOutlined,
  SearchOutlined,
  FolderOpenOutlined,
  FileTextOutlined,
  ProjectOutlined,
  ReadOutlined,
  BarChartOutlined,
  ApiOutlined,
  ThunderboltOutlined,
  CloudServerOutlined,
  HomeOutlined,
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import ApiKeySelectModal from '../components/ApiKeySelectModal';

const { Title, Text, Paragraph } = Typography;

const getMcpServerUrl = () => {
  if (process.env.REACT_APP_MCP_SERVER_URL) {
    return process.env.REACT_APP_MCP_SERVER_URL.replace(/\/+$/, '');
  }
  const origin = window.location.origin;
  try {
    const url = new URL(origin);
    url.port = '9090';
    return url.origin;
  } catch {
    return origin.replace(/:\d+$/, ':9090');
  }
};

const MCP_SERVER_URL = getMcpServerUrl();

const TOOLS = [
  {
    name: 'list_projects',
    icon: <ProjectOutlined style={{ fontSize: 28, color: '#0891b2' }} />,
    desc: '列出 ZwikiAI 平台上所有已分析的代码仓库/项目，返回 taskId、项目名、仓库地址、分析状态等信息。',
    tags: ['项目管理'],
  },
  {
    name: 'search_wiki',
    icon: <SearchOutlined style={{ fontSize: 28, color: '#059669' }} />,
    desc: '在 Wiki 文档的标题和内容中搜索关键词，支持按项目过滤，快速定位相关文档。',
    tags: ['文档检索'],
  },
  {
    name: 'get_repo_structure',
    icon: <FolderOpenOutlined style={{ fontSize: 28, color: '#d97706' }} />,
    desc: '查看项目文件目录结构，支持指定子目录路径，帮助理解项目的模块划分和目录组织。',
    tags: ['仓库浏览'],
  },
  {
    name: 'read_file',
    icon: <FileTextOutlined style={{ fontSize: 28, color: '#0284c7' }} />,
    desc: '读取项目中的单个文件内容，用于查看核心代码实现、进行代码审查或深入分析特定模块。',
    tags: ['代码阅读'],
  },
  {
    name: 'get_wiki_document',
    icon: <ReadOutlined style={{ fontSize: 28, color: '#0d9488' }} />,
    desc: '获取 AI 生成的 Wiki 文档内容，可以获取整个项目的文档概览或通过标题获取特定章节。',
    tags: ['Wiki 文档'],
  },
  {
    name: 'get_project_report',
    icon: <BarChartOutlined style={{ fontSize: 28, color: '#2563eb' }} />,
    desc: '获取项目的分析摘要报告，包括项目概述、技术栈、核心模块等关键信息，快速了解项目全貌。',
    tags: ['项目报告'],
  },
];

const buildStreamableHttpJson = (apiKey) => {
  return JSON.stringify({
    mcpServers: {
      zwiki: {
        type: 'streamableHttp',
        url: `${MCP_SERVER_URL}/mcp`,
        headers: {
          Authorization: `Bearer ${apiKey || 'your_api_key'}`,
        },
      },
    },
  }, null, 2);
};

const buildSseUrl = (apiKey) => {
  return `${MCP_SERVER_URL}/sse?Authorization=${apiKey || 'your_api_key'}`;
};

const buildClaudeCommand = (apiKey) => {
  return `claude mcp add -s user -t http zwiki ${MCP_SERVER_URL}/mcp --header "Authorization: Bearer ${apiKey || 'your_api_key'}"`;
};

const CodeBlock = ({ children, onCopy, codeBg, codeColor }) => (
  <div style={{
    position: 'relative',
    background: codeBg || '#1e1e1e',
    borderRadius: 8,
    padding: '16px 20px',
    marginBottom: 16,
    overflow: 'auto',
  }}>
    <Button
      type="text"
      icon={<CopyOutlined />}
      size="small"
      onClick={onCopy}
      style={{
        position: 'absolute',
        top: 8,
        right: 8,
        color: '#999',
        zIndex: 2,
      }}
    >
      复制
    </Button>
    <pre style={{
      margin: 0,
      color: codeColor || '#d4d4d4',
      fontSize: 13,
      lineHeight: 1.6,
      fontFamily: 'Consolas, "Courier New", monospace',
      whiteSpace: 'pre-wrap',
      wordBreak: 'break-all',
    }}>
      {children}
    </pre>
  </div>
);

const SectionTitle = ({ children, icon }) => (
  <Title level={3} style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 48, marginBottom: 16 }}>
    {icon}
    {children}
  </Title>
);

const McpIntroPage = () => {
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const { message } = App.useApp();
  const [keySelectOpen, setKeySelectOpen] = useState(false);
  const [keySelectTarget, setKeySelectTarget] = useState(null); // 'streamableHttp' | 'sse' | 'claude'

  const codeBg = token.colorBgElevated === '#fff' || token.colorBgElevated === '#ffffff' ? '#1e1e1e' : token.colorFillQuaternary;
  const codeColor = codeBg === '#1e1e1e' ? '#d4d4d4' : token.colorText;
  const secondaryColor = token.colorTextSecondary;
  const linkStyle = { cursor: 'pointer', color: token.colorPrimary };

  const handleCopyWithKeySelect = (target) => {
    setKeySelectTarget(target);
    setKeySelectOpen(true);
  };

  const handleKeySelected = (apiKey) => {
    setKeySelectOpen(false);
    let text = '';
    if (keySelectTarget === 'streamableHttp') {
      text = buildStreamableHttpJson(apiKey);
    } else if (keySelectTarget === 'sse') {
      text = buildSseUrl(apiKey);
    } else if (keySelectTarget === 'claude') {
      text = buildClaudeCommand(apiKey);
    }
    navigator.clipboard.writeText(text).then(() => {
      message.success('已复制配置到剪贴板（已填入 API Key）');
    }).catch(() => {
      message.error('复制失败，请手动复制');
    });
  };

  const handleKeySelectCancel = () => {
    setKeySelectOpen(false);
  };

  const containerVariants = {
    hidden: { opacity: 0 },
    visible: { opacity: 1, transition: { staggerChildren: 0.08 } },
  };
  const itemVariants = {
    hidden: { y: 20, opacity: 0 },
    visible: { y: 0, opacity: 1, transition: { type: 'spring', stiffness: 200, damping: 20 } },
  };

  return (
    <div style={{ maxWidth: 960, margin: '0 auto', padding: '20px 20px 80px', color: token.colorText }}>
      {/* Hero */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
        style={{ textAlign: 'center', marginBottom: 48, paddingTop: 20 }}
      >
        <Title level={1} style={{ marginBottom: 8 }}>ZwikiAI MCP</Title>
        <Paragraph style={{ fontSize: 17, color: secondaryColor, maxWidth: 680, margin: '0 auto' }}>
          通过标准 MCP 协议，让 Cursor、Windsurf、Claude Code 等 AI IDE 直接调用 ZwikiAI 的仓库阅读、文档检索和代码分析能力。
        </Paragraph>
        <div style={{ marginTop: 20, display: 'flex', gap: 12, justifyContent: 'center', flexWrap: 'wrap' }}>
          <Button type="primary" size="large" onClick={() => navigate('/center/apikeys')}>
            获取 API Key
          </Button>
          <Button size="large" onClick={() => {
            const el = document.getElementById('mcp-client-config');
            if (el) el.scrollIntoView({ behavior: 'smooth' });
          }}>
            查看配置
          </Button>
          <Button size="large" icon={<HomeOutlined />} onClick={() => navigate('/')}>
            返回首页
          </Button>
        </div>
      </motion.div>

      {/* 1. 什么是 MCP */}
      <SectionTitle icon={<ThunderboltOutlined />}>1. 什么是 MCP？</SectionTitle>
      <Card style={{ borderRadius: 12, marginBottom: 24 }}>
        <Paragraph style={{ fontSize: 15, margin: 0 }}>
          <Text strong>Model Context Protocol (MCP)</Text> 是一种标准协议，允许大模型以统一方式"使用工具"。
          你可以将 MCP 看作 AI 应用的"统一扩展接口"：同一个 MCP Server 可以在不同的 MCP 支持产品中使用，
          如 <Tag color="cyan">Cursor</Tag> <Tag color="blue">Windsurf</Tag> <Tag color="green">Claude Code</Tag> 等。
        </Paragraph>
      </Card>

      {/* 2. ZwikiAI MCP 概览 */}
      <SectionTitle icon={<ApiOutlined />}>2. ZwikiAI MCP 概览</SectionTitle>
      <Paragraph style={{ fontSize: 15, marginBottom: 20 }}>
        ZwikiAI MCP 让你的 AI 助手可以直接调用 ZwikiAI 的代码理解和检索能力：
      </Paragraph>
      <ul style={{ fontSize: 15, lineHeight: 2, paddingLeft: 24, marginBottom: 24 }}>
        <li>在代码仓库中搜索文档、代码和注释</li>
        <li>浏览仓库结构，快速掌握模块分布</li>
        <li>打开特定文件进行深度分析</li>
        <li>获取 AI 生成的 Wiki 文档和项目报告</li>
      </ul>
      <Paragraph style={{ fontSize: 15, marginBottom: 12 }}>
        <Text strong>访问控制：</Text>使用时需在本地客户端中配置 API Key。
        可在 <span onClick={() => navigate('/center/apikeys')} style={linkStyle}>API Key 管理页</span> 创建和管理你的密钥。
      </Paragraph>

      {/* 3. 可用工具 */}
      <SectionTitle icon={<ProjectOutlined />}>3. 可用工具</SectionTitle>
      <Paragraph style={{ fontSize: 15, marginBottom: 20 }}>
        ZwikiAI MCP 目前提供六个核心工具。你可以在对话中显式调用它们，也可以让客户端自动选择。
      </Paragraph>
      <motion.div variants={containerVariants} initial="hidden" animate="visible">
        <Row gutter={[16, 16]}>
          {TOOLS.map((tool) => (
            <Col xs={24} sm={12} key={tool.name}>
              <motion.div variants={itemVariants}>
                <Card
                  hoverable
                  style={{ borderRadius: 12, height: '100%' }}
                  styles={{ body: { padding: '20px 24px' } }}
                >
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
                    <div style={{
                      width: 48, height: 48, borderRadius: 12,
                      background: token.colorFillQuaternary, display: 'flex',
                      alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                    }}>
                      {tool.icon}
                    </div>
                    <div style={{ flex: 1 }}>
                      <Text strong style={{ fontSize: 15 }}>{tool.name}</Text>
                      {tool.tags.map(t => (
                        <Tag key={t} style={{ marginLeft: 8 }} color="cyan">{t}</Tag>
                      ))}
                      <Paragraph style={{ margin: '6px 0 0', color: secondaryColor, fontSize: 13 }}>
                        {tool.desc}
                      </Paragraph>
                    </div>
                  </div>
                </Card>
              </motion.div>
            </Col>
          ))}
        </Row>
      </motion.div>

      {/* 4. 协议和端点 */}
      <SectionTitle icon={<CloudServerOutlined />}>4. 协议和端点</SectionTitle>
      <Paragraph style={{ fontSize: 15, marginBottom: 20 }}>
        ZwikiAI MCP 支持两种访问方式，以方便不同客户端使用：
      </Paragraph>

      <Card style={{ borderRadius: 12, marginBottom: 16 }}>
        <Title level={5}>4.1 SSE (Server-Sent Events) — <Text code>/sse</Text></Title>
        <ul style={{ fontSize: 14, lineHeight: 2, paddingLeft: 24, margin: 0 }}>
          <li>URL：<Text code>{MCP_SERVER_URL}/sse?Authorization=your_api_key</Text></li>
          <li>遵循 MCP 官方推荐规范</li>
          <li>适用于大部分支持 MCP 的 IDE / 客户端</li>
          <li><Text strong>推荐优先使用</Text></li>
        </ul>
      </Card>

      <Card style={{ borderRadius: 12, marginBottom: 24 }}>
        <Title level={5}>4.2 Streamable HTTP — <Text code>/mcp</Text></Title>
        <ul style={{ fontSize: 14, lineHeight: 2, paddingLeft: 24, margin: 0 }}>
          <li>URL：<Text code>{MCP_SERVER_URL}/mcp</Text></li>
          <li>适配支持 HTTP 流式 MCP 的客户端（如 Cursor、Windsurf 等）</li>
        </ul>
      </Card>

      {/* 5. 客户端配置 */}
      <div id="mcp-client-config">
        <SectionTitle icon={<ApiOutlined />}>5. 客户端配置示例</SectionTitle>
      </div>

      <Title level={5}>5.1 通用 MCP 客户端（Windsurf / Cursor）</Title>
      <Paragraph style={{ fontSize: 14, marginBottom: 12 }}>
        在客户端的 MCP 配置文件中添加以下内容：
      </Paragraph>
      <CodeBlock codeBg={codeBg} codeColor={codeColor} onCopy={() => handleCopyWithKeySelect('streamableHttp')}>
        {buildStreamableHttpJson()}
      </CodeBlock>
      <Paragraph type="secondary" style={{ fontSize: 13, marginBottom: 24 }}>
        注意：将 <Text code>your_api_key</Text> 替换为在{' '}
        <span onClick={() => navigate('/center/apikeys')} style={linkStyle}>API Key 管理页</span> 获取的密钥。
        点击复制按钮时会自动提示选择你的 API Key。
      </Paragraph>

      <Title level={5}>5.2 Claude Code CLI</Title>
      <CodeBlock codeBg={codeBg} codeColor={codeColor} onCopy={() => handleCopyWithKeySelect('claude')}>
        {buildClaudeCommand()}
      </CodeBlock>

      <Divider />

      <Title level={5}>5.3 SSE 模式</Title>
      <Paragraph style={{ fontSize: 14, marginBottom: 12 }}>
        如果客户端支持 SSE 模式：
      </Paragraph>
      <CodeBlock codeBg={codeBg} codeColor={codeColor} onCopy={() => handleCopyWithKeySelect('sse')}>
        {buildSseUrl()}
      </CodeBlock>

      {/* 6. 使用示例 */}
      <SectionTitle icon={<ReadOutlined />}>6. 使用示例</SectionTitle>
      <Card style={{ borderRadius: 12 }}>
        <Paragraph style={{ fontSize: 14, marginBottom: 8 }}>
          配置完成后，你可以在 IDE/客户端的对话中使用类似以下请求：
        </Paragraph>
        <ul style={{ fontSize: 14, lineHeight: 2.2, paddingLeft: 24, margin: 0 }}>
          <li>"使用 zwiki 列出所有已分析的项目"</li>
          <li>"用 zwiki 搜索关于用户认证的文档"</li>
          <li>"通过 zwiki 查看项目的文件结构"</li>
          <li>"用 zwiki 读取 ChatController.java 的源代码"</li>
          <li>"用 zwiki 获取项目的 Wiki 文档目录"</li>
          <li>"通过 zwiki 获取项目分析报告"</li>
        </ul>
      </Card>

      {/* API Key 选择弹窗 */}
      <ApiKeySelectModal
        open={keySelectOpen}
        onSelect={handleKeySelected}
        onCancel={handleKeySelectCancel}
      />
    </div>
  );
};

export default McpIntroPage;
