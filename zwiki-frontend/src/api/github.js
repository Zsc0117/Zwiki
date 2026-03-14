import api from './http';

export const GithubApi = {
  listPrivateRepos: (q, config) =>
    api.get('/auth/github/repos', {
      ...(config || {}),
      params: { ...(config?.params || {}), q },
    }),
};

export default GithubApi;
