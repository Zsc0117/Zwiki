import api from './http';

export const McpApi = {
  getConfig: (config) => {
    return api.get('/mcp/config', config);
  },

  saveConfig: (json, config) => {
    return api.put('/mcp/config', json, {
      ...config,
      headers: { 'Content-Type': 'application/json', ...(config?.headers || {}) },
    });
  },

  listServers: (config) => {
    return api.get('/mcp/servers', config);
  },

  testServer: (name, config) => {
    return api.post(`/mcp/servers/${encodeURIComponent(name)}/test`, null, config);
  },

  listTools: (name, config) => {
    return api.get(`/mcp/servers/${encodeURIComponent(name)}/tools`, config);
  },
};

export default McpApi;
