/**
 * 将 AI 返回的 Markdown 文本转换为干净的纯文本，按点分行显示。
 * 去除 #、*、**、---、表格分隔线等 AI 样式符号。
 */
export function formatAiResponse(text) {
  if (!text) return '';

  return text
    // 去除代码块标记（保留内容）
    .replace(/```[\w]*\n?/g, '')
    // 去除标题标记 # ## ### 等
    .replace(/^#{1,6}\s*/gm, '')
    // 去除加粗 **text**
    .replace(/\*\*(.*?)\*\*/g, '$1')
    // 去除斜体 *text*
    .replace(/(?<!\*)\*(?!\*)(.*?)(?<!\*)\*(?!\*)/g, '$1')
    // 去除水平分割线
    .replace(/^[-*_]{3,}\s*$/gm, '')
    // 无序列表项：- * + 开头 → 用 • 替代
    .replace(/^[\s]*[-*+]\s+/gm, '• ')
    // 表格分隔行（|---|---|）→ 移除
    .replace(/^\|[-:\s|]+\|\s*$/gm, '')
    // 表格行：去掉外侧 | 并用空格分隔
    .replace(/^\|(.*)\|\s*$/gm, (_match, content) =>
      content
        .split('|')
        .map((c) => c.trim())
        .filter(Boolean)
        .join('  |  ')
    )
    // 去除行内代码反引号（保留内容）
    .replace(/`([^`]+)`/g, '$1')
    // 去除引用标记 >
    .replace(/^>\s?/gm, '')
    // 压缩连续空行
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/**
 * 规范化 AI 文本展示：
 * 保留 Markdown 语法（标题、加粗、斜体等），仅处理编号列表换行。
 * 将粘连的编号点（1. 2. 3.）拆成逐行显示，供 ReactMarkdown 正确渲染。
 */
export function normalizeAiTextForDisplay(text) {
  let out = String(text || '');
  if (!out) return '';

  out = out
    .replace(/\r\n?/g, '\n')
    // 标点后直接接编号，强制换行（保留 markdown 语法不动）
    .replace(/([：:。；;，,])\s*(\d{1,2}[\.、\)])\s*/g, '$1\n\n$2 ')
    // 编号项之间粘连："1.xxx 2.yyy" -> 换行
    .replace(/(\d{1,2}[\.、\)][^\n]{0,200}?)\s+(?=\d{1,2}[\.、\)])/g, '$1\n')
    // 压缩多余空行
    .replace(/\n{3,}/g, '\n\n')
    .trim();

  return out;
}

const normalizeWrappedDrawioUrls = (text) => {
  let out = String(text || '').replace(/\r\n?/g, '\n');
  // Some model outputs insert line breaks in long draw.io URLs.
  // Merge URL-safe continuation fragments back to one line.
  for (let i = 0; i < 8; i++) {
    const next = out.replace(
      /(https?:\/\/(?:(?:www\.)?draw\.io|embed\.diagrams\.net|app\.diagrams\.net|viewer\.diagrams\.net)[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]*)\s+([A-Za-z0-9%#?&=+/_\-.])/gi,
      '$1$2'
    );
    if (next === out) break;
    out = next;
  }
  return out;
};

export function extractDrawioUrls(text) {
  const raw = normalizeWrappedDrawioUrls(text);
  const urls = [];
  const drawioRegex = /https?:\/\/(?:(?:www\.)?draw\.io|embed\.diagrams\.net|app\.diagrams\.net|viewer\.diagrams\.net)[^\s)}\]"'<>*]*/gi;
  let m;
  while ((m = drawioRegex.exec(raw)) !== null) {
    const u = String(m[0] || '').replace(/[)。,，!?！？~～;；:：]+$/, '');
    if (u && !urls.includes(u)) urls.push(u);
  }
  return urls;
}

export function removeDrawioUrlsFromText(text, drawioUrls) {
  let out = String(text || '');
  for (const u of (drawioUrls || [])) {
    const escaped = u.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(escaped, 'g'), '');
  }
  out = out.replace(/\n{3,}/g, '\n\n').trim();
  return out;
}

export async function proxyMediaUrlsToMinio(content) {
  const { imageUrls, videoUrls } = extractMediaUrls(content);
  const allUrls = [...(imageUrls || []), ...(videoUrls || [])];
  if (allUrls.length === 0) return content;

  const results = await Promise.all(
    allUrls.map(async (url) => {
      try {
        const res = await fetch('/api/media/proxy-upload', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url }),
        });
        const json = await res.json();
        if (json.code === 200 && json.data) {
          return { original: url, minio: json.data };
        }
      } catch (e) {
        console.warn('[proxyMedia] proxy failed for', url, e);
      }
      return null;
    })
  );

  let replaced = content;
  for (const r of results) {
    if (r && r.minio) {
      replaced = replaced.replaceAll(r.original, r.minio);
    }
  }
  return replaced;
}

export function removeMediaUrlsFromText(text, mediaUrls) {
  let out = String(text || '');
  const urls = Array.from(new Set((mediaUrls || []).filter(Boolean)));
  for (const u of urls) {
    const escaped = u.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(escaped, 'g'), '');
  }
  out = out.replace(/(图片链接|视频链接)\s*[:：]\s*/g, '');
  out = out.replace(/\n{3,}/g, '\n\n').trim();
  return out;
}

export function extractMediaUrls(text) {
  const raw = String(text || '');
  const urls = new Set();
  const urlRegex = /https?:\/\/[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]+/gi;
  let m;
  while ((m = urlRegex.exec(raw)) !== null) {
    const u = String(m[0] || '').replace(/[)。,，!?！？~～;；:：]+$/, '');
    if (u) urls.add(u);
  }

  const imageUrls = [];
  const videoUrls = [];
  for (const u of urls) {
    if (/\.(png|jpe?g|webp|gif|bmp|svg)(\?|#|$)/i.test(u)) {
      imageUrls.push(u);
      continue;
    }
    if (/\.(mp4|webm|mov|m4v|m3u8)(\?|#|$)/i.test(u)) {
      videoUrls.push(u);
    }
  }

  return { imageUrls, videoUrls };
}
