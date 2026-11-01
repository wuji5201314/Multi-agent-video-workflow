r"""单独验证数字人画中画这一层：出图 + 出片，不用跑整条管线。

用法（在 backend-python 目录下）：

    .\.venv\Scripts\python.exe scripts\diag_digital_human.py
    .\.venv\Scripts\python.exe scripts\diag_digital_human.py --duration 6 --position top-right

产物落在 output/diag/digital-human/：
    avatar.mp4         provider 单独产出的数字人片段
    picture.mp4        叠加画中画后的画面（无字幕、无音轨）
    frame-*.png        几个时间点的抽帧，用来核对位置与遮挡

它存在的意义：换 provider、调位置、调圆角时，不用等整条 30 秒管线跑完就能看出效果。
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from mavw.config import Settings, get_settings  # noqa: E402
from mavw.render.compose import (  # noqa: E402
    ComposeOptions,
    VideoComposer,
    pip_origin,
    subtitle_reserve,
)
from mavw.render.digital_human import (  # noqa: E402
    DigitalHumanSpec,
    border_png,
    build_digital_human,
    rounded_mask_png,
)
from mavw.render.ffmpeg_tools import ffmpeg_executable, probe_duration_ms, run  # noqa: E402


def _make_audio(path: Path, seconds: float, sample_rate: int = 48000) -> Path:
    """造一段安静的正弦音当旁白，只为给 provider 一个有内容的音频输入。"""

    run(
        [
            ffmpeg_executable(),
            "-y",
            "-f",
            "lavfi",
            "-i",
            f"sine=frequency=220:duration={seconds}:sample_rate={sample_rate}",
            "-ac",
            "2",
            "-c:a",
            "pcm_s16le",
            str(path),
        ],
        timeout=60,
    )
    return path


def _make_background(path: Path, seconds: float, width: int, height: int, fps: int) -> Path:
    """造一张会缓慢推近的主画面，模拟 Ken Burns 素材卡。"""

    filters = (
        "zoompan=z='min(1+0.0012*on,1.12)':"
        "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':"
        f"d=1:s={width}x{height}:fps={fps},format=yuv420p"
    )
    run(
        [
            ffmpeg_executable(),
            "-y",
            "-f",
            "lavfi",
            "-i",
            f"testsrc2=size={width}x{height}:rate={fps}:duration={seconds}",
            "-vf",
            filters,
            "-t",
            f"{seconds:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(path),
        ],
        timeout=120,
    )
    return path


def _snapshots(video: Path, stamps: list[float], out_dir: Path) -> list[Path]:
    shots: list[Path] = []
    for stamp in stamps:
        target = out_dir / f"frame-{stamp:05.1f}.png"
        run(
            [
                ffmpeg_executable(),
                "-y",
                "-ss",
                f"{stamp:.2f}",
                "-i",
                str(video),
                "-frames:v",
                "1",
                "-update",
                "1",
                str(target),
            ],
            timeout=60,
        )
        if target.is_file():
            shots.append(target)
    return shots


async def main() -> int:
    parser = argparse.ArgumentParser(description="数字人画中画单层验证")
    parser.add_argument("--duration", type=float, default=4.0, help="测试时长（秒）")
    parser.add_argument(
        "--position",
        default="",
        help="top-right / top-left / bottom-left / bottom-right",
    )
    parser.add_argument("--width", type=int, default=0, help="小窗宽，默认取配置")
    parser.add_argument("--height", type=int, default=0, help="小窗高，默认取配置")
    args = parser.parse_args()

    base: Settings = get_settings()
    position = args.position or base.digital_human_pip_position
    width = args.width or base.digital_human_pip_width
    height = args.height or base.digital_human_pip_height
    radius = base.digital_human_pip_radius
    fps = base.media_render_fps

    out_dir = ROOT / "output" / "diag" / "digital-human"
    out_dir.mkdir(parents=True, exist_ok=True)

    provider = build_digital_human(base)
    if provider is None:
        print("DIGITAL_HUMAN_ENABLED=false，先打开开关再跑本脚本。")
        return 1

    print(f"provider = {provider.name}")

    audio = _make_audio(out_dir / "audio.wav", args.duration)
    background = _make_background(
        out_dir / "background.mp4", args.duration, base.media_render_width, base.media_render_height, fps
    )
    reference = background.with_suffix(".png")
    _snapshots(background, [0.0], out_dir)
    first_frame = out_dir / "frame-000.0.png"
    if first_frame.is_file():
        reference = first_frame

    spec = DigitalHumanSpec(
        reference_image=reference,
        audio=audio,
        duration_ms=int(args.duration * 1000),
        output=out_dir / "avatar.mp4",
        width=width,
        height=height,
        fps=fps,
    )
    print(f"生成数字人片段：{width}x{height}，{spec.duration_ms}ms……")
    avatar = await provider.generate(spec)
    print(f"  → {avatar}（{probe_duration_ms(avatar)}ms）")

    mask = out_dir / "pip-mask.png"
    mask.write_bytes(rounded_mask_png(width, height, radius))
    border = out_dir / "pip-border.png"
    border.write_bytes(border_png(width, height, radius, max(1, base.digital_human_pip_border)))

    x, y = pip_origin(
        position,
        (base.media_render_width, base.media_render_height),
        (width, height),
        base.digital_human_pip_margin,
        subtitle_reserve(
            base.media_render_subtitle_margin_v,
            base.media_render_subtitle_font_size,
        ),
    )
    print(f"小窗位置：{position} → ({x},{y})")

    composer = VideoComposer(ComposeOptions.from_settings(base))
    picture = out_dir / "picture.mp4"
    await asyncio.to_thread(
        composer.compose_pip,
        background,
        avatar,
        mask,
        border,
        picture,
        x=x,
        y=y,
        duration_ms=spec.duration_ms,
    )
    print(f"  → {picture}（{probe_duration_ms(picture)}ms）")

    stamps = [0.0, args.duration * 0.5, max(args.duration - 0.2, 0.0)]
    for shot in _snapshots(picture, stamps, out_dir):
        print(f"  抽帧：{shot}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
