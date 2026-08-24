import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5175,
    proxy: {
      // 开发环境代理到 Node 后端
      '/api': {
        target: process.env.API_BASE_URL || 'http://127.0.0.1:3000',
        changeOrigin: true,
      },
    },
  },
});
