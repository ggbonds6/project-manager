import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 开发期后端口 = 8180（与 .env.example 的 WEB_PORT 默认值一致）。
    // 容器内部仍是 8080（nginx 反代 backend:8080），故后端启动时必须用
    // SERVER_PORT=8180 覆盖，见 deploy/windows/start-dev.cmd 与 README。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8180',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://127.0.0.1:8180',
        changeOrigin: true,
      },
    },
  },
});
