import { defineConfig } from 'vite';

// 前端独立开发服务器（可选）。
// 默认推荐直接用 aimedia-launcher 一个 jar 打开 http://127.0.0.1:8088/ 即可（它已托管本目录静态页）。
// 若想让前端热更新单独跑 5173，则：npm install && npm run dev，
// 此时 /api 需要代理到 Java launcher(8088) 或直接到 Python(8010)。
export default defineConfig({
  root: '.',
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
