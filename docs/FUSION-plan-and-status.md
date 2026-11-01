# aimedia × Multi-Agent-Video-Creation 融合方案（渲染层已落地）

> 分析 + 方案；实现进度见第 9 节（渲染层已落地并自测通过）。
> 仓库源码已抓取到本地基线：`D:\AGI\mavc-src`（52 文件 / 约 1.1MB，GitHub raw 抓取，git clone 因 https helper 缺失失败）。

---

## 0. 结论先行

**推荐方案 A：服务化融合** —— 仓库改写为独立 Python「成片渲染服务」，Java 侧 aimedia 保持编排主导（状态机 / 审核 / TOS / 素材库 / 发布策略），把原来只会产 `IMAGE_CAROUSEL` 的成品环节升级为「图片轮播 或 MP4 成片」双格式。

- 本地链路（DB 状态机、TOS、审核、权限、素材库）全是 Java 资产，不能搬走；
- 仓库是 ffmpeg / moviepy / 十几家云 SDK 的重 Python 依赖，移植到 Java 不现实；
- YanQue-AI 源码不在本机（Java 只指向 `http://127.0.0.1:8000` / `YANQUE_AI_BASE_URL`），不适合把仓库硬塞进去。

---

## 1. 项目 A：yq-admin / aimedia（编排层，已存在）

### 1.1 内容生产链路（Java 编排 + Python LangGraph）

```
选题生成 → 人工确认选题(resume) → 资料检索 → 文章生成
  → 素材匹配(ai_content_material_task/item)
  → 内容审核(ai_content_review_task/issue, AWAITING_APPROVAL|REJECTED|FAILED)
  → 人工 approve → 成品任务(ai_content_preview_task/item)
  → Python 渲染 → 目前只有 IMAGE_CAROUSEL
```

### 1.2 长视频素材工厂（8 步状态机）

`PREPARE_VIDEO(10) → EXTRACT_AUDIO(20) → ASR_SUBMIT(30) → ASR_QUERY(40) → TRANSCRIPT_REFINEMENT(50) → MATERIAL_SEGMENT_GENERATION(60) → MATERIAL_CLIP_RENDER(70) → MATERIAL_INDEX(80)`

- `AiMediaProcessBiz`：handler 注册表 + 线程池，先落库再异步 `execute(taskId)`；
- `AiMediaProcessTaskService`：每步一个 `@Transactional` 收口（startStep / completeXxx / fail / failStaleStep），`video_id` 唯一键防并发，失败保留 `current_step` 供手动重试；
- `AiMediaFfmpegService`：`extractMp3`（16k 单声道 64k mp3）、`renderVideoClip`（`-ss/-t` + libx264 veryfast crf22 + aac 128k + faststart）；
- 存储：TOS 预签名（`presignDownload(key,1800)`、`existsWithSize` 上传完整性校验）。

### 1.3 三个必须正视的缺口

| 缺口 | 现状证据 |
|---|---|
| 成品只有图片轮播 | `AiContentPreviewTaskService.approveAndCreate` 与 `AiContentReviewTaskService.saveResult` 都硬编码 `format=IMAGE_CAROUSEL`，后者直接拒绝其它格式；`video_object_key` 字段空转 |
| 无配音 / 无字幕 / 无 BGM | 全模块无 TTS、无字幕烧录、无配乐，只有 ASR 反向转写 |
| 无成片合成、无发布 | 只有 clip 级切片；发布仅有 `publish_caption` / `tags` 字段，全仓库无任何 `PublishService` / `upload_post` 等价物 |

> 即：**「生成链路」的后半段（配音→字幕→成片→发布）正是仓库补得上的部分。**

---

## 2. 项目 B：Multi-Agent-Video-Creation（生成能力层）

本质是 **MoneyPrinterTurbo 的下游分支**（README 标题即 MoneyPrinterTurbo；`docs/skill/SKILL.md` 元数据 `upstream=harry0703/MoneyPrinterTurbo`），"Multi-Agent" 体现在 `docs/skill/` 的 Agent 入口（SKILL.md + mpt_agent.py）与网关式多供应商接入，而不是独立的 Agent 编排框架。

