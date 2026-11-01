# 架构与融合映射

## 1. 为什么这样融合

三个来源各有硬伤与长处：

| 项目 | 长处 | 硬伤 |
| --- | --- | --- |
| Multi-Agent-Video-Creation（= MoneyPrinterTurbo 分支） | 渲染能力最全：15 家 TTS、逐词字幕、镜头动效、BGM 混音、素材缓存 | 编排是**一次性直出**：`_run_pipeline` 从脚本跑到发布不回头，只有 `stop_at` 分段与 CLI `--retry`，没有半途改稿/换图/打回（全仓 grep 无 interrupt/human-in-the-loop） |
| YanQue-AI（LangGraph） | 编排强：选题确认、资料补充、审核退回、单张素材重生成、断点恢复 | 渲染弱：只有轮播与一个无配音/无字幕的 `_render_video`，且强依赖 Java 预签名 |
| yq-admin `aimedia` | 业务模型完整：任务表、步骤枚举、审核状态机、成品任务、TOS 存储 | 渲染在 Python 侧，Java 只管编排与落库 |

结论：**拿 YanQue 的可中断骨架，套 MPT 的渲染肌肉，用 aimedia 的字段命名。**
MPT 的 `task.py` 编排层不搬，只搬渲染层（`voice/subtitle/video/bgm`）。

## 2. 模块映射

| 本项目 | 直接来源 | 改动 |
| --- | --- | --- |
| `render/tts.py` | YanQue `media_render/tts.py` | 仅改 import 路径；edge-tts 必须显式 `boundary='WordBoundary'` 才有逐词时间轴 |
| `render/subtitle.py` | 同上 `subtitle.py` | 原样（sentence / word_by_word 两种模式） |
| `render/compose.py` | 同上 `compose.py` | 原样（Ken Burns 图片动效、等长对齐、ASS 烧录、旁白拼接、BGM 混音） |
| `render/ffmpeg_tools.py` | 同上 `ffmpeg_tools.py` | 原样（PATH 缺失时回落到 imageio-ffmpeg 二进制；无 ffprobe 时用 `ffmpeg -i` 解析） |
| `render/renderer.py` | 同上 `renderer.py` | ① 依赖 `..config` ② 上传改为可注入的 storage ③ `_download` 支持本地路径 ④ 封面改 Pillow |
| `render/card.py` | 新写（替代 Playwright 截图） | 纯 Pillow 画 1080x1920 卡片，离线可用 |
| `render/models.py` | 新写 | 字段与 Java `saveResult` 读取的 7 个 key 完全一致 |
| `pipeline/steps.py` | aimedia `MediaProcessStepCode` 语义 | 5 步 + 4 个 GATE |
| `pipeline/service.py` | 新写（YanQue LangGraph 的 interrupt/resume 语义） | 状态机 + sqlite 落库 |
| `publish/adapters.py` | MPT `upload_post.py` 的思路 | 本期只出发布单 / webhook |

## 3. 状态机

```
create_task
   │
   ▼
 TOPIC ──(payload.candidates)──▶ GATE ──approve(chosen_index)──┐
   ▲                                                           │
   └──────────── edit / reject（带 note 重跑）──────────────────┘
                                                               ▼
 SCRIPT ──(payload.script)────▶ GATE ──approve──┐   edit/reject 回本步并作废下游
                                                ▼
 MATERIAL ──(payload.shots)───▶ GATE ──approve──┐
                                                ▼
 RENDER ──(video_path/object_key)─▶ GATE ─approve─┐
                                                  ▼
 PUBLISH ──(plan)──────────────────────────────▶ DONE
```

- 步骤状态：`PENDING / WAITING_INPUT / RUNNING / SUCCEEDED / FAILED`
- 打回时把该步骤之后的产物一律重置为 `PENDING`，避免出现"视频是旧稿、文案是新稿"的错配；
- 所有决策写入 `gate_log`，可回溯谁在第几步改了什么。

## 4. 渲染参数

镜头时长 = `max(配音时长 + 400ms, 方案给的 duration_ms)`，再夹到 `[clip_min_ms, clip_max_ms]`。
所以口播念得长，镜头自动跟着长，不会出现"话说一半画面已经切走"。

字幕时间轴按**实际生成的片段长度**累加偏移，而不是按计划时长，避免累积漂移；
`subtitle_margin_v=420` 是为了不让字幕压到底部标签条上（默认卡片把标签放在 0.845 高度）。

## 5. 扩展点

1. **真实素材源**：`pipeline/service.py::_step_material`——接 pexels / seedance / 你自己的出图服务，返回本地路径或 https URL 即可（renderer 两种都吃）。
2. **真实发布**：`publish/adapters.py`——实现 `PublishAdapter.publish(payload)`，Cookie、分片上传、审核回查都放这里。
3. **人工交互前端**：`POST /api/tasks/{id}/gates/{step}` 已足够对接页面，GATE 的 payload 就是"待编辑表单"。
4. **接回 Java**：`STORAGE_BACKEND=presign` 复用 yq-admin 的 TOS 预签名，渲染结果直接回 `ai_content_preview_task` 的 `video_object_key`。
5. **BGM**：`MEDIA_RENDER_BGM_DIR` 指一个音乐目录、`MEDIA_RENDER_BGM_ENABLED=true` 即随机取一首压到 20% 音量。

## 6. 已知边界

- 单个素材上限 200MB、成片上限 500MB（超限直接报错而不是静默降质）；
- 素材下载只接受 https 或本地路径，且显式清空请求头——内部密钥不会跟着 TOS 公网请求外泄；
- `edge-tts` 需要外网；断网且 `fallback_silent=true` 时会退化成静音片（字幕仍在）；
- 无 ffmpeg 且 `imageio-ffmpeg` 也缺失时，渲染步骤会失败并落 `FAILED`，人工装好 ffmpeg 后可原地重跑该步骤。
