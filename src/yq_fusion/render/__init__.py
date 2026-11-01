"""视频成品渲染链路。

把 MoneyPrinterTurbo（GitHub: harry0703/MoneyPrinterTurbo）里真正有价值的三段能力
按本工程的形态重写后落地：
1. 配音合成与逐词时间轴（原 voice.py）；
2. 字幕时间轴与 ASS 样式（原 subtitle.py）；
3. FFmpeg 合成：画幅适配、图片推拉、拼接、字幕烧录、BGM 混音（原 video.py）。

刻意不搬的部分：moviepy 的 Clip 编排、库存/AI 素材下载、Upload-Post 跨平台发布。
前者会引入沉重的运行时依赖，后两者属于素材与发布层，由 Java 侧与后续阶段负责。
"""

from .compose import ComposeOptions, VideoComposer
from .ffmpeg_tools import MediaRenderError, ffmpeg_executable, probe_duration_ms
from .renderer import VideoPreviewRenderer
from .subtitle import SubtitleCue, build_cues, write_ass, write_srt
from .tts import SpeechAsset, VoiceSynthesizer, WordCue

__all__ = [
    "ComposeOptions",
    "MediaRenderError",
    "SpeechAsset",
    "SubtitleCue",
    "VideoComposer",
    "VideoPreviewRenderer",
    "VoiceSynthesizer",
    "WordCue",
    "build_cues",
    "ffmpeg_executable",
    "probe_duration_ms",
    "write_ass",
    "write_srt",
]
