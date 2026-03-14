import api from './http';

const gatewayUrl = process.env.REACT_APP_GATEWAY_URL || 'http://localhost:8992';

export const AuthApi = {
  me: (config) => api.get('/auth/me', config),
  logout: (config) => api.post('/auth/logout', null, config),
  getGithubLoginUrl: () => `${gatewayUrl}/oauth2/authorization/github`,
  getGiteeLoginUrl: () => `${gatewayUrl}/oauth2/authorization/gitee`,
  getLinkedAccounts: () => api.get('/auth/linked-accounts'),
  getBindUrl: (provider) => `${gatewayUrl}/api/auth/bindstart/${provider}`,
  unbindProvider: (provider) => api.post(`/auth/unbind/${provider}`),
};

export default AuthApi;
