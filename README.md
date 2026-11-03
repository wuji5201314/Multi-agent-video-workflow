# Multi-agent-video-workflow

把「内容生成 → 成品渲染 → 发布」做成一条**可以随时停下来改、改完接着跑**的自媒体流水线。

它融合了两个项目的长处：

| 来源 | 拿走了什么 |
| --- | --- |
| yq-admin 的 `aimedia` 模块 | 业务字段与状态语义（选题/素材/审核/成品任务、"生成链路"的分段方式） |
| YanQue-AI（LangGraph 内容生成） | 编排骨架：人工确认、打回重做、改稿、断点续跑 |
| Multi-Agent-Video-Creation（MoneyPrinterTurbo 系） | 渲染能力：TTS 配音 + 逐词时间轴、字幕烧录、镜头动效、ffmpeg 合成、BGM 混音 |

一句话区别：**MPT 选定参数后只能一口气跑完；本项目每一步产出后都停在 GATE 上等你点头、等你改、或者等你打回。**

## 在 IDEA / PyCharm 里一键启动（推荐）

仓库根 `.run/` 目录里已内置可直接运行的配置（打开项目后，IDE 右上角运行下拉框就能看到）：

| 运行配置 | 作用 | 访问地址 |
| --- | --- | --- |
| **后端 · Python 服务 (:8010)** | 起 Python FastAPI 后端（真实能力：出图/TTS/渲染/字幕） | http://127.0.0.1:8010/ |
| **后端 · Python 开发热重载 (:8010)** | 同上，改代码自动重载（开发用） | http://127.0.0.1:8010/ |
| **后端 · aimedia 启动器 (MavwApplication :8088)** | 起 Spring Boot 启动器：托管 `backend-java/frontend` 静态页 + 把 `/api/**` 反代到 Python | http://127.0.0.1:8088/ |
| **前端 · 打开页面** | 用 IDE 内置浏览器打开页面 | http://127.0.0.1:8088/ |
| **合并 · 启动后端并打开页面** | 一次跑起 Python 后端 + aimedia 启动器 + 打开浏览器 | http://127.0.0.1:8088/ |

**三种启动方式，按需选一：**

- **只想看效果**：跑 `后端 · Python 服务 (:8010)`，浏览器打开 http://127.0.0.1:8010/ 即可（前端由 Python 端静态托管，零构建）。
- **走 Java 后端 + 前端（推荐标准结构）**：先跑 `后端 · Python 服务 (:8010)`，再跑 `后端 · aimedia 启动器 (MavwApplication :8088)`，打开 http://127.0.0.1:8088/ 。前端所有 `/api` 请求由 Java 启动器透传到 Python，前端无需改任何地址。也可直接双击 `backend-java/aimedia-module/start-backend.bat`。
- **前端单独热更（可选）**：`cd backend-java/frontend` 后 `npm install && npm run dev`，打开 http://127.0.0.1:5173/ ，Vite 会把 `/api` 代理到 8088。
- **命令行（只看 Python）**：`cd backend-python` 后 `.\.venv\Scripts\python.exe run.py --port 8010`（或 `scripts\serve.py --port 8010`）。

**首次在 IDEA 打开时若未识别解释器**：`File → Project Structure → SDKs` 添加 `backend-python\.venv\Scripts\python.exe`（Python 3.13），并把项目 SDK 指到它。`.idea` 里已把源码根设为 `backend-python/src`。

> **启动器放哪、为什么这样放**：
> - 后端启动类在 `backend-java/aimedia-module/src/main/java/com/yanque/aimedia/MavwApplication.java`（包内 `launcher/` 下是 Web 配置与 `/api` 反代）。
> - 前端启动文件在 `backend-java/frontend/`（`package.json` / `vite.config.js` / `start-frontend.bat`）。
> - `backend-java/aimedia-module` 里的原有 controller/service 依赖 `yq-admin` 父工程的 `com.yanque.common.*`，全量编译会飘红（属预期的"对照代码"）。启动器通过 Maven `launcher` profile 的 compiler `excludes` 只编译 `MavwApplication + launcher/**`，从而可独立打包运行：
>   - 打包：`backend-java/aimedia-module` 下 `mvn -Plauncher -DskipTests package` → 产出 `target/aimedia-launcher.jar`
>   - 在 IDEA 里跑：运行配置已带 `-Dspring.profiles.active=launcher`；若 IDE 直接 Make 报全量编译错，请在 Maven 面板勾选 `launcher` profile 只编译启动器。
> - 端口约定：Python `8010`、aimedia 启动器 `8088`、前端 dev server `5173`（可选）。