### 2.1 能力盘点（按可复用价值排序）

| 模块 | 体量 | 能力 | 对 aimedia 的价值 |
|---|---|---|---|
| `app/services/voice.py` | 155KB / ~3700 行 | 15 家 TTS（Edge 免费、Azure v1/v2、SiliconFlow、Gemini、MiMo、MiniMax、ElevenLabs、Chatterbox、Kokoro、Fish Audio、VoxCPM、OpenAI 兼容）；词级时间戳 `sub_maker`；静音音频；语速/音量；分段拼接 | ★★★ 补齐配音 |
| `app/services/subtitle.py` | 12KB | 字幕生成（sentence / word_by_word、pop_spring 弹跳动画、圆角背景、字体字形探测） | ★★★ 补齐字幕 |
| `app/services/video.py` | 86KB / ~1950 行 | `combine_videos`：画幅适配 cover/contain、转场、字幕烧录、BGM 混音、ffmpeg concat、编码器探测与回退；`render_image_zoom_video` 图片 Ken Burns | ★★★ 成片合成（含把现有图片素材变动态片段） |
| `app/services/material.py` | 127KB / ~2900 行 | 素材获取：Pexels/Pixabay/Coverr 库存；Seedance/OFox/MuAPI/WaveSpeed/秘塔MiniMax/LoomLoom AI 视频；OpenAI 兼容文生图→转片段；素材缓存、时长覆盖、按脚本顺序 | ★★☆ 素材源扩展 |
| `app/services/task.py` | 77KB / ~1900 行 | `_run_pipeline`：脚本→关键词→配音→字幕→素材→成片→跨平台发布；stop_at 分段复用（script/terms/audio/subtitle/materials/video） | ★★★ 直接作为渲染服务骨架 |
| `app/services/upload_post.py` | 16KB | Upload-Post 异步跨平台发布（TikTok / Instagram / YouTube Shorts），逐平台结果校验、后台轮询、owner 恢复 | ★★☆ 发布参考实现 |
| `app/services/bgm.py` + `elevenlabs_music.py` + `sonilo.py` | 18KB+16KB+16KB | AI 生成 BGM / 本地 BGM 混音 | ★★☆ 配乐 |
| `app/services/llm.py` | 51KB | 多供应商 LLM 适配（脚本、关键词） | ★☆☆ 与 YanQue-AI 重叠，可不接 |
| `app/models/schema.py` `VideoParams` | — | 全参数：画幅/片段时长/转场/字幕样式字体描边/音色音量语速/BGM/素材源/并发 | ★★★ 直接映射为渲染请求契约 |
| `app/services/video_project.py` + `docs/video-projects.md` | 36KB | 可编辑分镜工程：快照/不可变版本/产物 SHA-256 复用/单场景重渲 | ★★☆ 后续「改一个镜头」能力 |
| `state.py` + `controllers/manager` | — | 任务状态（内存 / Redis）+ 任务队列 | ★☆☆ Java 已有更完整状态机 |

### 2.2 现有对外接口

`POST /api/v1/videos`、`/subtitle`、`/audio`、`GET /api/v1/tasks`、`/tasks/{id}`、`DELETE /videos/{id}`、`/stream/{path}`、`/download/{path}`，另有 BGM 与本地素材上传接口；`video_source` 支持 `pexels/pixabay/coverr/local/wavespeed/volcengine_seedance/ofox/metaso_minimax/muapi/loomloom/openai_image`。

---

## 3. 融合架构（方案 A）

```
yq-admin (Java, 编排与持久化主体)
├─ ContentGenerationPythonClient  → YanQue-AI     选题/文章/素材匹配/审核     【不动】
├─ AiMediaMaterialPythonClient    → YanQue-AI     长视频素材分段              【不动】
├─ AiMediaFfmpegService           长视频切片 + TOS 素材库                    【不动】
└─ 【新增】VideoRenderPythonClient → MPT-Render (Python)
        POST /api/v1/render/preview   提交渲染 job（幂等键）
        GET  /api/v1/render/tasks/{id} 进度 / 结果 / 错误
        ← 返回 MP4 直传 TOS 的 object_key + 时长 + 封面 + 字幕文件

发布层【新增】
├─ P1  OpenPostAdapter（TikTok / IG / YouTube，复用 upload_post.py）
└─ P2  DouyinPublishAdapter（复用 DouyinCollection 登录态或开放平台 OAuth）
```

