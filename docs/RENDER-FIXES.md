# 成片「重复 + 卡顿」修复记录

现象：demo 成片（32.49s）听起来像卡住，画面大字与字幕内容一模一样，字幕还断在半截。

结论：**与出图 / 图生视频模型无关**。画面素材本身没问题，缺陷全在装配层——
把「文案 → 配音 → 字幕 → 画面」缝成一条 MP4 的那几步。

## 四处根因与修复

| # | 根因 | 位置 | 修复 |
| - | ---- | ---- | ---- |
| 1 | 口播文本 = headline + body 硬拼。headline 已经是画面大字，字幕又从这段文本生成，于是同一句被念两遍、又被显示两遍 | `src/mavw/render/renderer.py:302` `_narration_text` | 有 body 就只念 body，body 为空才退化成念 headline |
| 2 | edge-tts 每段音频尾部自带 0.85–1.2s 静音，再叠 `NARRATION_GAP_MS` 400ms 与下一镜 0.2s 头静音，镜交界处空音 1.45s | `src/mavw/render/tts.py:36` `TRIM_TAIL_FILTER`、`tts.py:214` `_trim_tail` | 合成后削尾部静音（留 120ms 尾音）；镜间停 400→250ms |
| 3 | 句号处 TTS 停顿 0.65–0.75s，镜头时长又被 `max(planned)` 抬升，画面空转 | `tts.py:42` `TIGHTEN_PAUSE_FILTER`、`renderer.py:179` `_shot_duration` | 把 >0.55s 的内部静音压回 0.30s（逐词字幕模式跳过）；时长严格跟配音走 |
| 4 | 字幕按 `max_chars` 硬切中文串，且 ASS `WrapStyle: 2` 不折行 → 断在半截 | `src/mavw/render/subtitle.py:97` `_split_lines`、`subtitle.py:151` | 只在标点处断句，超长交给 `WrapStyle: 0` 自动折行 |

## 实测对比

| 指标 | 修复前 | 修复后 |
| ---- | ------ | ------ |
| 成片时长 | 32.49s | 28.41s |
| 镜间静音 | 1.45–1.48s（4 处） | 0 处 ≥0.5s |
| 最长静音 | 1.48s | <0.48s（句读呼吸） |
| 画面大字 vs 字幕 | 一字不差地重复 | 不重复，字幕是延续句 |
| 字幕断句 | 断在半截短语 | 整句，必要时两行折行 |

## 复现命令

```powershell
cd D:\AGI\Multi-agent-video-workflow
.\.venv\Scripts\python.exe scripts\demo.py --brief AI剪辑入门 --auto
.\.venv\Scripts\python.exe scripts\dump_script.py   # 核对大字 / 口播是否还重复
```

静音量测（ffmpeg 来自 imageio-ffmpeg，本机无 ffprobe）：

```powershell
$ff = .venv\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe
& $ff -hide_banner -i output\assets\<片名>.mp4 -af silencedetect=noise=-40dB:d=0.5 -f null NUL
```
