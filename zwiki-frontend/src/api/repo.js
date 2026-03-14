import api from './http';

export const RepoApi = {
  getFileTree: (taskId, config) =>
    api.get(`/task/repo/tree?taskId=${taskId}`, config),

  getFileContent: (taskId, path, config) =>
    api.get(`/task/repo/file?taskId=${taskId}&path=${encodeURIComponent(path)}`, config),

  getSymbolAnnotations: (taskId, path, config) =>
    api.get(`/task/repo/symbol-annotations?taskId=${taskId}&path=${encodeURIComponent(path)}`, config),

  getSymbolReferences: (taskId, symbol, defPath, defLine, config) =>
    api.get(`/task/repo/symbol-references?taskId=${taskId}&symbol=${encodeURIComponent(symbol)}${defPath ? `&defPath=${encodeURIComponent(defPath)}&defLine=${defLine}` : ''}`, config),
};

export default RepoApi;
