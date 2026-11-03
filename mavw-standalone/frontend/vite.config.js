import { defineConfig } from 'vite';

// 前端独立开发：`npm run dev` 起 5173；所有 /api 请求代理到 Java 后端 8088，
// Java 后端再转发给 Python 渲染引擎 8010。前端无需关心后端拆分。
export default defineConfig({
  server: {
    host: '127.0.0.1',
    port: 5173,
    open: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8088',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