## 快速开始

```powershell
cd D:\AGI\Multi-agent-video-workflow\backend-python
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install fastapi "uvicorn[standard]" httpx pydantic pillow edge-tts imageio-ffmpeg python-dotenv

# 全自动出一条片（不依赖 MySQL / TOS / Java）
.\.venv\Scripts\python.exe scripts\demo.py --brief "AI剪辑入门" --auto

# 交互式：每个 GATE 停下来等你确认/打回
.\.venv\Scripts\python.exe scripts\demo.py --brief "AI剪辑入门"

# 从中断处继续（进程重启也没关系，状态在 output/pipeline.db）
.\.venv\Scripts\python.exe scripts\demo.py --task-id 1 --auto

# 起服务（HTTP 接口 + Java 对接入口）
.\.venv\Scripts\python.exe scripts\serve.py --port 8010
```

产物：

```
output/pipeline.db                       任务/步骤/GATE 决策/产物 全部落库
output/assets/materials/task-<id>/*.png  素材（无现成配图时用 Pillow 生成的占位卡）
output/assets/preview-<id>/*.mp4|*.png   成片与封面
output/publish/publish-<id>-*.json       发布单（标题/文案/标签/成片路径）
```

## 五个步骤，四个 GATE

| 步骤 | 产出 | 停下来等你做什么 |
| --- | --- | --- |
| TOPIC | 3 条选题候选 | 选哪一条 / 自己改方向 |
| SCRIPT | 分镜口播稿 + 标题/文案/标签 | 改稿、增减镜头、打回重写 |
| MATERIAL | 每个镜头的配图（本地素材库或占位卡） | 换图、删镜头、打回重配 |
| RENDER | 1080x1920 MP4（配音+字幕+镜头动效） | 审片，不满意打回（后续步骤产物自动失效） |
| PUBLISH | 发布单 JSON（可选 webhook 推送） | 收尾 |

决策只有三种：`approve`（通过）/ `edit`（带着修改再跑一遍）/ `reject`（打回重做并作废下游产物）。

## 用到了哪些模型

| 环节 | 模型 | 接口 | 配置键 |
|------|------|------|--------|
| 选题 / 分镜口播 / 配图描述 | `deepseek-ai/deepseek-v4.1-flash` | NVIDIA NIM `POST /v1/chat/completions` | `BASE_URL` `API_KEY` `LLM_MODEL` |
| 镜头配图 | `black-forest-labs/flux-3-image-klein` | NVIDIA NIM `POST /v1/genai/{model}` | `IMAGE_PROVIDER=nvidia` `IMAGE_API_KEY` `IMAGE_MODEL` |
| 配音 | edge-tts（微软神经 TTS，带词级时间轴） | 本地库 | `MEDIA_RENDER_TTS_*` |
| 字幕 | ASS + libass | ffmpeg 烧录 | `MEDIA_RENDER_SUBTITLE_*` |
| 组装 | ffmpeg（Ken Burns / concat / 混音） | imageio-ffmpeg 自带二进制 | `MEDIA_RENDER_*` |

素材准备是**三级回退**（`pipeline/service.py` 的 `_step_material`）：

```
人工指定的 image_path → 本地素材库 PIPELINE_MATERIAL_DIR → 模型出图 → Pillow 占位卡
```

只有同时没配 key、没素材目录时才用占位卡，所以「离线也能跑通」和「接模型出真图」是同一套代码。

### 出图的两个坑

