import axios from 'axios';

const normalizeApiBaseUrl = (raw) => {
  let v = raw || '/api';
  if (typeof v !== 'string') {
    return '/api';
  }
  v = v.trim();
  if (!v) {
    return '/api';
  }
  if (v.endsWith('/')) {
    v = v.slice(0, -1);
  }
  if (v === 'http://localhost:8992' || v === 'http://127.0.0.1:8992') {
    return '/api';
  }
  if (v.startsWith('http') && !v.endsWith('/api')) {
    return `${v}/api`;
  }
  return v;
};

const baseURL = normalizeApiBaseUrl(process.env.REACT_APP_API_BASE_URL);

if (process.env.NODE_ENV === 'development') {
  console.log('[http] baseURL =', baseURL);
}

const api = axios.create({
  baseURL,
  timeout: 30000,
  withCredentials: true,
});

api.interceptors.request.use(
  (config) => {
    // Sa-Token: 优先使用satoken
    const satoken = localStorage.getItem('satoken');
    if (satoken) {
      config.headers = config.headers || {};
      config.headers['satoken'] = satoken;
    }
    
    // 兼容旧的Bearer token
    const token = localStorage.getItem('token') || localStorage.getItem('access_token');
    if (token && !satoken) {
      config.headers = config.headers || {};
      if (!config.headers.Authorization) {
        config.headers.Authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`;
      }
    }
    const userId = localStorage.getItem('userId');
    if (userId) {
      config.headers = config.headers || {};
      config.headers['X-User-Id'] = userId;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

api.interceptors.response.use(
  (response) => {
    const responseUrl = response?.request?.responseURL;
    const contentType = response?.headers?.['content-type'] || response?.headers?.['Content-Type'] || '';
    const isHtml =
      typeof response?.data === 'string' &&
      response.data.length > 0 &&
      (response.data.trim().startsWith('<!DOCTYPE') || response.data.trim().startsWith('<html'));
    const isOAuthRedirect =
      typeof responseUrl === 'string' &&
      (responseUrl.includes('/oauth2/authorization/') ||
        responseUrl.includes('/login/oauth2/') ||
        responseUrl.includes('github.com/login') ||
        responseUrl.includes('gitee.com/oauth'));

    // 只有在明确是 OAuth 重定向或返回了 HTML 登录页面时才报登录失效
    // 避免误判正常的 API 响应
    if (isOAuthRedirect || (isHtml && contentType.includes('text/html'))) {
      console.warn('[http] Detected OAuth redirect or HTML response:', responseUrl);
      const e = new Error('AUTH_REDIRECT');
      e.normalized = {
        message: '登录已失效，请重新登录',
        status: 401,
        data: undefined,
      };
      return Promise.reject(Object.assign(e, { response }));
    }

    const data = response.data;
    if (data && typeof data === 'object' && !Array.isArray(data)) {
      if (data.message !== undefined && data.msg === undefined) {
        data.msg = data.message;
      }
    }
    return data;
  },
  (error) => {
    const responseUrl = error?.request?.responseURL;
    console.error(
      `[http] Error:`,
      error.code,
      error.message,
      error?.config?.baseURL,
      error?.config?.url,
      responseUrl
    );
    const isOAuthRedirect =
      typeof responseUrl === 'string' &&
      (responseUrl.includes('/oauth2/authorization/') ||
        responseUrl.includes('/login/oauth2/') ||
        responseUrl.includes('github.com/login') ||
        responseUrl.includes('gitee.com/oauth'));

    const is401 = isOAuthRedirect || error?.response?.status === 401;

    // /auth/me 和 /auth/logout 不触发重定向，让 AuthContext 处理
    const requestUrl = error?.config?.url || '';
    const isAuthCheck = requestUrl.includes('/auth/me') || requestUrl.includes('/auth/logout');

    if (is401 && !isAuthCheck && window.location.pathname !== '/login') {
      localStorage.removeItem('satoken');
      localStorage.removeItem('userId');
      localStorage.removeItem('token');
      localStorage.removeItem('access_token');
      // 也清除后端session cookie，防止/api/auth/me仍返回成功导致循环
      api.post('/auth/logout').catch(() => {}).finally(() => {
        window.location.href = '/login';
      });
      return Promise.reject(error); // 立即返回，后续逻辑在finally中处理
    }

    const normalized = {
      message:
        error?.response?.data?.message ||
        error?.response?.data?.msg ||
        (is401 ? '登录已失效，请重新登录' : error?.message) ||
        'Network Error',
      status: error?.response?.status,
      data: error?.response?.data,
    };
    return Promise.reject(Object.assign(error, { normalized }));
  }
);

export { api };
export default api;
