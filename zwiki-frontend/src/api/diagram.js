import api from './http';

export const DiagramApi = {
  list: (taskId) =>
    api.get(`/diagram/list?taskId=${taskId}`),

  getDetail: (diagramId) =>
    api.get(`/diagram/${diagramId}`),

  create: (data) =>
    api.post('/diagram', data),

  update: (diagramId, data) =>
    api.put(`/diagram/${diagramId}`, data),

  delete: (diagramId) =>
    api.delete(`/diagram/${diagramId}`),
};

export default DiagramApi;
