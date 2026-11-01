"""诊断：量一遍每段 TTS 的头尾静音与句内停顿，确认卡顿到底来自哪里。

用法：python scripts/diag_tts_silence.py
产物：output/diag/tts/speech-NN.wav（可直接试听）
"""

from __future__ import annotations

import asyncio
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from types import SimpleNamespace  # noqa: E402

from mavw.config import get_settings  # noqa: E402
from mavw.render.ffmpeg_tools import ffmpeg_executable, probe_duration_ms  # noqa: E402
from mavw.render.renderer import _narration_text  # noqa: E402
from mavw.render.tts import VoiceSynthesizer  # noqa: E402

SHOTS = [
    ("很多人以为AI剪辑入门很简单", "很多人以为AI剪辑入门很简单，其实第一步就错了。"),
    ("第一步最容易被忽略", "做AI剪辑入门时，先把目标和边界写清楚，后面的动作才不会乱。"),
    ("第二步：把动作拆小", "把大任务拆成今天就能完成的最小动作，先跑通再优化。"),
    ("第三步：留出复盘时间", "每做完一轮就记录哪个动作真的有效，下一轮只加一个变量。"),
]


def narration(headline: str, body: str) -> str:
    """直接复用语渲染层的口播拼装，保证这里量的就是线上真正会念的文本。"""

    return _narration_text(SimpleNamespace(headline=headline, body=body))


def silences(path: Path) -> list[tuple[float, float]]:
    proc = subprocess.run(
        [
            ffmpeg_executable(), "-hide_banner", "-i", str(path),
            "-af", "silencedetect=noise=-45dB:d=0.2", "-f", "null", "NUL",
        ],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    starts = [float(m) for m in re.findall(r"silence_start: (-?[0-9.]+)", proc.stderr)]
    ends = [float(m) for m in re.findall(r"silence_end: ([0-9.]+)", proc.stderr)]
    return list(zip(starts, ends + [None] * (len(starts) - len(ends))))


async def main() -> int:
    settings = get_settings()
    voices = VoiceSynthesizer(settings)
    out = ROOT / "output" / "diag" / "tts"
    out.mkdir(parents=True, exist_ok=True)
    for index, (headline, body) in enumerate(SHOTS, start=1):
        text = narration(headline, body)
        audio = out / f"speech-{index:02d}.wav"
        speech = await voices.synthesize(text, audio)
        total = probe_duration_ms(audio) / 1000
        gaps = silences(audio)
        head = gaps[0][1] if gaps and gaps[0][0] <= 0.05 and gaps[0][1] else 0.0
        # 尾部静音：最后一段静音一直拖到结尾（silence_end 可能缺失或刚好等于总长）。
        tail = 0.0
        if gaps:
            last_start, last_end = gaps[-1]
            if last_end is None or last_end >= total - 0.05:
                tail = total - last_start
        inner = [
            f"{b - a:.2f}s@ {a:.2f}" for a, b in gaps
            if b and not (a <= 0.05) and b < total - 0.05
        ]
        print(f"[{index}] 文本：{text}")
        print(f"    总时长 {total:.2f}s | 头静音 {head:.2f}s | 尾静音 {tail:.2f}s | 句内停顿 {inner}")
        print(f"    有效语音 ≈ {total - head - tail:.2f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
