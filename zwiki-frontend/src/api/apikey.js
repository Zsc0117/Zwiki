import api from './http';

export const ApiKeyApi = {
  list: () => api.get('/auth/apikey/list'),
  create: (name) => api.post('/auth/apikey/create', { name }),
  reveal: (keyId) => api.get(`/auth/apikey/${keyId}/reveal`),
  delete: (keyId) => api.delete(`/auth/apikey/${keyId}`),
};

export default ApiKeyApi;
