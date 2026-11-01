"""FFmpeg 可执行文件的定位与受控调用。

移植自 MoneyPrinterTurbo 的 utils/video 模块中"编码器探测 + 回退"的思路，只保留
本服务真正需要的最小集合：

1. 依次尝试显式配置、PATH、常见安装目录、imageio-ffmpeg 自带的静态二进制；
2. 探测只依赖 ffmpeg 本体，不强依赖 ffprobe（静态发行包通常不带 ffprobe）；
3. 所有调用都带超时，失败时把 ffmpeg 的 stderr 尾部转成可读业务异常。
"""

from __future__ import annotations

import functools
import os
import re
import shutil
import subprocess
from pathlib import Path


class MediaRenderError(RuntimeError):
    """视频渲染链路中可以原样回传给人看的异常。"""


_DURATION = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")
_AUDIO_STREAM = re.compile(r"Stream #\d+:\d+.*?:\s*Audio:")
_VIDEO_SIZE = re.compile(r"Stream #\d+:\d+.*?:\s*Video:.*?,\s*(\d{2,5})x(\d{2,5})")


def _configured_candidates() -> list[str]:
    """收集显式配置的候选路径，目录会自动补上可执行文件名。"""

    suffix = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    raw_values = [
        os.getenv("MEDIA_RENDER_FFMPEG_PATH", ""),
        os.getenv("YQ_FFMPEG_PATH", ""),
        os.getenv("FFMPEG_PATH", ""),
        os.getenv("MEDIA_RENDER_FFMPEG_DIR", ""),
    ]
    candidates: list[str] = []
    for raw in raw_values:
        value = (raw or "").strip()
        if not value:
            continue
        path = Path(value)
        candidates.append(str(path / suffix) if path.is_dir() else str(path))
    candidates.extend(
        [
            str(Path("C:/ffmpeg/bin") / suffix),
            str(Path("/usr/bin") / suffix),
            str(Path("/usr/local/bin") / suffix),
            str(Path("/opt/homebrew/bin") / suffix),
        ]
    )
    return candidates


@functools.lru_cache(maxsize=1)
def ffmpeg_executable() -> str:
    """返回可直接执行的 ffmpeg 路径；找不到时给出可操作的安装提示。"""

    for candidate in _configured_candidates():
        if Path(candidate).is_file():
            return candidate
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:  # noqa: BLE001 - 任何导入失败都归为"没装 ffmpeg"
        raise MediaRenderError(
            "未找到可用的 FFmpeg：请安装 ffmpeg，或执行 uv pip install imageio-ffmpeg。"
        ) from exc


@functools.lru_cache(maxsize=1)
def ffprobe_executable() -> str | None:
    """ffprobe 是可选依赖；静态包通常只有 ffmpeg，因此允许返回 None。"""

    suffix = "ffprobe.exe" if os.name == "nt" else "ffprobe"
    candidates: list[str] = []
    raw = (os.getenv("MEDIA_RENDER_FFPROBE_PATH", "") or "").strip()
    if raw:
        candidates.append(raw)
    candidates.append(str(Path(ffmpeg_executable()).with_name(suffix)))
    found = shutil.which("ffprobe")
    if found:
        candidates.append(found)
    for candidate in candidates:
        if Path(candidate).is_file():
            return candidate
    return None


def run(command: list[str], *, timeout: float, cwd: Path | None = None) -> None:
    """执行受控 FFmpeg 命令；失败时抛出带 stderr 尾部的业务异常。"""

    try:
        result = subprocess.run(
            command,
            cwd=str(cwd) if cwd is not None else None,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise MediaRenderError(f"FFmpeg 执行超时（{timeout:.0f} 秒）。") from exc
    if result.returncode != 0:
        raise MediaRenderError("FFmpeg 执行失败：" + (result.stderr or "")[-1200:])


def _probe_text(path: Path, *, timeout: float = 60.0) -> str:
    """用 ffmpeg -i 读取容器信息；该用法只探测不转码，返回码恒为 1。"""

    result = subprocess.run(
        [ffmpeg_executable(), "-hide_banner", "-i", str(path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    return (result.stderr or "") + (result.stdout or "")


def probe_duration_ms(path: Path, *, timeout: float = 60.0) -> int:
    """读取媒体总时长（毫秒）；读不到直接报错，避免下游用错误时长合成。"""

    match = _DURATION.search(_probe_text(path, timeout=timeout))
    if not match:
        raise MediaRenderError(f"无法读取媒体时长：{path.name}")
    hours, minutes, seconds = match.groups()
    return int((int(hours) * 3600 + int(minutes) * 60 + float(seconds)) * 1000)


def has_audio_stream(path: Path, *, timeout: float = 60.0) -> bool:
    """判断源素材是否自带音轨，决定是否需要补静音。"""

    return bool(_AUDIO_STREAM.search(_probe_text(path, timeout=timeout)))


def probe_video_size(path: Path, *, timeout: float = 60.0) -> tuple[int, int] | None:
    """读取首个视频流的像素尺寸，用于日志与素材诊断。"""

    match = _VIDEO_SIZE.search(_probe_text(path, timeout=timeout))
    return (int(match.group(1)), int(match.group(2))) if match else None


def silence_wav(
    output: Path,
    duration_ms: int,
    *,
    sample_rate: int = 48000,
    timeout: float = 120.0,
) -> None:
    """生成等长静音音轨，供无配音兜底与音频补齐使用。"""

    run(
        [
            ffmpeg_executable(),
            "-y",
            "-f",
            "lavfi",
            "-i",
            f"anullsrc=channel_layout=stereo:sample_rate={sample_rate}",
            "-t",
            f"{duration_ms / 1000:.3f}",
            "-c:a",
            "pcm_s16le",
            str(output),
        ],
        timeout=timeout,
    )
