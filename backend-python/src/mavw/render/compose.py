"""FFmpeg 原生视频合成。

MoneyPrinterTurbo 的 video.py 依赖 moviepy 在内存里拼 Clip；本服务改为纯 FFmpeg
分步落地，避免引入 moviepy/imageio 以及 Windows 下的编码器兼容问题，但完整保留它
的这几项关键做法：

1. 画幅统一用 cover 策略（等比放大后居中裁切），杜绝黑边；
2. 静态图片用 zoompan 做缓慢推拉/平移，避免整片像 PPT；
3. 字幕用 ASS 文本由 ass 滤镜烧录，而不是逐帧贴图；
4. BGM 循环铺底并压低音量，与人声做 amix；
5. 编码器不可用时逐级回退，而不是整条任务直接失败。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from ..config import Settings
from .ffmpeg_tools import MediaRenderError, ffmpeg_executable, run


@dataclass(frozen=True)
class ComposeOptions:
    """一次合成使用的画幅、编码与性能参数。"""

    width: int = 1080
    height: int = 1920
    fps: int = 30
    crf: int = 21
    preset: str = "veryfast"
    timeout_seconds: float = 1800.0

    @classmethod
    def from_settings(cls, settings: Settings) -> "ComposeOptions":
        """从统一配置里取合成参数，便于运维只改环境变量。"""

        return cls(
            width=settings.media_render_width,
            height=settings.media_render_height,
            fps=settings.media_render_fps,
            crf=settings.media_render_crf,
            preset=settings.media_render_preset,
            timeout_seconds=settings.media_render_timeout_seconds,
        )


def subtitle_reserve(margin_v: int, font_size: int) -> int:
    """底部要给字幕让出的高度：字幕底距 + 一行字高 + 呼吸间隙。

    ASS 的 margin_v 是「字幕底边到画面底部」的距离，字幕本身还往上占大约
    1.4 倍字高；少算这一段，右下角的画中画就会正好压住字幕上沿。
    """

    return margin_v + int(font_size * 1.4) + 16


def pip_origin(
    position: str,
    canvas: tuple[int, int],
    pip: tuple[int, int],
    margin: int,
    bottom_reserved: int,
) -> tuple[int, int]:
    """算画中画左上角坐标。

    bottom_reserved 是底部要给字幕和标签条让出的高度；右下角摆放时从它往上算，
    否则小窗会正好压在字幕上。
    """

    canvas_w, canvas_h = canvas
    pip_w, pip_h = pip
    side = (position or "top-right").strip().lower()

    left = margin
    right = canvas_w - pip_w - margin
    # 顶部要给短视频平台的 UI 让位（约一个 margin 的高度），否则小窗会被点赞条压住。
    top = margin * 2
    bottom = canvas_h - pip_h - max(margin, bottom_reserved)

    if side == "top-left":
        return left, top
    if side == "bottom-left":
        return left, max(top, bottom)
    if side == "bottom-right":
        return max(left, right), max(top, bottom)
    # 默认右上：最不容易和标题、字幕、标签条撞在一起。
    return max(left, right), top


class VideoComposer:
    """把一组素材片段和一条口播音轨合成为竖屏成品。"""

    def __init__(self, options: ComposeOptions) -> None:
        self._options = options

    @property
    def _size_filter(self) -> str:
        """等比放大后居中裁切的 cover 适配链。"""

        return (
            f"scale={self._options.width}:{self._options.height}"
            ":force_original_aspect_ratio=increase,"
            f"crop={self._options.width}:{self._options.height},setsar=1"
        )

    def render_image_clip(
        self,
        image: Path,
        duration_ms: int,
        output: Path,
        *,
        variant: int = 0,
    ) -> None:
        """把静态图片渲染成带推拉或平移的等长视频片段。"""

        frames = max(2, int(round(duration_ms / 1000 * self._options.fps)))
        center_x = "iw/2-(iw/zoom/2)"
        center_y = "ih/2-(ih/zoom/2)"
        # 三种镜头语法轮换使用，避免整片都是同一种运动方向。
        if variant % 4 == 0:
            zoom, pos_x, pos_y = "min(1+0.0012*on,1.14)", center_x, center_y
        elif variant % 4 == 1:
            zoom, pos_x, pos_y = "max(1.14-0.0012*on,1.0)", center_x, center_y
        elif variant % 4 == 2:
            zoom, pos_x, pos_y = "1.10", center_x, f"(ih-ih/zoom)*(1-on/{frames})"
        else:
            zoom, pos_x, pos_y = "1.10", f"(iw-iw/zoom)*(on/{frames})", center_y
        # 先放大到 2 倍再交给 zoompan，避免缩放时出现明显锯齿。
        upscale = (
            f"scale={self._options.width * 2}:{self._options.height * 2}"
            ":force_original_aspect_ratio=increase,"
            f"crop={self._options.width * 2}:{self._options.height * 2},setsar=1"
        )
        filters = (
            f"[0:v]{upscale},"
            f"zoompan=z='{zoom}':x='{pos_x}':y='{pos_y}':"
            f"d={frames}:s={self._options.width}x{self._options.height}:fps={self._options.fps},"
            "format=yuv420p[v]"
        )
        # 单帧输入交给 zoompan 自己按 d= 帧数生成整段，避免 -loop 重复解码同一张图。
        self._encode(
            ["-i", str(image)],
            filters,
            output,
            extra=["-frames:v", str(frames), "-r", str(self._options.fps)],
        )

    def render_video_clip(self, source: Path, duration_ms: int, output: Path) -> None:
        """把已有视频素材裁到目标时长，并统一画幅与帧率。"""

        seconds = duration_ms / 1000
        filters = f"[0:v]fps={self._options.fps},{self._size_filter},format=yuv420p[v]"
        self._encode(
            ["-stream_loop", "-1", "-i", str(source), "-t", f"{seconds:.3f}"],
            filters,
            output,
            extra=["-t", f"{seconds:.3f}"],
        )

    def compose_pip(
        self,
        base: Path,
        avatar: Path,
        mask: Path,
        border: Path,
        output: Path,
        *,
        x: int,
        y: int,
        duration_ms: int,
        radius: int = 26,
    ) -> None:
        """把数字人小窗以圆角贴纸的形式叠到主画面上。

        mask 是圆角灰度图（白=保留），border 是白描边环；两者都由
        digital_human.rounded_mask_png / border_png 预先画好再传进来，
        避免在 FFmpeg 里堆 geq 表达式。
        """

        seconds = max(duration_ms, 200) / 1000
        filters = (
            "[1:v]format=rgba[pip];"
            "[2:v]format=gray[mask];"
            "[pip][mask]alphamerge[pipa];"
            f"[0:v][pipa]overlay={x}:{y}:eof_action=repeat[p1];"
            "[3:v]format=rgba[brd];"
            f"[p1][brd]overlay={x}:{y}:eof_action=repeat:format=auto,format=yuv420p[v]"
        )
        self._encode(
            [
                "-i",
                str(base),
                "-i",
                str(avatar),
                "-loop",
                "1",
                "-i",
                str(mask),
                "-loop",
                "1",
                "-i",
                str(border),
            ],
            filters,
            output,
            extra=["-t", f"{seconds:.3f}", "-r", str(self._options.fps)],
        )

    def concat(self, clips: list[Path], output: Path) -> None:
        """用 concat 解复用器无重编码拼接参数一致的片段。"""

        if not clips:
            raise MediaRenderError("没有可拼接的视频片段。")
        list_file = output.with_suffix(".txt")
        list_file.write_text(
            "\n".join(f"file '{clip.as_posix()}'" for clip in clips),
            encoding="utf-8",
        )
        run(
            [
                ffmpeg_executable(),
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(list_file),
                "-c",
                "copy",
                "-fflags",
                "+genpts",
                "-movflags",
                "+faststart",
                str(output),
            ],
            timeout=self._options.timeout_seconds,
        )

    def burn_subtitles(self, video: Path, ass: Path, output: Path) -> None:
        """把 ASS 字幕烧进画面；切到字幕所在目录，避免 Windows 盘符转义问题。"""

        run(
            [
                ffmpeg_executable(),
                "-y",
                "-i",
                str(video),
                "-vf",
                f"ass={ass.name}",
                "-c:v",
                "libx264",
                "-preset",
                self._options.preset,
                "-crf",
                str(self._options.crf),
                "-pix_fmt",
                "yuv420p",
                "-an",
                "-movflags",
                "+faststart",
                str(output),
            ],
            timeout=self._options.timeout_seconds,
            cwd=ass.parent,
        )

    def align_audio(self, source: Path, duration_ms: int, output: Path) -> None:
        """把单段口播补齐或裁到与画面等长，保证音画不错位。"""

        seconds = max(duration_ms, 200) / 1000
        run(
            [
                ffmpeg_executable(),
                "-y",
                "-i",
                str(source),
                "-af",
                f"apad,atrim=0:{seconds:.3f},asetpts=N/SR/TB",
                "-ar",
                "48000",
                "-ac",
                "2",
                "-c:a",
                "pcm_s16le",
                str(output),
            ],
            timeout=self._options.timeout_seconds,
        )

    def build_narration(self, segments: list[Path], output: Path) -> None:
        """把各段对齐后的口播按顺序拼成一条完整旁白音轨。"""

        if not segments:
            raise MediaRenderError("没有可拼接的配音片段。")
        command = [ffmpeg_executable(), "-y"]
        for segment in segments:
            command += ["-i", str(segment)]
        stream_spec = "".join(f"[{index}:a]" for index in range(len(segments)))
        filters = f"{stream_spec}concat=n={len(segments)}:v=0:a=1[a]"
        command += [
            "-filter_complex",
            filters,
            "-map",
            "[a]",
            "-ar",
            "48000",
            "-ac",
            "2",
            "-c:a",
            "pcm_s16le",
            str(output),
        ]
        run(command, timeout=self._options.timeout_seconds)

    def mux(
        self,
        video: Path,
        narration: Path,
        output: Path,
        *,
        bgm: Path | None = None,
        bgm_volume: float = 0.18,
    ) -> None:
        """合入旁白与可选 BGM，并输出可直传的 mp4。"""

        command = [ffmpeg_executable(), "-y", "-i", str(video), "-i", str(narration)]
        if bgm is not None:
            # 循环铺满全片，但以旁白长度为准收尾。
            command += ["-stream_loop", "-1", "-i", str(bgm)]
            filters = (
                "[1:a]aresample=48000,asetpts=N/SR/TB[n];"
                f"[2:a]aresample=48000,volume={bgm_volume:.3f}[b];"
                "[n][b]amix=inputs=2:duration=first:normalize=0[a]"
            )
            command += ["-filter_complex", filters, "-map", "0:v", "-map", "[a]"]
        else:
            command += ["-map", "0:v", "-map", "1:a"]
        command += [
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "160k",
            "-ar",
            "48000",
            "-ac",
            "2",
            "-shortest",
            "-movflags",
            "+faststart",
            str(output),
        ]
        run(command, timeout=self._options.timeout_seconds)

    def _encode(
        self,
        inputs: list[str],
        filters: str,
        output: Path,
        *,
        extra: list[str] | None = None,
    ) -> None:
        """按 libx264 常规、libx264 极速、mpeg4 的顺序逐级回退编码。"""

        attempts = [
            ["-c:v", "libx264", "-preset", self._options.preset, "-crf", str(self._options.crf)],
            ["-c:v", "libx264", "-preset", "ultrafast", "-crf", str(self._options.crf + 3)],
            ["-c:v", "mpeg4", "-q:v", "4"],
        ]
        last_error: MediaRenderError | None = None
        for codec in attempts:
            command = [
                ffmpeg_executable(),
                "-y",
                *inputs,
                "-filter_complex",
                filters,
                "-map",
                "[v]",
                *codec,
                "-pix_fmt",
                "yuv420p",
                "-an",
                "-movflags",
                "+faststart",
                *(extra or []),
                str(output),
            ]
            try:
                run(command, timeout=self._options.timeout_seconds)
                return
            except MediaRenderError as exc:
                last_error = exc
        raise last_error or MediaRenderError("视频片段编码失败。")
