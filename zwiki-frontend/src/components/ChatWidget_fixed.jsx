import React, { useState, useRef, useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { 
  message
} from 'antd';
import { 
  CloseOutlined,
  CodeOutlined,
  DeleteOutlined
} from '@ant-design/icons';
import { motion, AnimatePresence } from 'framer-motion';
import { extractMediaUrls, extractDrawioUrls, removeDrawioUrlsFromText, formatAiResponse } from './formatAiResponse';
import { ChatHistoryApi } from '../api/chatHistory';
import { useAuth } from '../auth/AuthContext';

const QUICK_QUESTIONS = [
  { text: '项目架构图解' },
  { text: '这个项目用了哪些技术栈？' },
  { text: '帮我解释一下微服务架构' },
  { text: 'Spring AI 是什么？' },
];

const LOGIN_REQUIRED_REPLY = '你还没有登录，请你先登录才能使用完整功能哦';

const ChatWidget = ({ darkMode: _darkMode = false, taskId }) => {
  const location = useLocation();
  const isRepoDetailPage = /^\/repo\//.test(location.pathname);
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState([]);
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [previewUrl, setPreviewUrl] = useState(null);
  const [videoErrors, setVideoErrors] = useState(new Set());
  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);
  const { me } = useAuth();
  const userId = me?.userId;

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
  }, [isOpen, userId, taskId]);

  const handleClearHistory = async () => {
    if (!userId) return;
    try {
      await ChatHistoryApi.clear(userId, taskId || null);
      setMessages([]);
      message.success('聊天记录已清除');
    } catch (e) {
      console.error('[ChatWidget] clear history failed:', e);
      message.error('清除失败');
    }
  };

  const sendMessage = async () => {
    if (!inputValue.trim() || loading) return;
    const query = inputValue.trim();
    setInputValue('');
    setLoading(true);

    const botId = Date.now().toString();
    const userMsg = { id: Date.now().toString(), type: 'user', content: query };
    const botMsg = { id: botId, type: 'bot', content: '' };
    setMessages(prev => [...prev, userMsg, botMsg]);

    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: query, userId, taskId }),
      });

      if (!res.ok) throw new Error(`HTTP ${res.status}`);

      const reader = res.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let accumulated = '';
      let currentEvent = null;
      let streamDone = false;
      let hasErrorEvent = false;
      let errorMessage = '';

      while (!streamDone) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split('\n');

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
            continue;
          }
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (data === '[DONE]') {
              streamDone = true;
              break;
            }
            if (currentEvent === 'message') {
              accumulated += data;
              setMessages(prev => prev.map(m => m.id === botId ? { ...m, content: accumulated } : m));
            } else if (currentEvent === 'error') {
              hasErrorEvent = true;
              errorMessage = data || '抱歉，服务出现问题，请稍后重试。';
              setMessages(prev => prev.map(m => m.id === botId ? { ...m, content: errorMessage } : m));
              streamDone = true;
              break;
            }
          }
        }
      }

      const botContent = hasErrorEvent
        ? (errorMessage || '抱歉，服务出现问题，请稍后重试。')
        : (accumulated || '抱歉，我目前无法回答你的问题。');
      if (!accumulated && !hasErrorEvent) {
        setMessages(prev => prev.map(m => m.id === botId ? { ...m, content: botContent } : m));
      }
      persistMessages(query, botContent);
    } catch (error) {
      console.error('Chat SSE error:', error);
      setMessages(prev => prev.map(m => m.id === botId ? { ...m, content: '抱歉，服务出现问题，请稍后重试。' } : m));
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
                <CloseOutlined style={{ color: '#94a3b8', fontSize: 14 }} />
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
                        whileHover={{ borderColor: '#2563eb', color: '#2563eb' }}
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
                          background: '#dbeafe',
                          color: '#2563eb',
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
                        background: msg.type === 'user' ? '#2563eb' : '#ffffff',
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
                            const formatted = formatAiResponse(textOnly);
                            return (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                                {(imageUrls || []).map((u) => (
                                  <div key={u} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                    <img
                                      src={u}
                                      alt="generated"
                                      onDoubleClick={() => setPreviewUrl(u)}
                                      style={{ maxWidth: '100%', borderRadius: 10, border: '1px solid #e2e8f0', cursor: 'zoom-in' }}
                                    />
                                  </div>
                                ))}
                                {(videoUrls || []).map((u) => (
                                  <div key={u} style={{ display: 'flex', flexDirection: 'column', gap: 6, position: 'relative', zIndex: 1 }}>
                                    {videoErrors.has(u) ? (
                                      <div
                                        onClick={() => window.open(u, '_blank')}
                                        style={{
                                          position: 'relative',
                                          width: '100%',
                                          maxWidth: 400,
                                          aspectRatio: '16/9',
                                          borderRadius: 10,
                                          border: '1px solid #e2e8f0',
                                          background: '#f8fafc',
                                          display: 'flex',
                                          alignItems: 'center',
                                          justifyContent: 'center',
                                          cursor: 'pointer',
                                          overflow: 'hidden',
                                        }}
                                      >
                                        <div style={{ textAlign: 'center', color: '#64748b', fontSize: 14, padding: 16 }}>
                                          <div style={{ fontSize: 32, marginBottom: 8 }}>🎬</div>
                                          <div>视频加载失败</div>
                                          <div style={{ fontSize: 12, marginTop: 4, color: '#94a3b8' }}>点击新窗口播放</div>
                                        </div>
                                      </div>
                                    ) : (
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
                                          border: '1px solid #e2e8f0',
                                          pointerEvents: 'auto',
                                          userSelect: 'none'
                                        }}
                                        onError={(e) => {
                                          console.error('Video load error:', e);
                                          setVideoErrors(prev => new Set([...prev, u]));
                                        }}
                                        onLoadStart={() => console.log('Video load start:', u)}
                                        onCanPlay={() => console.log('Video can play:', u)}
                                      />
                                    )}
                                    <button
                                      onClick={() => window.open(u, '_blank')}
                                      style={{
                                        padding: '4px 8px',
                                        fontSize: 11,
                                        borderRadius: 4,
                                        border: '1px solid #d1d5db',
                                        background: '#fff',
                                        color: '#374151',
                                        cursor: 'pointer',
                                        transition: 'all 0.2s',
                                      }}
                                      onMouseEnter={(e) => {
                                        e.target.style.background = '#f3f4f6';
                                        e.target.style.borderColor = '#9ca3af';
                                      }}
                                      onMouseLeave={(e) => {
                                        e.target.style.background = '#fff';
                                        e.target.style.borderColor = '#d1d5db';
                                      }}
                                    >
                                      新窗口打开
                                    </button>
                                  </div>
                                ))}
                                {drawioUrls.map((u, idx) => (
                                  <div
                                    key={`drawio-${idx}`}
                                    onClick={() => window.open(u, '_blank')}
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
                                      <div style={{ fontWeight: 600, fontSize: 13, color: '#0369a1' }}>在 Draw.io 中打开图表</div>
                                      <div style={{ fontSize: 11, color: '#64748b', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                        点击在浏览器中编辑此图表
                                      </div>
                                    </div>
                                    <span style={{ color: '#0ea5e9', fontSize: 16 }}>→</span>
                                  </div>
                                ))}
                                {formatted ? (
                                  <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7, fontSize: 14 }}>
                                    {formatted}
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
              {messages.length > 0 && (
                <div style={{ display: 'flex', justifyContent: 'center', marginTop: 8 }}>
                  <motion.button
                    whileHover={{ color: '#ef4444' }}
                    whileTap={{ scale: 0.95 }}
                    onClick={handleClearHistory}
                    style={{
                      background: 'none', border: 'none', cursor: 'pointer',
                      color: '#94a3b8', fontSize: 12, display: 'flex', alignItems: 'center', gap: 4,
                    }}
                  >
                    <DeleteOutlined style={{ fontSize: 11 }} />
                    清除聊天记录
                  </motion.button>
                </div>
              )}
            </div>
          </motion.div>
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
