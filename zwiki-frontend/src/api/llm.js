import api from './http';

const API_BASE = '/llm';

export const LlmApi = {
  getModels: () => api.get(`${API_BASE}/models`),

  getAggregatedStats: () => api.get(`${API_BASE}/stats/all`),

  getKeys: () => api.get(`${API_BASE}/keys`),

  createKey: (key) => api.post(`${API_BASE}/keys`, key),

  updateKey: (keyId, key) => api.put(`${API_BASE}/keys/${keyId}`, key),

  deleteKey: (keyId) => api.delete(`${API_BASE}/keys/${keyId}`),

  getModelsByKey: (keyId) => api.get(`${API_BASE}/keys/${keyId}/models`),

  createModel: (keyId, model) =>
    api.post(`${API_BASE}/keys/${keyId}/models`, model),

  updateModel: (keyId, id, model) =>
    api.put(`${API_BASE}/keys/${keyId}/models/${id}`, model),

  deleteModel: (keyId, id) =>
    api.delete(`${API_BASE}/keys/${keyId}/models/${id}`),

  toggleModelEnabled: (keyId, id, enabled) =>
    api.post(`${API_BASE}/keys/${keyId}/models/${id}/toggle?enabled=${enabled}`),

  markModelHealthy: (modelName) => 
    api.post(`${API_BASE}/models/${modelName}/mark-healthy`),

  refreshModels: () => 
    api.post(`${API_BASE}/models/refresh`),

  getBalancerConfig: () => 
    api.get(`${API_BASE}/balancer/config`),

  updateBalancerConfig: (config) => 
    api.put(`${API_BASE}/balancer/config`, config),

  getKeyStats: () =>
    api.get(`${API_BASE}/stats/keys`),

  getProviderStats: () =>
    api.get(`${API_BASE}/stats/providers`),

  getUsageTrend: ({ dimensionType, dimensionId, startDate, endDate }) => {
    const params = new URLSearchParams();
    params.append('dimensionType', dimensionType);
    if (dimensionId) params.append('dimensionId', dimensionId);
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);
    return api.get(`${API_BASE}/stats/trend?${params.toString()}`);
  },
};
