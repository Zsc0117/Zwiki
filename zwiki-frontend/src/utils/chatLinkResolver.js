import { TaskApi } from '../api/task';

/**
 * 缓存所有项目的 URL → taskId 映射，用于 AI 聊天中点击仓库链接直接跳转项目文档页。
 */
let _cache = null;
let _loading = null;

function normalizeUrl(url) {
  if (!url) return '';
  return String(url)
    .trim()
    .replace(/\/+$/, '')
    .replace(/\.git$/, '')
    .toLowerCase();
}

/**
 * 加载并缓存所有任务的 URL→taskId 映射。
 * 返回 Map<normalizedUrl, taskId>
 */
async function loadTaskUrlMap() {
  if (_cache) return _cache;
  if (_loading) return _loading;

  _loading = (async () => {
    try {
      const res = await TaskApi.listAll();
      const map = new Map();
      const tasks = res?.data?.records || res?.data?.list || res?.data || [];
      if (Array.isArray(tasks)) {
        for (const t of tasks) {
          if (t.projectUrl && t.taskId) {
            map.set(normalizeUrl(t.projectUrl), t.taskId);
          }
        }
      }
      _cache = map;
      return map;
    } catch (e) {
      console.warn('[chatLinkResolver] load tasks failed:', e);
      return new Map();
    } finally {
      _loading = null;
    }
  })();

  return _loading;
}

/**
 * 判断 URL 是否是 GitHub/Gitee 仓库地址
 */
export function isRepoUrl(href) {
  if (!href) return false;
  return /^https?:\/\/(github\.com|gitee\.com)\/[^/]+\/[^/]+/i.test(href);
}

/**
 * 根据仓库 URL 查找对应的 taskId。
 * 返回 taskId 或 null。
 */
export async function resolveTaskId(repoUrl) {
  const map = await loadTaskUrlMap();
  const normalized = normalizeUrl(repoUrl);
  return map.get(normalized) || null;
}

/**
 * 清除缓存（例如新项目创建后）
 */
export function clearTaskUrlCache() {
  _cache = null;
}

/**
 * 在 catalogueTree 中递归查找匹配标题的节点，返回 { key, content } 或 null
 */
export function findCatalogueByTitle(catalogueTree, title) {
  if (!catalogueTree || !title) return null;
  const target = title.trim();
  for (const node of catalogueTree) {
    const nodeTitle = (node.title || node.name || '').trim();
    if (nodeTitle === target || nodeTitle.includes(target) || target.includes(nodeTitle)) {
      return { key: node.key, content: node.content, title: nodeTitle };
    }
    if (node.children && node.children.length > 0) {
      const found = findCatalogueByTitle(node.children, title);
      if (found) return found;
    }
  }
  return null;
}
