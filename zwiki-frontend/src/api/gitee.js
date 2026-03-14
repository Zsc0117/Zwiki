import api from './http';

export const GiteeApi = {
  listPrivateRepos: (q, config) =>
    api.get('/auth/gitee/repos/private', {
      ...(config || {}),
      params: { ...(config?.params || {}), q },
    }),
};

export default GiteeApi;
