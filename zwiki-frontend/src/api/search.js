import api from './http';

export const SearchApi = {
  global: (q, limit = 20) => api.get('/search/global', { params: { q, limit } }),
};

export default SearchApi;