1. **比例**：flux 的 `aspect_ratio` 合法值里没有 `9:16`（只有 `9:21 / 1:2 / 2:3 / 3:4 / 1:1 / 4:3 ...`）。代码用 `1:2` 生成，再由 `generate/nvidia.py` 的 `cover_resize` 等比缩放后居中裁成 1080×1920，画面不变形。
2. **文字**：flux 画中文 UI 会得到满屏伪汉字。所以 prompt 里硬性要求「实拍照片，不要界面/截图/文字」；`_material_prompt` 还会把含「界面 / 按钮 / 截图 / 进度条」的描述自动改写成实拍隐喻。文案侧的 `image_query` 也在 prompt 里被约束成「只写人物、场景、物件、光线、景别」。

### 数字人画中画（可选层）

`DIGITAL_HUMAN_ENABLED=true` 会在成片右上角叠一个圆角小窗，里面是「对着镜头说话」的数字人。
它只加一层画面，**不改状态机、不改 API、不改 Java 契约**，关掉就和原来一模一样。

为什么挂在 RENDER 而不是 MATERIAL：数字人是音频驱动，而口播音频要到 RENDER 阶段的
`_synthesize` 才生成。所以小窗在 `renderer._build_shots` 之后、烧字幕之前合成——
一次覆盖全片，人物形象全程连续，不会在镜头切换处「换脸」；字幕最后烧，永远压在最上层。

三个 provider，按显存换：

| provider | 显存 | 用途 |
| --- | --- | --- |
| `placeholder` | 0 | Pillow + FFmpeg 画的占位主播，离线可跑，用来先定版式 |
| `wav2lip` | 2-4GB | 本地对口型，人脸来自参考图，小窗下精度足够 |
| `api` | 0 | 远程推理，本地零算力，效果最好 |

摆位 `DIGITAL_HUMAN_PIP_POSITION` 支持四个角；选 `bottom-*` 时会用
`compose.subtitle_reserve()` 自动让出字幕高度与顶部平台 UI，不会压字。

生成结果按「参考图 + 音频 + 尺寸 + provider」的内容哈希缓存在 `output/digital-human-cache/`：
GATE 上只改标题文案（音频没变）时直接命中，不用重烧几分钟算力。
provider 出任何错都只记 warning 并出原片，不会让一条已渲染完成的片子作废。

单独调这一层（不用跑整条管线）：

```powershell
$env:DIGITAL_HUMAN_ENABLED='true'
.\.venv\Scripts\python.exe scripts\diag_digital_human.py --duration 4 --position top-right
```

产物在 `output/diag/digital-human/`，含 `avatar.mp4`（provider 原始输出）、
`picture.mp4`（叠加后画面）和若干抽帧，用来核对位置与遮挡。

### 视频模型现状

`wan2.2-animate-2-14b` 目前**调不通**：`POST https://ai.api.nvidia.com/v1/genai/wan-ai/wan2.2-animate-2-14b` 返回 404（换成 `integrate.api.nvidia.com`、`/v1/video/generations` 等路径同样 404），说明这个 key 的账号还没对该模型开通 API。另外它本身是 **video editing（图+动作视频 → 动画）**，不是文生视频。

在开通之前，镜头的动态由 ffmpeg 的 Ken Burns 推拉平移提供（`compose.py` 的 `render_image_clip`）。

## 前端工作台（backend-java/frontend/）

起服务后直接打开 <http://127.0.0.1:8010/>，就是一个 Apple 风格的单页工作台：左边任务列表，右边步骤进度 + 当前步骤产出 + GATE 操作条。

![工作台](docs/screenshots/workspace.png)

能力：

- 新建任务：一句话简报 → 自动选题
- 选题：三张卡片点选，再按「通过」
- 脚本：标题 / 封面标题 / 文案 / 标签 / 每个镜头的大字与口播都能直接改
- 素材：看缩略图，把 `image_path` 换成自己的图片路径即用真图
- 成片：内置播放器，直接看 1080×1920 的 MP4
- 发布：标题、文案、标签、平台与发布单
- 三种决策：通过 / 带着修改重跑 / 打回重做

