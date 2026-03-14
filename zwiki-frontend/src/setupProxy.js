const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function(app) {
  const target = process.env.API_PROXY_TARGET || process.env.REACT_APP_API_PROXY_TARGET || 'http://localhost:8992';
  console.log(`[setupProxy] Proxy target: ${target}`);

  const proxyConfig = {
    target,
    changeOrigin: true,
    logLevel: 'debug',
    cookieDomainRewrite: 'localhost',
    onProxyReq: (proxyReq, req) => {
      console.log(`[proxy] ${req.method} ${req.originalUrl} -> ${target}`);
    },
    onProxyRes: (proxyRes, req) => {
      console.log(`[proxy:res] ${req.method} ${req.originalUrl} <- ${proxyRes.statusCode}`);
    },
    onError: (err, req, res) => {
      console.error(`[proxy:error] ${req.method} ${req.originalUrl} -> ${target}:`, err.message);
      if (res && !res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ code: 502, message: `Proxy error: ${err.message}` }));
      }
    }
  };

  app.use(
    '/api',
    createProxyMiddleware({
      ...proxyConfig,
      pathRewrite: (path) => `/api${path}`,
    })
  );

  app.use(
    '/oauth2',
    createProxyMiddleware({
      ...proxyConfig,
      pathRewrite: (path) => `/oauth2${path}`,
    })
  );

  app.use(
    '/login/oauth2',
    createProxyMiddleware({
      ...proxyConfig,
      pathRewrite: (path) => `/login/oauth2${path}`,
    })
  );

  // WebSocket proxy for notifications
  // Use pathFilter instead of Express mount path to correctly handle WS upgrade events
  const wsTarget = process.env.WS_PROXY_TARGET || target;
  app.use(
    createProxyMiddleware({
      target: wsTarget,
      pathFilter: '/ws/notification/**',
      changeOrigin: true,
      ws: true,
      logLevel: 'debug',
      onProxyReqWs: (proxyReq, req, socket, options, head) => {
        console.log(`[proxy:ws] WebSocket ${req.url} -> ${wsTarget}`);
      },
      onError: (err, req, res) => {
        console.error(`[proxy:ws:error] ${req.url} -> ${wsTarget}:`, err.message);
      }
    })
  );
};