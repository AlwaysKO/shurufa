import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5175,
    proxy: {
      '/uploads': { target: process.env.API_BASE_URL || 'http://127.0.0.1:3000', changeOrigin: false },
      // 保留浏览器 Host，使登录 Origin 校验与开发代理一致
      '/api': {
        target: process.env.API_BASE_URL || 'http://127.0.0.1:3000',
        changeOrigin: false,
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq, req) => {
            // 与Nginx一致：覆盖访客自带的代理头，审计只接受实际连接来源。
            proxyReq.setHeader('X-Forwarded-For', req.socket.remoteAddress || '');
          });
        },
      },
    },
  },
});
