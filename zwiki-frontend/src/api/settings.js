import api from './http';

export const SettingsApi = {
  get: () => api.get('/auth/settings'),
  update: (data) => api.put('/auth/settings', data),
  sendTestEmail: () => api.post('/auth/settings/test-email'),
};

export default SettingsApi;
