# mavw-standalone — 标准前后端分离工程

这是一套**独立、标准**的前后端工程，不依赖仓库里任何历史复刻结构：

```
mavw-standalone/
├── backend/          Spring Boot 后端（IDEA 里对 MavwApplication 点 Run 启动，端口 8088）
│   └── src/main/java/com/mavw/standalone/
│       ├── MavwApplication.java      ← 启动类（就是这个）
│       ├── config/AppConfig.java     ← RestClient + CORS
│       ├── controller/*.java         ← /api/** 接口层（Health/Image/Audio/Subtitle/Task）
│       └── proxy/*.java              ← 转发给 Python 渲染引擎
└── frontend/         独立前端（package.json + Vite，npm run dev 启动，端口 5173）
    ├── package.json
    ├── vite.config.js               ← /api 代理到 8088
    ├── index.html
    └── src/ (main.js / app.js / studio.js / app.css)
```

**架构**：`前端(5173) → Java 后端(8088) → Python 渲染引擎(8010)`

- 前端：标准 SPA，`npm install && npm run dev`，所有 `/api` 请求经 Vite proxy 打到 Java 后端。
- Java 后端：完整的 `/api/**` REST 接口层（类型化 Controller），内部用 `RestClient` 转发 Python。
- Python：保留原有出图 / TTS / 字幕 / 渲染能力（本工程不重写渲染逻辑）。

---

## 一、IDEA 里启动（推荐）

仓库根 `.run/` 目录已内置运行配置，右上角运行下拉框直接可见：

| 运行配置 | 说明 | 端口 |
| --- | --- | --- |
| **后端 · Python 服务 (:8010)** | Python 渲染引擎（必须先起） | 8010 |
| **标准 · 后端 Java (MavwApplication :8088)** | Spring Boot 后端，对 MavwApplication 直接 Run | 8088 |
| **标准 · 前端 npm dev (:5173)** | 前端 Vite dev server | 5173 |
| **标准 · 启动全部** | 上面三个一键组合启动 | 8010/8088/5173 |

**三步走**：
1. 先跑「后端 · Python 服务 (:8010)」（渲染能力所在）。
2. 再跑「标准 · 后端 Java (MavwApplication :8088)」。
3. 最后跑「标准 · 前端 npm dev (:5173)」，浏览器自动打开 http://127.0.0.1:5173/ 。

> 首次导入：IDEA 打开项目后，右键 `mavw-standalone/backend/pom.xml → Add as Maven Project`（或右下角提示点 Load），
> 才会把 `MavwApplication` 识别为可运行主类。前端首次需在 `mavw-standalone/frontend` 下执行 `npm install`。

## 二、命令行启动

```powershell
# 1) Python 渲染引擎
cd D:\AGI\Multi-agent-video-workflow\backend-python
.\.venv\Scripts\python.exe run.py --port 8010

# 2) Java 后端（另开一个终端）
cd D:\AGI\Multi-agent-video-workflow\mavw-standalone\backend
mvn spring-boot:run
#   或： mvn -DskipTests package 后 java -jar target\mavw-standalone-backend-0.1.0.jar

# 3) 前端（再开一个终端）
cd D:\AGI\Multi-agent-video-workflow\mavw-standalone\frontend
npm install
npm run dev
```

打开 http://127.0.0.1:5173/ 。

## 二·五、双击 .bat 启动（不依赖 IDEA，启动文件都在各自目录）

| 双击这个文件 | 启动什么 | 端口 |
| --- | --- | --- |
| `backend-python\start.bat` | Python 渲染引擎 | 8010 |
| `mavw-standalone\backend\start-backend.bat` | Java 后端（有 jar 直接跑 jar，没有才用 Maven 打） | 8088 |
| `mavw-standalone\frontend\start-frontend.bat` | 前端 Vite（首次自动 npm install） | 5173 |
| **`mavw-standalone\start-all.bat`** | **三个一键全启并自动打开浏览器** | 8010→8088→5173 |
| `mavw-standalone\stop-all.bat` | 停止上面三个端口的服务 | — |

> 启动文件都就地放在对应模块目录，不集中塞在别处；`start-all.bat` 只是便利入口。

## 三、构建产物

```powershell
# 前端出静态包到 dist/
cd mavw-standalone\frontend && npm run build

# 后端起 fat jar
cd mavw-standalone\backend && mvn -DskipTests package
```

## 四、接口清单（Java 后端对外，内部转发 Python）

| 分组 | 路径 |
| --- | --- |
| 健康 | `GET /api/health` |
| 图片 | `GET /api/images/options`、`POST /api/images/generate`、`POST /api/images/upload`、`POST /api/images/analyze`、`GET /api/images/history`、`DELETE /api/images/{id}`、`GET /api/images/files/{name}`、`GET /api/images/uploads/{name}` |
| 配音 | `GET /api/audio/voices`、`POST /api/audio/tts`、`GET /api/audio/history`、`DELETE /api/audio/{id}`、`GET /api/audio/files/{name}` |
| 字幕 | `GET /api/subtitle/options`、`POST /api/subtitle/build`、`GET /api/subtitle/history`、`DELETE /api/subtitle/{id}`、`GET /api/subtitle/files/{name}` |
| 视频任务 | `GET /api/tasks`、`POST /api/tasks`、`GET /api/tasks/{id}`、`POST /api/tasks/{id}/advance`、`POST /api/tasks/{id}/gates/{step}`、`GET /api/tasks/{id}/materials/{index}`、`GET /api/tasks/{id}/artifacts/{aid}` |

Python 未启动时，接口返回 `502` + `{"error":"python_unavailable","message":"..."}`，前端据此提示。

## 五、配置

后端 `backend/src/main/resources/application.yml`：

```yaml
server.port: 8088
mavw.python-base-url: http://127.0.0.1:8010   # Python 渲染引擎地址
mavw.proxy.request-timeout-ms: 600000          # 渲染类接口超时（10 分钟）
mavw.cors-allowed-origins: [ http://127.0.0.1:5173 ]
```

前端 `frontend/vite.config.js` 里 `/api` 的 `target` 默认 `http://127.0.0.1:8088`。