### 3.1 一次成品渲染的时序

```
审核 approve
 → AiContentPreviewTaskService.approveAndCreate(format=VIDEO|IMAGE_CAROUSEL)
 → buildRenderPayload(): items[{asset_type, source_url(TOS 预签名), script_segment, duration_ms}]
 → VideoRenderPythonClient.submit(job_id=preview_task_id)
 → Python: 下载素材 → TTS(分段) → 字幕时间轴 → 素材对齐 → combine_videos → 上传 TOS
 → Java 定时轮询 → 落库 video_object_key / duration / cover → 任务完成
 → 发布任务（人工或自动）→ 平台作品链接回填
```

### 3.2 能力归属表

| 环节 | 现状 | 融合后归属 |
|---|---|---|
| 选题 / 文章 / 素材匹配 / 审核 | aimedia + YanQue-AI | 不变 |
| 脚本分镜（按段落切分 + 关键词） | 无 | Java 新增分段表 → 传 Python；关键词由 Python `llm.py` 或 YanQue-AI 二选一 |
| 配音 TTS | 无 | Python `voice.py`（默认 Edge TTS，免 Key） |
| 字幕 | 无（只有 ASR 转写） | Python `subtitle.py`（TTS 词级时间轴） |
| 素材 | 本地库 clip + 生成图 | 三路优先级：本地库 clip → AI 生成（Seedance/MuAPI/OFox）→ 库存（Pexels）；生成图走 `render_image_zoom_video` |
| 成片合成 | 只有切片 | Python `combine_videos`（画幅/转场/BGM/字幕烧录） |
| 成品存储 | TOS | Python 直传 TOS 返回 key（Java 只需校验 exists/size） |
| 审核 | ai_content_review_task | 不变，新增「成片审核」可选节点 |
| 发布 | 无 | P1 OpenPost（海外）；P2 抖音/视频号适配器 |

---

## 4. 接口契约草案

### 4.1 提交渲染

```json
POST /api/v1/render/preview
{
  "job_id": "1843",
  "format": "VIDEO",
  "aspect": "9:16",
  "script_segments": [
    {"need_id": "m1", "text": "开头钩子……", "asset_type": "VIDEO_CLIP",
     "source_url": "https://tos...presigned", "duration_ms": 4200},
    {"need_id": "m2", "text": "……", "asset_type": "GENERATED_IMAGE",
     "source_url": "https://tos...presigned", "duration_ms": 3800}
  ],
  "voice": {"name": "zh-CN-XiaoxiaoNeural-Female", "rate": 1.0, "volume": 1.0},
  "subtitle": {"enabled": true, "mode": "sentence", "position": "bottom",
               "font": "STHeitiMedium.ttc", "size": 60, "color": "#FFFFFF",
               "stroke_color": "#000000", "stroke_width": 1.5, "animation": "pop_spring"},
  "bgm": {"type": "random", "volume": 0.2},
  "cover_title": "……",
  "output": {"bucket": "yq-media", "prefix": "ai/preview/1843/"}
}
→ 202 {"accepted": true, "job_id": "1843", "render_task_id": "rt-8f3a"}
```

### 4.2 轮询结果

```json
GET /api/v1/render/tasks/rt-8f3a
{
  "state": "complete",
  "progress": 100,
  "video_object_key": "ai/preview/1843/final-1.mp4",
  "video_url": "https://tos...", "duration_ms": 41230,
  "cover_object_key": "ai/preview/1843/cover.jpg",
  "subtitle_object_key": "ai/preview/1843/subtitle.srt",
  "warnings": []
}
```

### 4.3 Java 侧异常约定（沿用现有风格）
`[PYTHON_REQUEST_TIMEOUT]` / `[PYTHON_SERVICE_UNAVAILABLE]` / `[RENDER_JOB_FAILED]` / `[RENDER_ASSET_DOWNLOAD_FAILED]`

