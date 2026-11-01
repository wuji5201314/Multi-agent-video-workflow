"""数字人（talking head）provider：把参考形象 + 一条旁白音频变成一小段说话视频。

为什么做成 provider：
- L2 画中画只用到 320x400 的小窗，对分辨率的要求远低于全屏数字人，因此本地轻量
  模型（Wav2Lip 一类，2-4GB 显存）就够用；显存更紧的机器还可以直接走远程 API。
- 参考形象、模型、算力来源都会换，但「吃音频、出 mp4」这个契约不会变。

三条落地路线：

1. placeholder —— 纯 Pillow + FFmpeg 画一张会呼吸的占位主播卡。
   零依赖、零算力、离线可跑，用来先把画中画版式和整条链路验证通。
2. wav2lip —— 本地对口型。只改嘴型，人脸来自参考图，正好适配小窗。
3. api —— 远程推理（任意自建/托管服务），本地不占显存，效果最好。

所有实现都必须输出「明确时长、30fps、yuv420p」的 mp4，后续 overlay 才不用再兜底。
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path

from ..config import Settings
from .ffmpeg_tools import MediaRenderError, ffmpeg_executable, probe_duration_ms, run

logger = logging.getLogger(__name__)

# 占位主播卡按 2 倍出图再缩到小窗尺寸，避免小窗里显得糊。
_CARD_SCALE = 2


class DigitalHumanError(MediaRenderError):
    """数字人生成失败（provider 不可用、模型报错、超时）。"""


@dataclass(frozen=True)
class DigitalHumanSpec:
    """一次数字人生成的输入。"""

    reference_image: Path
    audio: Path
    duration_ms: int
    output: Path
    width: int
    height: int
    fps: int = 30

    def cache_key(self, provider: str, extra: str = "") -> str:
        """按「参考图 + 音频 + 目标尺寸 + provider」算缓存键。

        改稿重跑时音频会变（内容变了），键自然失效；只改字幕样式之类的重跑则命中缓存，
        不用为一个标点重新烧几分钟算力。
        """

        digest = hashlib.sha256()
        digest.update(provider.encode())
        digest.update(f"{self.width}x{self.height}@{self.fps}|{extra}".encode())
        for path, label in ((self.reference_image, "ref"), (self.audio, "audio")):
            digest.update(label.encode())
            try:
                digest.update(path.read_bytes())
            except OSError:
                # 读不到内容时退化成「路径 + 大小 + 修改时间」，至少不会误命中别的文件。
                stat = path.stat() if path.exists() else None
                digest.update(str(path).encode())
                digest.update(str(stat.st_size if stat else 0).encode())
                digest.update(str(int(stat.st_mtime) if stat else 0).encode())
        return digest.hexdigest()[:24]


class DigitalHumanProvider(ABC):
    """数字人 provider 契约。"""

    name: str = "base"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    @abstractmethod
    async def generate(self, spec: DigitalHumanSpec) -> Path:
        """产出一段与 spec.duration_ms 等长（误差 1 帧内）的说话视频。"""

    def _normalize(self, raw: Path, spec: DigitalHumanSpec) -> Path:
        """把任意来源的 mp4 统一成目标尺寸、帧率与时长。

        统一在这里做而不是各 provider 各写一遍：模型输出的分辨率五花八门，
        有的还带音轨，overlay 阶段一律不需要。
        """

        seconds = max(spec.duration_ms, 200) / 1000
        filters = (
            f"scale={spec.width}:{spec.height}:force_original_aspect_ratio=increase,"
            f"crop={spec.width}:{spec.height},fps={spec.fps},setsar=1,format=yuv420p"
        )
        normalized = spec.output.with_name(spec.output.stem + "-norm.mp4")
        run(
            [
                ffmpeg_executable(),
                "-y",
                "-i",
                str(raw),
                "-t",
                f"{seconds:.3f}",
                "-vf",
                filters,
                "-c:v",
                self._codec(),
                "-pix_fmt",
                "yuv420p",
                "-an",
                "-r",
                str(spec.fps),
                "-movflags",
                "+faststart",
                str(normalized),
            ],
            timeout=self._settings.digital_human_timeout_seconds,
        )
        # 模型输出常常短于旁白（比如最后一句没念完），补帧到等长，免得 overlay 时画面先断。
        actual_ms = probe_duration_ms(normalized)
        if actual_ms + 100 < spec.duration_ms:
            padded = spec.output.with_name(spec.output.stem + "-pad.mp4")
            run(
                [
                    ffmpeg_executable(),
                    "-y",
                    "-i",
                    str(normalized),
                    "-vf",
                    "tpad=stop_mode=clone:stop_duration=5",
                    "-t",
                    f"{seconds:.3f}",
                    "-c:v",
                    self._codec(),
                    "-pix_fmt",
                    "yuv420p",
                    "-an",
                    "-movflags",
                    "+faststart",
                    str(padded),
                ],
                timeout=self._settings.digital_human_timeout_seconds,
            )
            normalized = padded
        normalized.replace(spec.output)
        return spec.output

    def _codec(self) -> str:
        """小窗片段一律用 libx264；回退逻辑交给 compose 的逐级降级。"""

        return "libx264"


class PlaceholderProvider(DigitalHumanProvider):
    """离线占位主播：画一张静态卡，再让它轻轻呼吸。

    不是「假装有数字人」，而是让画中画的尺寸、位置、圆角、描边、遮挡关系在
    没有模型和显存的情况下也能一眼看准，方便先把版式定下来。
    """

    name = "placeholder"

    async def generate(self, spec: DigitalHumanSpec) -> Path:
        card = await asyncio.to_thread(self._draw_card, spec)
        await asyncio.to_thread(self._animate, card, spec)
        return spec.output

    def _draw_card(self, spec: DigitalHumanSpec) -> Path:
        from PIL import Image, ImageDraw, ImageFont

        width, height = spec.width * _CARD_SCALE, spec.height * _CARD_SCALE
        canvas = Image.new("RGB", (width, height), (18, 24, 40))
        draw = ImageDraw.Draw(canvas)

        # 竖直渐变背景，和小窗的深色调保持一致。
        for y in range(height):
            ratio = y / max(height - 1, 1)
            draw.line(
                [(0, y), (width, y)],
                fill=(
                    int(24 + 40 * ratio),
                    int(30 + 30 * ratio),
                    int(48 + 62 * ratio),
                ),
            )

        # 肩线 + 圆形头像剪影，远看就是一个半身主播的轮廓。
        head_r = int(width * 0.26)
        center = (width // 2, int(height * 0.40))
        draw.ellipse(
            (
                center[0] - head_r,
                center[1] - head_r,
                center[0] + head_r,
                center[1] + head_r,
            ),
            fill=(236, 241, 248),
        )
        shoulder_w = int(width * 0.78)
        draw.rounded_rectangle(
            (
                center[0] - shoulder_w // 2,
                center[1] + int(head_r * 0.55),
                center[0] + shoulder_w // 2,
                height,
            ),
            radius=int(width * 0.18),
            fill=(226, 233, 244),
        )

        font = self._font(int(width * 0.085))
        label = "数字人"
        if font is not None:
            box = draw.textbbox((0, 0), label, font=font)
            draw.text(
                ((width - (box[2] - box[0])) / 2, int(height * 0.80)),
                label,
                font=font,
                fill=(96, 106, 128),
            )

        # 左上角录影红点，一眼看出这是「正在说话」的窗口而不是静态图。
        draw.ellipse(
            (
                int(width * 0.06),
                int(height * 0.05),
                int(width * 0.06) + int(width * 0.055),
                int(height * 0.05) + int(width * 0.055),
            ),
            fill=(235, 87, 87),
        )

        path = spec.output.with_name("avatar-card.png")
        canvas.save(path, format="PNG")
        return path

    def _animate(self, card: Path, spec: DigitalHumanSpec) -> None:
        """静态卡 → 等长视频，叠加极轻的呼吸缩放，避免小窗死板。"""

        seconds = max(spec.duration_ms, 200) / 1000
        filters = (
            f"scale={spec.width}:{spec.height}:force_original_aspect_ratio=increase,"
            f"crop={spec.width}:{spec.height},"
            "zoompan=z='1.02+0.012*sin(on/26)':"
            "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':"
            f"d=1:s={spec.width}x{spec.height}:fps={spec.fps},"
            "setsar=1,format=yuv420p"
        )
        run(
            [
                ffmpeg_executable(),
                "-y",
                "-loop",
                "1",
                "-i",
                str(card),
                "-t",
                f"{seconds:.3f}",
                "-vf",
                filters,
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "20",
                "-pix_fmt",
                "yuv420p",
                "-an",
                "-movflags",
                "+faststart",
                str(spec.output),
            ],
            timeout=self._settings.digital_human_timeout_seconds,
        )

    @staticmethod
    def _font(size: int):
        from PIL import ImageFont

        for candidate in (
            "C:/Windows/Fonts/msyhbd.ttc",
            "C:/Windows/Fonts/msyh.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
        ):
            if Path(candidate).is_file():
                try:
                    return ImageFont.truetype(candidate, size)
                except OSError:
                    continue
        return None


class Wav2LipProvider(DigitalHumanProvider):
    """本地 Wav2Lip：只改嘴型，人脸来自参考图，显存约 2-4GB。

    小窗场景下这个精度的收益/成本比最高：观众看不到 320px 宽的嘴部细节，
    但「嘴在动」这件事本身已经贡献了绝大部分真实感。
    """

    name = "wav2lip"

    async def generate(self, spec: DigitalHumanSpec) -> Path:
        root = Path(self._settings.digital_human_wav2lip_dir)
        if not root.is_dir():
            raise DigitalHumanError(f"Wav2Lip 目录不存在：{root}")
        script = root / "inference.py"
        checkpoint = Path(self._settings.digital_human_wav2lip_checkpoint)
        if not script.is_file():
            raise DigitalHumanError(f"缺少 Wav2Lip inference.py：{script}")
        if not checkpoint.is_file():
            raise DigitalHumanError(f"缺少 Wav2Lip 权重：{checkpoint}")

        raw = spec.output.with_name("avatar-raw.mp4")
        python = self._settings.digital_human_python or os.sys.executable
        command = [
            python,
            str(script),
            "--checkpoint_path",
            str(checkpoint),
            "--face",
            str(spec.reference_image),
            "--audio",
            str(spec.audio),
            "--outfile",
            str(raw),
            # 小窗用不上整张脸，上下垫一点即可，垫太多会把人脸压得太小。
            "--pads",
            "0",
            "10",
            "0",
            "0",
            "--resize_factor",
            "1",
            "--nosmooth",
        ]
        if self._settings.digital_human_wav2lip_static:
            command.append("--static")
        logger.info("Wav2Lip 推理开始：%s", spec.reference_image.name)
        try:
            # Wav2Lip 是同步长任务，20 秒旁白在 MX450 上大约 3-6 分钟。
            await asyncio.to_thread(
                run, command, self._settings.digital_human_timeout_seconds, root
            )
        except MediaRenderError as exc:
            raise DigitalHumanError(f"Wav2Lip 推理失败：{exc}") from exc
        if not raw.is_file() or raw.stat().st_size == 0:
            raise DigitalHumanError("Wav2Lip 没有产出视频文件。")
        return await asyncio.to_thread(self._normalize, raw, spec)


class ApiProvider(DigitalHumanProvider):
    """远程推理：把参考图与音频 POST 给服务，拿回 mp4。

    请求体走 multipart，字段名可通过配置改，方便直接对接自建服务、
    ComfyUI 封装、或任何「图 + 音频 → 视频」的 HTTP 接口。
    """

    name = "api"

    async def generate(self, spec: DigitalHumanSpec) -> Path:
        import httpx

        endpoint = self._settings.digital_human_api_url.strip()
        if not endpoint:
            raise DigitalHumanError("DIGITAL_HUMAN_API_URL 未配置。")

        files = {
            self._settings.digital_human_api_image_field: (
                spec.reference_image.name,
                spec.reference_image.read_bytes(),
                "application/octet-stream",
            ),
            self._settings.digital_human_api_audio_field: (
                spec.audio.name,
                spec.audio.read_bytes(),
                "audio/wav",
            ),
        }
        headers = {}
        if self._settings.digital_human_api_key:
            headers["Authorization"] = f"Bearer {self._settings.digital_human_api_key}"

        raw = spec.output.with_name("avatar-raw.mp4")
        try:
            async with httpx.AsyncClient(
                timeout=self._settings.digital_human_timeout_seconds,
                follow_redirects=True,
            ) as client:
                response = await client.post(
                    endpoint, files=files, headers=headers
                )
            response.raise_for_status()
        except Exception as exc:  # noqa: BLE001 - 网络与状态码统一收敛
            raise DigitalHumanError(f"数字人 API 调用失败：{exc}") from exc

        content_type = response.headers.get("content-type", "")
        if "video" in content_type or "octet-stream" in content_type:
            raw.write_bytes(response.content)
        else:
            # 服务只回一个下载地址时，再取一次。
            payload = response.json()
            url = str(payload.get("video_url") or payload.get("url") or "")
            if not url:
                raise DigitalHumanError(f"API 未返回视频：{response.text[:200]}")
            async with httpx.AsyncClient(
                timeout=self._settings.digital_human_timeout_seconds,
                follow_redirects=True,
            ) as client:
                download = await client.get(url)
            download.raise_for_status()
            raw.write_bytes(download.content)

        if not raw.is_file() or raw.stat().st_size == 0:
            raise DigitalHumanError("数字人 API 返回空视频。")
        return await asyncio.to_thread(self._normalize, raw, spec)


def build_digital_human(settings: Settings) -> DigitalHumanProvider | None:
    """按配置造 provider；关闭时返回 None，调用方据此完全跳过这段链路。"""

    if not settings.digital_human_enabled:
        return None
    kind = (settings.digital_human_provider or "placeholder").strip().lower()
    if kind == "wav2lip":
        return Wav2LipProvider(settings)
    if kind == "api":
        return ApiProvider(settings)
    return PlaceholderProvider(settings)


class DigitalHumanCache:
    """按内容哈希复用已生成的数字人片段。

    GATE 上「改一个字重跑」是常态，而数字人是整条管线里最贵的一步；
    没有这层缓存，改字幕错别字也要重烧几分钟。
    """

    def __init__(self, root: Path) -> None:
        self._root = root

    def path_for(self, key: str) -> Path:
        return self._root / f"{key}.mp4"

    def get(self, key: str) -> Path | None:
        path = self.path_for(key)
        if path.is_file() and path.stat().st_size > 0:
            logger.info("数字人缓存命中：%s", key)
            return path
        return None

    def put(self, key: str, produced: Path) -> Path:
        target = self.path_for(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        if produced.resolve() != target.resolve():
            target.write_bytes(produced.read_bytes())
        return target


def rounded_mask_png(width: int, height: int, radius: int) -> bytes:
    """生成一张圆角矩形遮罩（白=保留，黑=透明）。

    用 Pillow 画比在 FFmpeg 里堆 geq 表达式稳得多，也方便微调圆角。
    """

    from PIL import Image, ImageDraw

    scale = 4  # 超采样后缩小，圆角边缘才不锯齿
    canvas = Image.new("L", (width * scale, height * scale), 0)
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(
        (0, 0, width * scale - 1, height * scale - 1),
        radius=radius * scale,
        fill=255,
    )
    canvas = canvas.resize((width, height), Image.LANCZOS)

    buffer = io.BytesIO()
    canvas.save(buffer, format="PNG")
    return buffer.getvalue()


def border_png(width: int, height: int, radius: int, thickness: int) -> bytes:
    """生成小窗描边（白色圆角环），叠在视频上做「贴纸」质感。"""

    from PIL import Image, ImageDraw

    scale = 4
    canvas = Image.new("RGBA", (width * scale, height * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(
        (0, 0, width * scale - 1, height * scale - 1),
        radius=radius * scale,
        outline=(255, 255, 255, 255),
        width=thickness * scale,
    )
    canvas = canvas.resize((width, height), Image.LANCZOS)

    buffer = io.BytesIO()
    canvas.save(buffer, format="PNG")
    return buffer.getvalue()
