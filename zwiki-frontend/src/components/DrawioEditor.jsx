import React, { useEffect, useRef, useCallback, useState, useMemo } from 'react';
import { Modal, Spin } from 'antd';
import pako from 'pako';

const DRAWIO_EMBED_URL = 'https://embed.diagrams.net/?embed=1&spin=1&proto=json&lang=zh&ui=kennedy';

const normalizeBase64 = (value) => {
  const normalized = String(value || '')
    .trim()
    .replace(/\s+/g, '')
    .replace(/-/g, '+')
    .replace(/_/g, '/');
  const padding = normalized.length % 4;
  return padding === 0 ? normalized : `${normalized}${'='.repeat(4 - padding)}`;
};

const safeDecodeURIComponent = (value) => {
  if (typeof value !== 'string') return value;
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
};

const decodeRepeatedly = (value, maxDepth = 3) => {
  let out = String(value ?? '');
  for (let i = 0; i < maxDepth; i++) {
    const decoded = safeDecodeURIComponent(out);
    if (decoded === out) break;
    out = decoded;
  }
  return out;
};

const normalizeDrawioUrlInput = (url) => {
  let out = String(url || '').trim();
  if (!out) return out;

  out = out.replace(/&amp;/g, '&');

  // Some model outputs may wrap links with markdown syntax.
  const markdownLink = out.match(/^\[[^\]]*\]\((https?:\/\/[^)]+)\)$/i);
  if (markdownLink?.[1]) {
    out = markdownLink[1];
  }

  // Merge URL-safe continuation fragments back to one line.
  for (let i = 0; i < 8; i++) {
    const next = out.replace(
      /(https?:\/\/(?:(?:www\.)?draw\.io|embed\.diagrams\.net|app\.diagrams\.net|viewer\.diagrams\.net)[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]*)\s+([A-Za-z0-9%#?&=+/_\-.])/gi,
      '$1$2'
    );
    if (next === out) break;
    out = next;
  }

  if (/^https?%3A%2F%2F/i.test(out)) {
    out = decodeRepeatedly(out, 2);
  }

  return out.trim();
};

/**
 * 尝试解压 @drawio/mcp 格式的压缩数据
 * @drawio/mcp 压缩流程: encodeURIComponent → pako.deflateRaw → base64
 * 因此解压流程: base64 decode → pako.inflateRaw → decodeURIComponent
 */
const tryInflateToString = (encoded) => {
  if (!encoded) return null;
  const raw = String(encoded).trim();
  
  // 候选值：原始数据和 URI 解码后的数据（处理 JSON 中 URL 编码的情况）
  const candidates = [raw];
  try {
    const uriDecoded = decodeURIComponent(raw);
    if (uriDecoded !== raw) {
      candidates.push(uriDecoded);
    }
  } catch {
    // ignore URI decode errors
  }

  for (const candidate of candidates) {
    try {
      const decoded = atob(normalizeBase64(candidate));
      const bytes = new Uint8Array(decoded.length);
      for (let i = 0; i < decoded.length; i++) {
        bytes[i] = decoded.charCodeAt(i);
      }
      
      // 尝试 inflateRaw（@drawio/mcp 使用 deflateRaw with windowBits=-15）
      let inflated = null;
      try {
        inflated = pako.inflateRaw(bytes, { to: 'string' });
      } catch {
        try {
          inflated = pako.inflate(bytes, { to: 'string' });
        } catch {
          // keep trying other candidates
          continue;
        }
      }
      
      if (inflated) {
        // @drawio/mcp 在压缩前进行了 encodeURIComponent，需要解码
        try {
          return decodeURIComponent(inflated);
        } catch {
          // 如果 URI 解码失败，返回原始 inflate 结果
          return inflated;
        }
      }
    } catch {
      // keep trying other candidates
    }
  }
  return null;
};

/**
 * 从 draw.io URL hash 中解析图表数据
 * 支持的格式：
 * - #R<base64> - Deflated + base64 编码的 XML
 * - #D<data> - 直接 URL 编码的数据
 * - #U<url> - 从远程 URL 加载
 * - ?mermaid=<code> - Mermaid 代码
 */
const parseDrawioUrlData = (url) => {
  try {
    const input = normalizeDrawioUrlInput(url);
    if (!input) return null;

    // Handle edge case: some URLs have #create= embedded in query string
    // e.g., ?grid=0&pv=0&border=10&edit=_blank#create={...}
    // Normalize by replacing #create= with %23create= so URL parser works correctly
    let normalizedUrl = input;
    if (input.includes('_blank#create=')) {
      normalizedUrl = input.replace('#create=', '%23create=');
    }

    // First check for #create= in the full URL string (could be in hash or query)
    const createMatch = input.match(/[?#&]create=([^&#]+)/);
    if (createMatch) {
      try {
        let createRaw = decodeRepeatedly(createMatch[1], 3);
        // Defensive: strip trailing garbage (e.g., markdown text appended after URL)
        // Find the end of JSON object by matching braces
        const jsonEnd = (() => {
          let depth = 0;
          let inString = false;
          let escape = false;
          for (let i = 0; i < createRaw.length; i++) {
            const ch = createRaw[i];
            if (escape) { escape = false; continue; }
            if (ch === '\\') { escape = true; continue; }
            if (ch === '"') { inString = !inString; continue; }
            if (!inString) {
              if (ch === '{') depth++;
              else if (ch === '}') {
                depth--;
                if (depth === 0) return i + 1;
              }
            }
          }
          return createRaw.length;
        })();
        createRaw = createRaw.substring(0, jsonEnd);
        const createObj = JSON.parse(createRaw);
        if (createObj?.type === 'mermaid' && createObj?.data) {
          if (createObj.compressed) {
            const inflated = tryInflateToString(createObj.data);
            if (!inflated) {
              console.warn('[DrawioEditor] Failed to inflate compressed mermaid payload, data prefix:', String(createObj.data).substring(0, 40));
              return null;
            }
            return { type: 'mermaid', data: inflated };
          }
          return { type: 'mermaid', data: safeDecodeURIComponent(createObj.data) };
        }
        if (createObj?.type === 'xml' && createObj?.data) {
          if (createObj.compressed) {
            const inflated = tryInflateToString(createObj.data);
            if (!inflated) {
              console.warn('[DrawioEditor] Failed to inflate compressed xml payload, data prefix:', String(createObj.data).substring(0, 40));
              return null;
            }
            return { type: 'xml', data: inflated };
          }
          return { type: 'xml', data: safeDecodeURIComponent(createObj.data) };
        }
      } catch (e) {
        console.warn('[DrawioEditor] Failed to parse #create payload:', e);
      }
    }

    // Try standard URL parsing
    const urlObj = new URL(normalizedUrl);

    // Check query params for mermaid
    const mermaidParam = urlObj.searchParams.get('mermaid');
    if (mermaidParam) {
      return { type: 'mermaid', data: mermaidParam };
    }

    // Check for create= in query params (when # was encoded)
    const createParam = urlObj.searchParams.get('create') || urlObj.searchParams.get('%23create') || urlObj.searchParams.get('#create');
    if (createParam) {
      try {
        const createObj = JSON.parse(decodeRepeatedly(createParam, 3));
        if (createObj?.type === 'mermaid' && createObj?.data) {
          if (createObj.compressed) {
            const inflated = tryInflateToString(createObj.data);
            if (inflated) return { type: 'mermaid', data: inflated };
          } else {
            return { type: 'mermaid', data: safeDecodeURIComponent(createObj.data) };
          }
        }
      } catch (e) {
        console.warn('[DrawioEditor] Failed to parse create param:', e);
      }
    }

    const hashIndex = input.indexOf('#');
    if (hashIndex === -1) return null;

    const hash = input.substring(hashIndex + 1);
    if (!hash) return null;

    if (hash.startsWith('create=')) {
      try {
        const createObj = JSON.parse(decodeRepeatedly(hash.substring('create='.length), 3));
        if (createObj?.type === 'mermaid' && createObj?.data) {
          if (createObj.compressed) {
            const inflated = tryInflateToString(createObj.data);
            if (inflated) return { type: 'mermaid', data: inflated };
          } else {
            return { type: 'mermaid', data: safeDecodeURIComponent(createObj.data) };
          }
        }
        if (createObj?.type === 'xml' && createObj?.data) {
          if (createObj.compressed) {
            const inflated = tryInflateToString(createObj.data);
            if (inflated) return { type: 'xml', data: inflated };
          } else {
            return { type: 'xml', data: safeDecodeURIComponent(createObj.data) };
          }
        }
      } catch (e) {
        console.warn('[DrawioEditor] Failed to parse hash create payload:', e);
      }
    }

    // Legacy format: #R<base64> - Deflated + base64 encoded XML
    const prefix = hash.charAt(0);
    const data = hash.substring(1);

    if (prefix === 'R') {
      // Deflated + base64 encoded XML
      try {
        const decoded = atob(data);
        const bytes = new Uint8Array(decoded.length);
        for (let i = 0; i < decoded.length; i++) {
          bytes[i] = decoded.charCodeAt(i);
        }
        const inflated = pako.inflateRaw(bytes, { to: 'string' });
        return { type: 'xml', data: decodeURIComponent(inflated) };
      } catch (e) {
        console.warn('[DrawioEditor] Failed to decompress #R data:', e);
        // Try using inflate instead of inflateRaw
        try {
          const decoded = atob(data);
          const bytes = new Uint8Array(decoded.length);
          for (let i = 0; i < decoded.length; i++) {
            bytes[i] = decoded.charCodeAt(i);
          }
          const inflated = pako.inflate(bytes, { to: 'string' });
          return { type: 'xml', data: decodeURIComponent(inflated) };
        } catch (e2) {
          console.warn('[DrawioEditor] Failed with inflate too:', e2);
          return null;
        }
      }
    } else if (prefix === 'D') {
      // URL encoded data (could be XML or diagram data)
      try {
        const decoded = decodeURIComponent(data);
        // Check if it's XML
        if (decoded.startsWith('<mxfile') || decoded.startsWith('<mxGraphModel')) {
          return { type: 'xml', data: decoded };
        }
        // Could be other format, try as XML anyway
        return { type: 'xml', data: decoded };
      } catch (e) {
        console.warn('[DrawioEditor] Failed to decode #D data:', e);
        return null;
      }
    } else if (prefix === 'U') {
      // Remote URL - not supported in embed mode
      console.warn('[DrawioEditor] #U (remote URL) format not supported');
      return null;
    }

    // Unknown format, try to decode as URL-encoded data
    try {
      const decoded = decodeURIComponent(hash);
      if (decoded.startsWith('<mxfile') || decoded.startsWith('<mxGraphModel')) {
        return { type: 'xml', data: decoded };
      }
    } catch { /* ignore */ }

    // Try as base64 decode (no compression)
    try {
      const decoded = atob(hash);
      if (decoded.startsWith('<mxfile') || decoded.startsWith('<mxGraphModel')) {
        return { type: 'xml', data: decoded };
      }
    } catch { /* ignore */ }

    return null;
  } catch (e) {
    console.error('[DrawioEditor] Error parsing draw.io URL:', e);
    return null;
  }
};

/**
 * DrawioEditor 支持两种数据来源：
 * 1. xmlData 模式：直接传入 XML 数据
 * 2. drawioUrl 模式：从 draw.io URL 中解析图表数据
 * 
 * 两种模式都使用 postMessage 协议与 embed.diagrams.net 通信
 */
const DrawioEditor = ({ open, onClose, xmlData, onSave, drawioUrl }) => {
  const iframeRef = useRef(null);
  const [loading, setLoading] = useState(true);
  const pendingXmlRef = useRef(null);
  const autoCapturePhaseRef = useRef(null);
  const autoCapturedXmlRef = useRef(null);
  const [parsedUrlData, setParsedUrlData] = useState(null);
  const [parseError, setParseError] = useState(false);

  // 解析 URL 数据
  useEffect(() => {
    if (drawioUrl && open) {
      const parsed = parseDrawioUrlData(drawioUrl);
      setParsedUrlData(parsed);
      if (!parsed) {
        console.warn('[DrawioEditor] Could not parse diagram data from URL:', drawioUrl);
        setParseError(true);
      } else {
        setParseError(false);
      }
    } else {
      setParsedUrlData(null);
      setParseError(false);
    }
  }, [drawioUrl, open]);

  // 确定要加载的数据（可能是 XML 或 Mermaid）
  const dataToLoad = useMemo(() => {
    if (xmlData) return { type: 'xml', data: xmlData, fromUrl: false };
    if (parsedUrlData) return { ...parsedUrlData, fromUrl: true };
    return { type: 'xml', data: '', fromUrl: false };
  }, [xmlData, parsedUrlData]);

  // 始终使用 embed 模式，通过 postMessage 加载数据
  const iframeSrc = DRAWIO_EMBED_URL;

  const sendMessage = useCallback((msg) => {
    if (iframeRef.current?.contentWindow) {
      iframeRef.current.contentWindow.postMessage(JSON.stringify(msg), '*');
    }
  }, []);

  const handleMessage = useCallback((evt) => {
    if (!evt.data || typeof evt.data !== 'string') return;

    let msg;
    try {
      msg = JSON.parse(evt.data);
    } catch {
      return;
    }

    if (msg.event === 'init') {
      setLoading(false);
      // 根据数据类型发送不同的加载消息
      if (dataToLoad.type === 'mermaid') {
        sendMessage({ action: 'load', descriptor: { format: 'mermaid', data: dataToLoad.data } });
      } else {
        sendMessage({ action: 'load', xml: dataToLoad.data || '' });
      }
      // Auto-capture: if loaded from sourceUrl, export xml+svg to persist in DB
      if (dataToLoad.fromUrl && dataToLoad.data) {
        setTimeout(() => {
          if (autoCapturePhaseRef.current !== null) return;
          autoCapturePhaseRef.current = 'xml';
          sendMessage({ action: 'export', format: 'xml' });
        }, 2500);
      }
    } else if (msg.event === 'save') {
      // save event contains xml field with the diagram XML
      autoCapturePhaseRef.current = null;
      autoCapturedXmlRef.current = null;
      pendingXmlRef.current = msg.xml || '';
      // Request SVG export for preview thumbnail
      sendMessage({ action: 'export', format: 'svg' });
    } else if (msg.event === 'export') {
      // Auto-capture phase 1: got XML, now request SVG
      if (autoCapturePhaseRef.current === 'xml') {
        autoCapturedXmlRef.current = msg.xml || msg.data || '';
        autoCapturePhaseRef.current = 'svg';
        sendMessage({ action: 'export', format: 'svg' });
        return;
      }
      // Auto-capture phase 2: got SVG, persist both
      if (autoCapturePhaseRef.current === 'svg') {
        const xml = autoCapturedXmlRef.current || '';
        const svg = msg.data || '';
        autoCapturePhaseRef.current = null;
        autoCapturedXmlRef.current = null;
        if (xml && onSave) {
          onSave(xml, svg, { autoCapture: true });
        }
        return;
      }
      // Normal save-triggered SVG export
      const xml = pendingXmlRef.current;
      pendingXmlRef.current = null;
      if (xml !== null && onSave) {
        onSave(xml, msg.data || '');
      }
    } else if (msg.event === 'exit') {
      onClose && onClose();
    }
  }, [dataToLoad, sendMessage, onSave, onClose]);

  useEffect(() => {
    if (!open) return;
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [open, handleMessage]);

  useEffect(() => {
    if (open) {
      setLoading(true);
      pendingXmlRef.current = null;
      autoCapturePhaseRef.current = null;
      autoCapturedXmlRef.current = null;
    }
  }, [open]);

  return (
    <Modal
      open={open}
      onCancel={() => onClose && onClose()}
      footer={null}
      width="100vw"
      style={{ top: 0, padding: 0, maxWidth: '100vw' }}
      styles={{ body: { height: 'calc(100vh - 55px)', padding: 0, overflow: 'hidden' } }}
      destroyOnHidden
      closable
      maskClosable={false}
    >
      {loading && !parseError && (
        <div style={{
          position: 'absolute', inset: 0, zIndex: 10,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          background: 'rgba(255,255,255,0.85)',
        }}>
          <Spin size="large">
            <div style={{ padding: 30, textAlign: 'center', color: '#8c8c8c', fontSize: 14 }}>加载绘图编辑器...</div>
          </Spin>
        </div>
      )}
      {parseError && drawioUrl && (
        <div style={{
          position: 'absolute', inset: 0, zIndex: 10,
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          background: '#fff', padding: 40, textAlign: 'center',
        }}>
          <div style={{ fontSize: 48, marginBottom: 16 }}>⚠️</div>
          <div style={{ fontSize: 18, fontWeight: 600, marginBottom: 8, color: '#1f2937' }}>
            无法解析图表数据
          </div>
          <div style={{ fontSize: 14, color: '#6b7280', marginBottom: 24, maxWidth: 400 }}>
            图表链接格式无法识别，无法还原原始图。请关闭后重试生成，或继续新建空白图。
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <button
              onClick={() => {
                setParsedUrlData(null);
                setParseError(false);
              }}
              style={{
                padding: '8px 16px', borderRadius: 6, border: '1px solid #0ea5e9',
                background: '#0ea5e9', color: '#fff', cursor: 'pointer', fontSize: 14,
              }}
            >
              继续新建空白图
            </button>
            <button
              onClick={() => onClose && onClose()}
              style={{
                padding: '8px 16px', borderRadius: 6, border: '1px solid #d1d5db',
                background: '#fff', color: '#374151', cursor: 'pointer', fontSize: 14,
              }}
            >
              关闭
            </button>
          </div>
        </div>
      )}
      {open && !parseError && (
        <iframe
          ref={iframeRef}
          src={iframeSrc}
          title="Draw.io Editor"
          style={{
            width: '100%',
            height: '100%',
            border: 'none',
          }}
          onLoad={() => {
            // iframe 加载完成后等待 init 事件
          }}
        />
      )}
    </Modal>
  );
};

export default DrawioEditor;