---

## 5. 分阶段任务与工作量

### P0 打通最小闭环（3–5 人日）
1. 仓库 fork 新增 `app/controllers/v1/render.py` + `app/services/render_job.py`：无状态 job（`storage/render_jobs/{job_id}`），复用 `task._run_pipeline(stop_at="video", video_source="local")`；
2. `material.py` 新增 `download_remote_material(url)`（大小/时长/超时/直链校验）；
3. Java 新增 `VideoRenderPythonClient` + `AiContentPreviewTaskService` 的 `format` 参数化（`IMAGE_CAROUSEL` / `VIDEO`）+ 轮询落库；
4. 打通：审核 approve → 渲染 → TOS 校验 → `video_object_key` 落库。

**验收**：一条真实文章 → 9:16 MP4（有配音、有字幕、素材来自本地库 clip）→ TOS 可播放。

### P1 能力补齐（5–8 人日）
- 素材三路优先级与降级；生成图 Ken Burns 动效；BGM（本地 + AI）；字幕样式全参数；封面首帧
- Python 直传 TOS（AK/SK 配置）+ 幂等键 + 收费保护（Seedance/MuAPI 预算开关、dry-run）
- 失败分级：素材不足 → 降级库存 → 仍失败则回落 IMAGE_CAROUSEL（保证链路不断）

### P2 发布（3–5 人日）
- `ai_content_publish_task`（平台、状态机、失败重试、作品 URL 回填）
- P2a `OpenPostAdapter` 复用 `upload_post.py`（TikTok/IG/YouTube）
- P2b `DouyinPublishAdapter`（复用 `DouyinCollection` 已有登录态，或抖音开放平台 OAuth——需你确认凭据来源）

### P3 增强（5–10 人日）
- `video_project` 分镜工程接入（只重渲改动场景）
- Agent 化：按内容类型自动选素材源/音色/时长/BGM（SKILL.md 的 Agent 入口思路）
- 批量生成（`video_count`）、A/B 封面、数据回流

---

## 6. 文件级改动清单

**Python（仓库 fork）**
- 新增 `app/controllers/v1/render.py`、`app/services/render_job.py`、`app/models/render_schema.py`
- 改 `app/services/material.py`（远程素材下载）、`app/services/task.py`（job 隔离目录 + 远程素材入口）
- 改 `app/config/config.py` + `config.example.toml`：`[render]`、`[tos]`
- 复用不动：`voice.py`、`subtitle.py`、`video.py`、`bgm.py`、`utils/*`

**Java（yq-admin/aimedia）**
- 新增 `service/VideoRenderPythonClient.java`、`service/AiContentVideoRenderService.java`、`enums/PreviewFormat.java`
- 改 `service/AiContentPreviewTaskService.java`（去掉硬编码 `IMAGE_CAROUSEL`，`buildRenderPayload` 支持视频素材）
- 改 `service/AiContentReviewTaskService.java`（`saveResult` 放行 `VIDEO`）
- 改 `scheduler/*`（新增渲染 job 轮询；现有 failStale 机制复用）
- 改 `application.yml`（`yanque-ai.render.base-url`）、`mapper/*`（`video_duration_ms/cover_object_key/output_meta_json`）

---

## 7. 风险与坑（必须提前处理）

1. **素材跨服务传输**：Java 给预签名 URL 让 Python 下载，还是 Python 直传 TOS？建议后者（Python 配 AK/SK），否则 1200s 超时下大文件易断。
2. **超时**：现有 preview 超时 1200s，MP4 合成可能更久 → 必须异步 job + 轮询，不能同步等。
3. **状态双写**：Python 内部 task 状态与 Java 步骤状态并存，以 Java 为准，Python 只回最终态 + 心跳进度。
4. **字体**：Linux 服务器无中文字体会出豆腐块，需内置字体并断言 `subtitle_font_supports_text`。
5. **moviepy/codec 兼容**：仓库已有编码器探测与回退（`_get_effective_video_codec`、`_fallback_write_videofile`），别删。
6. **成本**：Seedance/MuAPI/WaveSpeed 按条计费，重试必须幂等，必须预算闸门。
7. **幂等**：`job_id = preview_task_id`，重复提交直接返回原 job。
8. **并发**：仓库线程池 + Redis 队列双模式；Java 侧已有 `aiMediaProcessingExecutor`，两边都要限流，避免 ffmpeg 打满 CPU。

