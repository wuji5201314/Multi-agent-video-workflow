// Vite 入口：样式 + 两个前端脚本（IIFE，执行即挂载）。
// app.js 负责「视频生成」工作区（window.__videoBoot），studio.js 负责首页/图片/配音/字幕工作区。
import './app.css';
import './app.js';
import './studio.js';