前端代码放在 **`backend-java/frontend/`**：它将来跟 yq-admin 的 web 端一起走，和 Java 侧同属一个部署单元。目录里只有 `index.html`、`app.css`、`app.js` 三个文件，零构建、无打包步骤。

本地开发时 Python 会把该目录用 `StaticFiles` 挂在根路径（目录由 `WEB_ROOT` 决定，默认自动探测 `../backend-java/frontend`，找不到就退化成纯 API 服务）；页面调用的是同源的 `/api/*`。如果前端由 yq-admin 的 web 容器托管在别的端口，跨源请求已由 `CORS_ORIGINS` 放行（默认 `*`）。后端只多了两个只读路由：素材原图 `/api/tasks/{id}/materials/{index}` 与产物本体 `/api/tasks/{id}/artifacts/{aid}`（都只放行数据库里登记过的路径）。

## 目录里的 Java 代码是什么

`java/aimedia-module/` 是从 yq-admin 原样复制过来的 `com.yanque.aimedia` 包（187 个文件中占 133 个 java/xml），**不是本服务的运行时**，作用是字段与状态语义的对照物：枚举、实体、Mapper、Handler、Controller 都保持你原来的样子，方便以后并回 yq-admin 时逐项对齐。

真正跑起来的是 `src/mavw`（Python）。两者没有代码级耦合，唯一契约是 `/ai/content-generation/topics/previews/render` 这个同名同形的接口。

## 配置

全部走环境变量，见 [.env.example](.env.example)。默认值是「离线也能出片」：

- 没有大模型 key → 用内置模板生成选题与口播稿；
- 没有配图 → 用 Pillow 生成竖屏素材卡；
- 没有 ffmpeg → 用 `imageio-ffmpeg` 自带二进制；
- 配音默认 `edge-tts`（免费，无需 key，带逐词时间轴）；
- 存储默认落本地，`STORAGE_BACKEND=presign` 才回 yq-admin 取 TOS 预签名。

## 和 Java（yq-admin）对接

服务暴露了与 yq-admin `ContentGenerationPythonClient.renderPreview` **同名同形**的路径：

```
POST /ai/content-generation/topics/previews/render
{ "preview_task_id": 123, "format": "VIDEO", "cover_title": "...",
  "publish_caption": "...", "tags": ["..."],
  "items": [{ "need_id": "N01", "asset_type": "GENERATED_IMAGE", "source_url": "https://...",
              "headline": "...", "body": "...", "duration_ms": 7000 }] }
→ { "format": "VIDEO", "cover_object_key": "...", "cover_file_size": 0,
    "video_object_key": "...", "video_file_size": 0, "duration_ms": 32490, "items": [] }
```

把 yq-admin 的 `yanque-ai.base-url` 指到本项目端口，并配上 `STROAGE_BACKEND=presign` 与
`YANQUE_INTERNAL_API_KEY`，即可由 Java 侧发起渲染、拿回 object key 落库。

## 已验证

- `scripts/demo.py --brief "AI剪辑入门" --auto`：5 步全绿，产出 1080x1920 / 30fps / h264 + aac / 28.41s 的成片，镜间静音 0 处 ≥0.5s；
- `scripts/smoke_gates.py`：选题确认、脚本打回重跑、改稿保留、进程重启续跑、下游失效重置，6 条断言全通过；
- 坐标自测：抽取第 8 秒画面确认「卡片标题 + 底部标签 + 字幕」三层不重叠。

## 目录

仓库里是两个**互相独立**的后端目录，各自用对应的 IDE 打开即可，不会互相索引：

