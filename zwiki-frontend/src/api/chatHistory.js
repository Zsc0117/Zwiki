import api from './http';

export const ChatHistoryApi = {
  getHistory: (userId, taskId, config) => {
    const params = { userId };
    if (taskId) params.taskId = taskId;
    return api.get('/chat/history', { params, ...config });
  },

  saveMessage: (message, config) => {
    return api.post('/chat/history', message, config);
  },

  saveBatch: (messages, config) => {
    return api.post('/chat/history/batch', messages, config);
  },

  clearHistory: (userId, taskId, config) => {
    const params = { userId };
    if (taskId) params.taskId = taskId;
    return api.delete('/chat/history', { params, ...config });
  }
};

export default ChatHistoryApi;
