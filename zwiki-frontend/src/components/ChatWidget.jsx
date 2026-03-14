import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { 
  App
} from 'antd';
import { 
  CloseOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import { motion, AnimatePresence } from 'framer-motion';
import ChatMarkdown from './ChatMarkdown';
import { extractMediaUrls, extractDrawioUrls, removeDrawioUrlsFromText, proxyMediaUrlsToMinio, normalizeAiTextForDisplay } from './formatAiResponse';
import { readSseResponse } from '../utils/sseStream';
import { ChatHistoryApi } from '../api/chatHistory';
import { useAuth } from '../auth/AuthContext';
import useSavedDiagrams from '../hooks/useSavedDiagrams';

const QUICK_QUESTIONS = [
  { text: '生成项目架构图（Draw.io）' },
  { text: '这个项目用了哪些技术栈？' },
  { text: '生成核心类关系图' },
  { text: '生成接口调用时序图' },
];

const ChatWidget = ({ darkMode: _darkMode = false, taskId }) => {
  const { message } = App.useApp();
  const location = useLocation();
  const navigate = useNavigate();
  const isRepoDetailPage = /^\/repo\//.test(location.pathname);
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState([]);
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [previewUrl, setPreviewUrl] = useState(null);
  const [proxiedVideos, setProxiedVideos] = useState({});
  const [proxiedImages, setProxiedImages] = useState({});
  const proxyingRef = useRef(new Set());
  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);
  const { me } = useAuth();
  const userId = me?.userId;
  const {
    hydrateSavedDrawioDiagrams,
    getSavedDiagramByUrl,
    autoSaveDrawioDiagrams,
  } = useSavedDiagrams({
    taskId,
    defaultBaseName: 'AI 生成图表',
    logPrefix: 'ChatWidget',
  });

  useEffect(() => {
    if (!isOpen || !taskId) return;
    hydrateSavedDrawioDiagrams();
  }, [isOpen, taskId, hydrateSavedDrawioDiagrams]);

  const proxyMediaUrl = useCallback(async (originalUrl, isImage = false) => {
    const cache = isImage ? proxiedImages : proxiedVideos;
    const setCache = isImage ? setProxiedImages : setProxiedVideos;
    const cacheKey = originalUrl;
    if (cache[cacheKey] || proxyingRef.current.has(cacheKey)) return;
    proxyingRef.current.add(cacheKey);
    try {
      const res = await fetch('/api/media/proxy-upload', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: originalUrl }),
      });
      const json = await res.json();
      if (json.code === 200 && json.data) {
        setCache(prev => ({ ...prev, [cacheKey]: json.data }));
      } else {
        console.warn(`[ChatWidget] ${isImage ? 'image' : 'video'} proxy failed:`, json.message);
        setCache(prev => ({ ...prev, [cacheKey]: originalUrl }));
      }
    } catch (e) {
      console.warn(`[ChatWidget] ${isImage ? 'image' : 'video'} proxy error:`, e);
      setCache(prev => ({ ...prev, [cacheKey]: originalUrl }));
    } finally {
      proxyingRef.current.delete(cacheKey);
    }
  }, [proxiedVideos, proxiedImages]);

  useEffect(() => {
    for (const msg of messages) {
      if (msg.type !== 'bot') continue;
      const { imageUrls, videoUrls } = extractMediaUrls(msg.content);
      for (const u of (imageUrls || [])) {
        if (!proxiedImages[u] && !proxyingRef.current.has(u)) {
          proxyMediaUrl(u, true);
        }
      }
      for (const u of (videoUrls || [])) {
        if (!proxiedVideos[u] && !proxyingRef.current.has(u)) {
          proxyMediaUrl(u, false);
        }
      }
    }
  }, [messages, proxiedVideos, proxiedImages, proxyMediaUrl]);

  const removeMediaUrlsFromText = (text, mediaUrls) => {
    let out = String(text || '');
    const urls = Array.from(new Set((mediaUrls || []).filter(Boolean)));
    for (const u of urls) {
      const escaped = u.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      out = out.replace(new RegExp(escaped, 'g'), '');
    }
    out = out.replace(/(图片链接|视频链接)\s*[:：]\s*/g, '');
    out = out.replace(/\n{3,}/g, '\n\n').trim();
    return out;
  };

  const persistMessages = async (userText, botText) => {
    if (!userId) return;
    const payload = [
      {
        userId,
        taskId: taskId || null,
        role: 'user',
        content: userText,
      },
      {
        userId,
        taskId: taskId || null,
        role: 'assistant',
        content: botText,
      },
    ];

    try {
      await ChatHistoryApi.saveBatch(payload);
    } catch (e) {
      console.warn('[ChatWidget] save chat history failed:', e);
    }
  };

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  useEffect(() => {
    if (isOpen) {
      setTimeout(() => { inputRef.current?.focus(); }, 300);
    }
  }, [isOpen]);

  // Load chat history from backend when widget opens
  useEffect(() => {
    if (!isOpen || !userId || historyLoaded) return;
    (async () => {
      try {
        const res = await ChatHistoryApi.getHistory(userId, taskId || null);
        if (res?.code === 200 && Array.isArray(res.data) && res.data.length > 0) {
          const loaded = res.data.map((m, idx) => ({
            id: m.id || idx,
            type: m.role === 'user' ? 'user' : 'bot',
            content: m.content || '',
          }));
          setMessages(loaded);
        }
      } catch (e) {
        console.warn('[ChatWidget] load history failed:', e);
      } finally {
        setHistoryLoaded(true);
      }
    })();
  }, [isOpen, userId, taskId, historyLoaded]);

  const sendMessage = async (directQuery = '') => {
    if (loading) return;
    const query = String(directQuery || inputValue || '').trim();
    if (!query) return;
    
    if (!me || !userId) {
      message.warning('您当前还没有登录，登录体验完整服务！');
      return;
    }
    setInputValue('');
    setLoading(true);

    const msgKey = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const botId = `bot-${msgKey}`;
    const userMsg = { id: `user-${msgKey}`, type: 'user', content: query };
    const botMsg = { id: botId, type: 'bot', content: '' };
    setMessages(prev => [...prev, userMsg, botMsg]);

    const recentHistory = messages.slice(-10).map((m) => ({
      role: m.type === 'user' ? 'user' : 'assistant',
      content: m.content,
    }));

    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, userId, taskId, history: recentHistory }),
      });

      const { content: accumulated, hasErrorEvent, errorMessage } = await readSseResponse(res, {
        onMessage: (nextContent) => {
          setMessages(prev => prev.map(m => (m.id === botId && m.type === 'bot') ? { ...m, content: nextContent } : m));
        },
        onError: (msg) => {
          setMessages(prev => prev.map(m => (m.id === botId && m.type === 'bot') ? { ...m, content: msg } : m));
        },
      });

      const botContent = hasErrorEvent
        ? (errorMessage || '抱歉，服务出现问题，请稍后重试。')
        : (accumulated || '抱歉，我目前无法回答你的问题。');
      if (!accumulated && !hasErrorEvent) {
        setMessages(prev => prev.map(m => (m.id === botId && m.type === 'bot') ? { ...m, content: botContent } : m));
      }
      // Proxy media URLs to MinIO before persisting, so DB stores stable links
      let contentToSave = botContent;
      try {
        const proxied = await proxyMediaUrlsToMinio(botContent);
        if (proxied !== botContent) {
          contentToSave = proxied;
          setMessages(prev => prev.map(m => (m.id === botId && m.type === 'bot') ? { ...m, content: proxied } : m));
        }
      } catch (e) {
        console.warn('[ChatWidget] media proxy before persist failed:', e);
      }
      persistMessages(query, contentToSave);
      // Auto-save drawio diagrams to project diagram list
      if (taskId) {
        await autoSaveDrawioDiagrams(contentToSave, query);
      }
    } catch (error) {
      console.error('Chat SSE error:', error);
      setMessages(prev => prev.map(m => (m.id === botId && m.type === 'bot') ? { ...m, content: '抱歉，服务出现问题，请稍后重试。' } : m));
      message.error('发送消息失败，请检查网络连接');
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e) => {
    if (e.nativeEvent?.isComposing || e.isComposing) return;
    if (e.key === 'Enter') {
      e.preventDefault();
      sendMessage();
    }
  };

  const showWelcome = messages.length === 0;

  if (isRepoDetailPage) return null;

  return (
    <>
      {/* ========== Floating Button ========== */}
      <AnimatePresence>
        {!isOpen && (
          <motion.div
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0, opacity: 0 }}
            whileHover={{ scale: 1.1 }}
            whileTap={{ scale: 0.92 }}
            onClick={() => setIsOpen(true)}
            title={'打开 ZwikiAI 智能助手'}
            style={{
              position: 'fixed', right: 24, bottom: 24,
              background: 'transparent',
              border: 'none',
              zIndex: 1000,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer',
            }}
          >
            <img
              src="/file_5a99221b344447d6b26324ed487c2c0c.png"
              alt="AI助手"
              style={{ width: 64, height: 64, objectFit: 'contain' }}
            />
          </motion.div>
        )}
      </AnimatePresence>

      {/* ========== Chat Panel ========== */}
      <AnimatePresence>
        {isOpen && (
          <>
            {/* Click-outside overlay */}
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setIsOpen(false)}
              style={{
                position: 'fixed',
                inset: 0,
                zIndex: 1000,
                background: 'transparent',
              }}
            />
            <motion.div
              initial={{ opacity: 0, scale: 0.88, y: 24 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.88, y: 24 }}
              transition={{ type: 'spring', stiffness: 340, damping: 28 }}
              style={{
                position: 'fixed', right: 24, bottom: 24,
                width: 420, height: 620, borderRadius: 16,
                zIndex: 1001,
                background: '#ffffff',
                boxShadow: '0 20px 60px rgba(0,0,0,0.15), 0 0 0 1px rgba(0,0,0,0.05)',
                display: 'flex', flexDirection: 'column',
                overflow: 'hidden',
              }}
            >
            {/* --- Header --- */}
            <div style={{
              padding: '14px 20px',
              background: 'linear-gradient(135deg, #7dd3fc 0%, #60a5fa 100%)',
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              flexShrink: 0,
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <img
                  src="/file_5a99221b344447d6b26324ed487c2c0c.png"
                  alt="AI助手"
                  style={{ width: 24, height: 24, objectFit: 'contain' }}
                />
                <span style={{ color: '#fff', fontSize: 14, fontWeight: 600 }}>
                  {'ZwikiAI 智能助手'}
                </span>
              </div>
              <motion.div
                whileHover={{ scale: 1.15, backgroundColor: 'rgba(255,255,255,0.15)' }}
                whileTap={{ scale: 0.9 }}
                onClick={() => setIsOpen(false)}
                style={{
                  width: 28, height: 28, borderRadius: 6,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  cursor: 'pointer',
                }}
              >
                <CloseOutlined style={{ color: 'rgba(255, 255, 255, 0.92)', fontSize: 14 }} />
              </motion.div>
            </div>

            {/* --- Body --- */}
            <div style={{
              flex: 1, overflowY: 'auto',
              display: 'flex', flexDirection: 'column',
              background: '#f8fafc',
            }}>
              {showWelcome ? (
                /* --- Welcome --- */
                <div style={{
                  flex: 1, display: 'flex', flexDirection: 'column',
                  alignItems: 'center', justifyContent: 'center',
                  padding: '0 28px 20px',
                }}>
                  <h2 style={{
                    color: '#1e293b', fontSize: 22, fontWeight: 700,
                    textAlign: 'center', margin: '0 0 6px 0', lineHeight: 1.5,
                  }}>
                    {'你好 👋 我是 ZwikiAI 智能助手'}<br />{'我可以帮你理解项目、代码和平台知识'}
                  </h2>
                  <p style={{
                    color: '#94a3b8', fontSize: 13,
                    margin: '0 0 28px 0', textAlign: 'center',
                  }}>
                    {'支持平台问答、知识检索、项目理解与图表生成'}
                  </p>

                  <div style={{
                    display: 'flex', flexDirection: 'column', gap: 10,
                    width: '100%', maxWidth: 340,
                  }}>
                    {QUICK_QUESTIONS.map((q, i) => (
                      <motion.button
                        key={i}
                        whileHover={{ borderColor: '#22c55e', color: '#22c55e' }}
                        whileTap={{ scale: 0.98 }}
                        onClick={() => {
                          sendMessage(q.text);
                        }}
                        disabled={loading}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 8,
                          padding: '13px 16px',
                          background: '#fff',
                          border: '1px solid #e2e8f0',
                          borderRadius: 12,
                          color: '#334155', fontSize: 14, fontWeight: 500,
                          cursor: 'pointer', textAlign: 'left', width: '100%',
                          transition: 'all 0.2s',
                        }}
                      >
                        <span style={{
                          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                          width: 22, height: 22, borderRadius: 6, flexShrink: 0,
                          background: '#dcfce7',
                          color: '#22c55e',
                          fontSize: 12,
                        }}>
                          <CodeOutlined />
                        </span>
                        {q.text}
                      </motion.button>
                    ))}
                  </div>
                </div>
              ) : (
                /* --- Messages --- */
                <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 14 }}>
                  {messages.map((msg) => (
                    <motion.div
                      key={msg.id}
                      initial={{ opacity: 0, y: 12 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ duration: 0.22 }}
                      style={{
                        display: 'flex',
                        justifyContent: msg.type === 'user' ? 'flex-end' : 'flex-start',
                        alignItems: 'flex-start', gap: 8,
                      }}
                    >
                      {msg.type === 'bot' && (
                        <img
                          src="/file_5a99221b344447d6b26324ed487c2c0c.png"
                          alt="AI助手"
                          style={{ width: 30, height: 30, objectFit: 'contain', flexShrink: 0 }}
                        />
                      )}

                      <div style={{
                        maxWidth: msg.type === 'user' ? '70%' : '85%',
                        padding: '10px 14px',
                        borderRadius: msg.type === 'user' ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
                        background: msg.type === 'user' ? '#22c55e' : '#ffffff',
                        color: msg.type === 'user' ? '#fff' : '#1e293b',
                        fontSize: 14, lineHeight: 1.65, wordBreak: 'break-word',
                        border: msg.type === 'user' ? 'none' : '1px solid #e2e8f0',
                        boxShadow: msg.type === 'user' ? 'none' : '0 1px 3px rgba(0,0,0,0.04)',
                      }}>
                        {msg.type === 'bot' ? (
                          (() => {
                            const { imageUrls, videoUrls } = extractMediaUrls(msg.content);
                            const drawioUrls = extractDrawioUrls(msg.content);
                            const mediaUrls = [...(imageUrls || []), ...(videoUrls || [])];
                            let textOnly = removeMediaUrlsFromText(msg.content, mediaUrls);
                            if (drawioUrls.length > 0) {
                              textOnly = removeDrawioUrlsFromText(textOnly, drawioUrls);
                            }
                            const displayText = normalizeAiTextForDisplay(textOnly);
                            return (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                                {(imageUrls || []).map((u) => {
                                  const imgSrc = proxiedImages[u];
                                  return (
                                    <div key={u} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                      {!imgSrc ? (
                                        <div style={{
                                          width: '100%', maxWidth: 400, aspectRatio: '4/3',
                                          borderRadius: 10,
                                          background: '#f8fafc', display: 'flex',
                                          alignItems: 'center', justifyContent: 'center',
                                          color: '#94a3b8', fontSize: 13,
                                        }}>
                                          图片加载中...
                                        </div>
                                      ) : (
                                        <img
                                          src={imgSrc}
                                          alt="generated"
                                          onDoubleClick={() => setPreviewUrl(imgSrc)}
                                          style={{ maxWidth: '100%', borderRadius: 10, cursor: 'zoom-in' }}
                                        />
                                      )}
                                    </div>
                                  );
                                })}
                                {(videoUrls || []).map((u) => {
                                  const src = proxiedVideos[u];
                                  return (
                                    <div key={u} style={{ display: 'flex', flexDirection: 'column', gap: 6, position: 'relative', zIndex: 1 }}>
                                      {!src ? (
                                        <div style={{
                                          width: '100%', maxWidth: 400, aspectRatio: '16/9',
                                          borderRadius: 10,
                                          background: '#f8fafc', display: 'flex',
                                          alignItems: 'center', justifyContent: 'center',
                                          color: '#94a3b8', fontSize: 13,
                                        }}>
                                          视频加载中...
                                        </div>
                                      ) : (
                                        <video
                                          src={src}
                                          controls
                                          playsInline
                                          preload="metadata"
                                          style={{
                                            maxWidth: '100%',
                                            borderRadius: 10,
                                          }}
                                        />
                                      )}
                                    </div>
                                  );
                                })}
                                {drawioUrls.map((u, idx) => {
                                  const saved = getSavedDiagramByUrl(u);
                                  return (
                                  <div
                                    key={`drawio-${idx}`}
                                    onClick={() => {
                                      if (taskId && saved) {
                                        navigate(`/repo/${taskId}/diagrams?open=${saved.diagramId}`);
                                      } else if (taskId) {
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
                                  <ChatMarkdown content={displayText} navigate={navigate} />
                                ) : (!msg.content && loading) ? (
                                  <div style={{ color: '#94a3b8', fontSize: 14, display: 'flex', alignItems: 'center', gap: 6 }}>
                                    <span style={{ display: 'inline-flex', gap: 3 }}>
                                      正在思考中
                                      <span style={{ display: 'inline-flex', gap: 2 }}>
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
                        ) : msg.content}
                      </div>
                    </motion.div>
                  ))}
                  <div ref={messagesEndRef} />
                </div>
              )}
            </div>

            {/* --- Footer: input --- */}
            <div style={{
              padding: '12px 20px 16px',
              borderTop: '1px solid #e2e8f0',
              background: '#fff',
              flexShrink: 0,
            }}>
              {/* Input bar */}
              <div style={{
                display: 'flex', alignItems: 'center', gap: 10,
                background: '#f8fafc',
                borderRadius: 28,
                padding: '4px 6px 4px 18px',
                border: '1px solid #e2e8f0',
              }}>
                <input
                  ref={inputRef}
                  value={inputValue}
                  onChange={(e) => setInputValue(e.target.value)}
                  onKeyDown={handleKeyPress}
                  placeholder="输入你的问题，我来帮你快速分析"
                  disabled={loading}
                  style={{
                    flex: 1, background: 'transparent',
                    border: 'none', outline: 'none',
                    color: '#1e293b', fontSize: 14, lineHeight: '40px',
                  }}
                />
              </div>
            </div>
          </motion.div>
          </>
        )}
      </AnimatePresence>

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

    </>
  );
};

export default ChatWidget;