---

## 8. 待你确认（6 条）

1. **架构**：方案 A（独立 Python 渲染服务，推荐）还是方案 B（并入 YanQue-AI）？YanQue-AI 源码在哪台机/哪个仓库？
2. **发布目标**：抖音 / 视频号 / 小红书 / 快手，还是海外 TikTok / IG / YouTube？发布放 P1 还是 P2？抖音凭据从哪来（DouyinCollection 登录态 / 开放平台）？
3. **成品形态**：保留「图片轮播 + 视频成片」双格式（审稿人选），还是彻底切换 VIDEO？
4. **素材优先级**：本地素材库 clip → AI 生成 → 库存 Pexels，这个顺序和「是否允许 AI 生成付费素材」你定。
5. **部署**：Python 渲染服务跑哪台机（本机 / 同网服务器）？有无 ffmpeg、能否装中文字体、能否写磁盘、要不要 GPU（目前不需要）。
6. **仓库自带 WebUI/CLI/FastAPI /videos 是否保留**（建议保留作调试，Java 只用新增 /render 适配层）。

---

## 9. 实现状态（2026-10-30 已落地）

结论：**渲染层已融合完成**，编排层保持原样（MPT 仓库没有人工回环能力，编排是 YanQue 侧 LangGraph 的强项，因此只搬渲染）。

> 注：本节记录的是**第一阶段**（把渲染层并回 `YanQue-AI-14` 内部）的落地情况，作为历史留存。
> 最终交付形态已改为**独立项目** `D:\AGI\Multi-agent-video-workflow`（把渲染层 + 可中断 GATE 编排一起落成可独立运行的服务），
> 以项目根目录 `README.md` 与 `docs/ARCHITECTURE.md` 为准。

### 9.1 新增 Python 模块 `src/yanque_ai/modules/media_render/`

| 文件 | 能力 | 仓库来源 |
| --- | --- | --- |
| `ffmpeg_tools.py` | FFmpeg/ffprobe 路径解析（配置 → PATH → imageio-ffmpeg 内置二进制）、`probe_duration_ms`（无 ffprobe 时解析 `ffmpeg -i` 的 stderr）、受控 `run()` | `app/utils/` |
| `tts.py` | edge-tts 逐词时间戳（必须显式 `boundary='WordBoundary'`，offset/duration 单位 100ns）+ openai 兼容通道备用 + 失败降级静音 | `app/services/voice.py` |
| `subtitle.py` | 句级/逐字 cue 生成、16 字断行、SRT/ASS 落盘、字体字形探测 | `app/services/subtitle.py` |
| `compose.py` | 图片 Ken Burns 推拉、片段拼接、字幕烧录、旁白轨构建、BGM 循环混音、libx264→ultrafast→mpeg4 三级编码回退 | `app/services/video.py` + `bgm.py` |
| `renderer.py` | `VideoPreviewRenderer.render()`：下载素材 → 逐条配音 → 镜头时长跟语音时长对齐 → concat → 烧字幕 → mux → 上传封面+MP4，返回既有 `PreviewRenderResponse` | 新写 |

`content_generation/preview_renderer.py:90` 的 `render()` 仅在 `format == "VIDEO"` 时延迟导入并分派到 `VideoPreviewRenderer`；图片轮播链路一字未动，随时可回退。
`content_generation/preview_plan_agent.py:94` `choose_preview_format(materials, *, video_enabled)` 由配置决定 `VIDEO`/`IMAGE_CAROUSEL`，模型无权改写；`review_nodes.py:139` 同步校验。

### 9.2 配置与依赖

