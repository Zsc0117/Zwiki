import api from './http';

export const WikiWebhookApi = {
  list: () => api.get('/auth/wiki-webhook'),
  getByTask: (taskId) => api.get(`/auth/wiki-webhook/by-task/${taskId}`),
  create: (data) => api.post('/auth/wiki-webhook', data),
  update: (id, data) => api.put(`/auth/wiki-webhook/${id}`, data),
  delete: (id) => api.delete(`/auth/wiki-webhook/${id}`),
};

export default WikiWebhookApi;
