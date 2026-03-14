import api from './http';

export const GitHubReviewConfigApi = {
  list: (config) =>
    api.get('/auth/github/review-config', config),

  getById: (id, config) =>
    api.get(`/auth/github/review-config/${id}`, config),

  create: (data, config) =>
    api.post('/auth/github/review-config', data, config),

  update: (id, data, config) =>
    api.put(`/auth/github/review-config/${id}`, data, config),

  delete: (id, config) =>
    api.delete(`/auth/github/review-config/${id}`, config),

  tokenStatus: (config) =>
    api.get('/auth/github/review-config/token-status', config),
};

export default GitHubReviewConfigApi;