- `common/config.py`：新增 29 个 `media_render_*` 字段 + `content_preview_video_enabled`（默认 true），全部可用 `MEDIA_RENDER_*` 环境变量覆盖；参数含 1080x1920@30 / crf 21 / veryfast / timeout 1800s / clip 3500–30000ms / 音色 `zh-CN-YunxiNeural` +8% / 字幕 sentence + Microsoft YaHei 58pt margin_v 260 / BGM 0.18。
- `pyproject.toml` + `uv.lock`：`edge-tts>=7.0.0`、`imageio-ffmpeg>=0.6.0`（锁到 7.3.1 / 0.7.0）。
- `.env.example`：完整 `MEDIA_RENDER_*` 说明块 + `CONTENT_PREVIEW_VIDEO_ENABLED`。
- `Dockerfile`：最终阶段 `MEDIA_RENDER_FFMPEG_PATH=/usr/bin/ffmpeg`、`MEDIA_RENDER_SUBTITLE_FONT="Noto Sans CJK SC"`（镜像已有 ffmpeg + fonts-noto-cjk，直接规避风险第 4 条）。

### 9.3 Java 侧改动（`D:\Ajava+ai\yq\yq-admin`，`mvn -o -B -DskipTests compiler:compile` BUILD SUCCESS）

- 新增 `config/AiContentPreviewProperties.java`：`ai.content-preview.video-enabled`。
- `AiContentPreviewTaskService.java`：`resolveFormat()` 统一降级；`approveAndCreate` 回写真实格式；`buildRenderPayload`/`saveResult` 都按降级后的格式校验，VIDEO 时校验 `video_object_key/video_file_size`。
- `AiContentReviewTaskService.java`：`saveResult` 不再硬锁 `IMAGE_CAROUSEL`，按开关归一后回写 `preview_plan_json`。
- `ContentGenerationPythonClient.java`：预览渲染超时可配 `ai.content-preview.render-timeout-millis`（默认 2400000 = 40min），应对风险第 2 条。
- `AiContentPreviewTaskMapper.xml`：`markSuccess` 原来把 `video_object_key/video_file_size/duration_ms` 硬写 null（视频成品会被吞掉），改为写入实体值；`prepareRetry` 原来硬编码 `format='IMAGE_CAROUSEL'`，改为保留任务自身格式。

### 9.4 验证证据

- `scripts/smoke_media_render.py`：3 镜合成通过 —— shot 时长 7180/7570/21、7580/7970/23、6530/6930/18（图/音/字幕 cue 数），`cues=12`，成片 22470ms / 1018561 bytes，`word_level=True`；抽帧确认 Ken Burns + 中文字幕烧录正常。
- `scripts/smoke_media_render_bgm.py`：BGM 混音分支通过 —— 旁白峰值 -6.6 dB、BGM 源 -21.1 dB、混音后 -6.4 dB / 22470ms，旁白未被压制。（注：测试脚本里 lavfi 源不能配 `-stream_loop`，生产 `mux()` 的循环作用于普通文件输入，不受影响。）
- `scripts/contract_media_render.py`：Java 报文形状 → `PreviewRenderRequest` 校验通过（VIDEO 保留 `duration_ms`）；响应字段名与 Java `saveResult` 读取的 7 个 key 完全一致；格式开关与渲染默认参数断言通过。
- `scripts/list_routes.py`：OpenAPI 已注册 `POST /ai/content-generation/topics/previews/render`，与 Java 调用路径一致。

### 9.5 仍未做（下一批）

1. **真实端到端联调**：需要 Java + MySQL + Redis + TOS + YanQue-AI 同时在线，跑一条真内容从 approve 到成片。本机无系统 ffmpeg/ffprobe，靠 imageio-ffmpeg 兜底；服务器上用 apt 版即可。
2. 逐字弹跳字幕（`MEDIA_RENDER_SUBTITLE_MODE=word` + `MEDIA_RENDER_SUBTITLE_POP=true`）已实现但未实跑取样。
3. 外部库存素材（pexels/pixabay）与 AI 视频片段源：按决策 10A 本阶段不接。
4. 平台发布：按决策 11 留给 Java 侧（`publish_caption/tags` 已在 `preview_plan_json` 里，抖音发布通道另开一期）。
5. 编排层能力（MPT 的 `stop_at` 分段复用、`video_project.py` 不可变版本快照）暂不移植——与现有 Java 状态机重复。
