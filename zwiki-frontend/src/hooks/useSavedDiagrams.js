import { useState, useRef, useEffect, useCallback } from 'react';
import { DiagramApi } from '../api/diagram';
import { extractDrawioUrls } from '../components/formatAiResponse';

const DIAGRAM_KEYWORDS = ['架构图', '类图', '类关系图', '时序图', '序列图', 'ER图', '部署图', '流程图', '模块图', '关系图', '组件图'];

export const normalizeDiagramUrl = (url) => String(url || '').trim().replace(/&amp;/g, '&');

const deriveDiagramBaseName = (question, fallbackName) => {
  const q = String(question || '');
  for (const keyword of DIAGRAM_KEYWORDS) {
    if (q.includes(keyword)) return keyword;
  }
  return fallbackName;
};

const useSavedDiagrams = ({
  taskId,
  defaultBaseName = 'AI生成图表',
  logPrefix = 'SavedDiagrams',
} = {}) => {
  const [savedDrawioDiagrams, setSavedDrawioDiagrams] = useState({}); // normalizedUrl -> {diagramId, name}
  const savedDrawioDiagramsRef = useRef({});

  useEffect(() => {
    savedDrawioDiagramsRef.current = savedDrawioDiagrams;
  }, [savedDrawioDiagrams]);

  const hydrateSavedDrawioDiagrams = useCallback(async () => {
    if (!taskId) return;
    try {
      const res = await DiagramApi.list(taskId);
      if (res?.code !== 200 || !Array.isArray(res.data)) return;

      const fromServer = {};
      for (const item of res.data) {
        const key = normalizeDiagramUrl(item?.sourceUrl);
        if (!key) continue;
        fromServer[key] = { diagramId: item.diagramId, name: item.name };
      }

      if (Object.keys(fromServer).length > 0) {
        setSavedDrawioDiagrams((prev) => ({ ...fromServer, ...prev }));
      }
    } catch {
      // ignore hydration failure
    }
  }, [taskId]);

  const getSavedDiagramByUrl = useCallback((url) => {
    const key = normalizeDiagramUrl(url);
    if (!key) return null;
    return savedDrawioDiagramsRef.current[key] || null;
  }, []);

  const autoSaveDrawioDiagrams = useCallback(async (botContent, userQuestion) => {
    if (!taskId) return { firstDiagram: null, newCount: 0, totalCount: 0 };

    const drawioUrls = extractDrawioUrls(botContent)
      .map(normalizeDiagramUrl)
      .filter(Boolean);

    if (drawioUrls.length === 0) {
      return { firstDiagram: null, newCount: 0, totalCount: 0 };
    }

    let firstDiagram = null;
    let newCount = 0;
    const baseName = deriveDiagramBaseName(userQuestion, defaultBaseName);

    for (let i = 0; i < drawioUrls.length; i++) {
      const url = drawioUrls[i];
      const existing = savedDrawioDiagramsRef.current[url];
      if (existing) {
        if (!firstDiagram) firstDiagram = existing;
        continue;
      }

      try {
        const name = drawioUrls.length > 1 ? `${baseName} (${i + 1})` : baseName;
        const res = await DiagramApi.create({
          taskId,
          name,
          sourceUrl: url,
        });

        if (res?.code === 200 && res.data) {
          const savedItem = { diagramId: res.data.diagramId, name: res.data.name };
          if (!firstDiagram) firstDiagram = savedItem;
          newCount += 1;

          savedDrawioDiagramsRef.current = {
            ...savedDrawioDiagramsRef.current,
            [url]: savedItem,
          };
          setSavedDrawioDiagrams((prev) => ({ ...prev, [url]: savedItem }));
        }
      } catch (e) {
        console.warn(`[${logPrefix}] auto-save drawio diagram failed:`, e);
      }
    }

    return { firstDiagram, newCount, totalCount: drawioUrls.length };
  }, [taskId, defaultBaseName, logPrefix]);

  return {
    savedDrawioDiagrams,
    hydrateSavedDrawioDiagrams,
    getSavedDiagramByUrl,
    autoSaveDrawioDiagrams,
  };
};

export default useSavedDiagrams;