```
Multi-agent-video-workflow/
├── backend-python/            ← PyCharm 打开这一层
│   ├── src/mavw/
│   │   ├── config.py          全部开关（30+ 项）
│   │   ├── storage.py         本地存储 / TOS 预签名存储
│   │   ├── llm.py             选题与脚本（大模型 + 内置模板兜底）
│   │   ├── render/            tts / subtitle / compose / ffmpeg_tools / card(PIL) / renderer
│   │   ├── pipeline/          步骤定义与可中断状态机（sqlite 落库）
│   │   ├── publish/           发布适配（dry-run 发布单 / webhook）
│   │   └── api.py, main.py    FastAPI 接口层
│   ├── scripts/               demo / serve / smoke / 诊断脚本
│   ├── reference/mpt/         渲染层原始参考实现
│   ├── sql/schema.sql         管线库可读快照
│   ├── output/                运行产物（已 gitignore）
│   ├── .venv/                 虚拟环境（已 gitignore）
│   ├── pyproject.toml
│   └── .env.example
├── backend-java/              ← IDEA 打开这一层
│   ├── aimedia-module/
│   │   ├── pom.xml            仅为 IDE 索引提供的骨架（见下）
│   │   └── src/main/java/com/yanque/aimedia/...
│   └── frontend/              前端（index.html + app.css + app.js）
└── docs/                      两边共享的文档
```

## 用两个 IDE 打开

| 工具 | 打开路径 | 做什么 |
|------|----------|--------|
| **PyCharm** | `D:\AGI\Multi-agent-video-workflow\backend-python` | 唯一的运行时。解释器选 `backend-python\.venv\Scripts\python.exe`，源码根 `src` |
| **IDEA** | `D:\AGI\Multi-agent-video-workflow\backend-java` | Java 对照代码 + 前端页面。打开后 Maven 自动识别 `aimedia-module\pom.xml`，`frontend` 作为静态资源目录 |

两边各自打开子目录，所以 PyCharm 不会去索引 `.java`，IDEA 也不会去索引 Python，虚拟环境与构建缓存互不干扰。

**IDEA 里飘红是预期的。** `backend-java` 是从 yq-admin 原样复制过来的 `com.yanque.aimedia` 包，其中 46 处引用了 yq-admin 的公共模块 `com.yanque.common.*`（用户上下文、统一响应、异常基类），这些不在本仓库里。`pom.xml` 只是为了让 IDEA 把 `src/main/java` 认成源码根、能跳转和全局搜索，**不是可独立构建的工程**。真要编译与运行，把 `aimedia-module/src` 合并回 yq-admin 对应模块。

如果连飘红都不想看，`backend-java` 当成纯文本对照目录也行：IDEA 里 `File → Open` 选这个文件夹，不加载 Maven 项目即可。

## 这台机器上的 Git 已经修好了

原先 `D:\Git\Git` 只有 `git-remote-ftp.exe`，缺 `git-remote-http.exe` 与 `git-remote-https.exe`
（同目录下 `git-http-backend`、`git-http-fetch`、`git-http-push` 都在，唯独这两个不见，像是被清理工具
单独删掉的），所以 Git Bash 里 `git clone/push https://...` 一律报 `git: 'remote-https' is not a git command`。

现在已从便携版 MinGit 补回这两个文件：

```
D:\Git\Git\mingw64\libexec\git-core\git-remote-http.exe
D:\Git\Git\mingw64\libexec\git-core\git-remote-https.exe
```

Git Bash 与 cmd 里直接用原生 `git` 即可，不再需要包装脚本。万一哪天又坏了（比如重装 Git 前），
兜底方案是 `D:\AGI\tools\git-gh.cmd`——它用 `D:\AGI\tools\MinGit` 的 git，并设置
`GIT_SSL_CAINFO`（MinGit 编译期证书路径指向不存在的 `C:/Program Files/Git/...`，不设就报
`error adding trust anchors`）与 `GIT_EXEC_PATH`。

## 边界

- 发布只到「发布单」：抖音/小红书真实上传要 Cookie 与分片上传，扩展点已留在 `publish/adapters.py`；
- 素材优先顺序：人工指定 → `PIPELINE_MATERIAL_DIR` 素材目录 → 模型出图（`IMAGE_PROVIDER=nvidia`）→ Pillow 占位卡；
- 素材库/图片生成的在线供应商（pexels、seedance 等）未接入，接口位置在 `pipeline/service.py` 的 `_step_material`。
