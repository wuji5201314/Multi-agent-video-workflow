"""成品竖屏视频渲染编排。

链路顺序与 MoneyPrinterTurbo 的 task.py 一致，但每一步都做了裁剪：

    口播文本 → TTS 配音（含逐词时间轴）→ 字幕文件
             → 每张素材按配音长度生成动效片段 → 无重编码拼接
             → 烧字幕 → 合入旁白与 BGM → 上传 TOS → 返回 object key

与图片轮播共用同一条 Java 预签名上传通道，Java 侧只看到 format=VIDEO 和最终对象，
不需要感知 FFmpeg、TTS 与字幕细节。
"""

from __future__ import annotations

import asyncio
import logging
import random
import re
import tempfile
from pathlib import Path
from urllib.parse import urlparse

import httpx

from ..config import Settings
from ..storage import build_storage, internal_headers
from .card import render_cover_card
from .compose import ComposeOptions, VideoComposer, pip_origin, subtitle_reserve
from .digital_human import (
    DigitalHumanCache,
    DigitalHumanSpec,
    border_png,
    build_digital_human,
    rounded_mask_png,
)
from .ffmpeg_tools import (
    MediaRenderError,
    ffmpeg_executable,
    probe_duration_ms,
    run,
    silence_wav,
)
from .models import RenderResult
from .subtitle import SubtitleCue, build_cues, write_ass, write_srt
from .tts import SpeechAsset, VoiceSynthesizer, normalize_script

logger = logging.getLogger(__name__)

# 与 Java 端 InternalApiInterceptor.HEADER_INTERNAL_TOKEN 保持一致。
INTERNAL_TOKEN_HEADER = "X-Internal-Token"
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
AUDIO_SUFFIXES = {".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg"}
MAX_ASSET_BYTES = 200 * 1024 * 1024
MAX_VIDEO_BYTES = 500 * 1024 * 1024
# 每句话说完到下一镜头之间留的呼吸间隙；TTS 尾部静音已经裁掉，250ms 足够自然换气。
NARRATION_GAP_MS = 250
# 判断"正文是否在复述标题"时比较的净字数（去标点后）。
_REPEAT_PREFIX_CHARS = 6
_PUNCTUATION = re.compile(r"[，。！？；：、,.!?;:\s~～…\-—]")


class VideoPreviewRenderer:
    """把已审核的图片/视频素材合成带配音字幕的竖屏成品。"""

    def __init__(self, settings: Settings, storage: object | None = None) -> None:
        self._settings = settings
        self._options = ComposeOptions.from_settings(settings)
        self._composer = VideoComposer(self._options)
        self._voices = VoiceSynthesizer(settings)
        self._admin_base = settings.yanque_admin_base_url.rstrip("/")
        # 默认本地存储，离线也能出片；STORAGE_BACKEND=presign 时切回 Java 预签名 + TOS。
        self._storage = storage or build_storage(settings)
        # Java 端 /internal/** 由 InternalApiInterceptor 保护，缺这个头会 401。
        self._internal_headers = internal_headers(settings)
        # 数字人是可选层：DIGITAL_HUMAN_ENABLED=false 时 _avatar 为 None，整段逻辑不做。
        self._avatar = build_digital_human(settings)
        self._avatar_cache = DigitalHumanCache(settings.digital_human_cache())

    async def render(self, request: object) -> object:
        """按素材顺序合成一条完整成品视频并上传封面与 MP4。"""

        with tempfile.TemporaryDirectory(prefix="yanque-video-") as temp:
            workdir = Path(temp)
            # trust_env=False：绕开 Windows 系统代理，TOS 公网直连即可；
            # headers 只用于回环 presign，_download 会显式传空 headers 防止密钥外泄。
            async with httpx.AsyncClient(
                timeout=300.0,
                follow_redirects=True,
                trust_env=False,
                headers=self._internal_headers,
            ) as client:
                sources = await asyncio.gather(
                    *[
                        self._download(client, item.source_url, workdir / f"source-{index}")
                        for index, item in enumerate(request.items, start=1)
                    ]
                )
                speeches = await self._synthesize(request, workdir)
                clips, voices, cues, shot_keys = await self._build_shots(
                    request, sources, speeches, workdir
                )
                silent = workdir / "silent.mp4"
                await asyncio.to_thread(self._composer.concat, clips, silent)
                narration = workdir / "narration.wav"
                await asyncio.to_thread(self._composer.build_narration, voices, narration)
                picture = silent
                if self._avatar is not None:
                    # 画中画先叠、字幕后烧：字幕永远压在最上层，不会被小窗盖住；
                    # 而且一次覆盖全片，人物形象全程连续，不会在镜头切换处「换脸」。
                    picture = await self._apply_digital_human(
                        silent, narration, sources, workdir
                    )
                if cues:
                    picture = await self._burn(picture, cues, workdir)
                final = workdir / "final.mp4"
                await asyncio.to_thread(
                    self._composer.mux,
                    picture,
                    narration,
                    final,
                    bgm=self._pick_bgm(),
                    bgm_volume=self._settings.media_render_bgm_volume,
                )
                duration_ms = await asyncio.to_thread(probe_duration_ms, final)
                video_bytes = final.read_bytes()
                if not video_bytes or len(video_bytes) > MAX_VIDEO_BYTES:
                    raise MediaRenderError("成品视频为空或超过500MB。")
                cover_bytes, cover_path = await self._cover(request, workdir)
                cover_key = await self._upload(
                    client, request.preview_task_id, 0, "image/png", cover_bytes
                )
                video_key = await self._upload(
                    client, request.preview_task_id, 0, "video/mp4", video_bytes
                )
        return RenderResult(
            format="VIDEO",
            cover_object_key=cover_key,
            cover_file_size=len(cover_bytes),
            video_object_key=video_key,
            video_file_size=len(video_bytes),
            duration_ms=duration_ms,
            # 视频成品的画面都在同一支 MP4 里，没有逐镜头对象，因此不返回轮播条目。
            items=[],
        )

    async def _synthesize(self, request: object, workdir: Path) -> list[SpeechAsset]:
        """逐条合成口播；刻意串行，避免并发触发免费 TTS 的限流。"""

        speeches: list[SpeechAsset] = []
        for index, item in enumerate(request.items, start=1):
            script = _narration_text(item)
            audio = workdir / f"speech-{index:02d}.wav"
            speeches.append(await self._voices.synthesize(script, audio))
        return speeches

    async def _build_shots(
        self,
        request: object,
        sources: list[Path],
        speeches: list[SpeechAsset],
        workdir: Path,
    ) -> tuple[list[Path], list[Path], list[SubtitleCue], list[str]]:
        """按配音长度生成等长片段、对齐音轨并累积全局字幕时间轴。"""

        clips: list[Path] = []
        voices: list[Path] = []
        cues: list[SubtitleCue] = []
        shot_keys: list[str] = []
        offset = 0
        for index, (item, source, speech) in enumerate(
            zip(request.items, sources, speeches, strict=True), start=1
        ):
            # 发布方案里给了更长的镜头时长时以方案为准，但不短于配音所需时间。
            duration_ms = self._shot_duration(speech, item.duration_ms)
            clip = workdir / f"clip-{index:02d}.mp4"
            await asyncio.to_thread(
                self._render_clip, source, duration_ms, clip, index
            )
            voice = workdir / f"voice-{index:02d}.wav"
            await asyncio.to_thread(self._composer.align_audio, speech.audio_path, duration_ms, voice)
            actual_ms = await asyncio.to_thread(probe_duration_ms, clip)
            # 字幕时间轴以实际片段长度为准，避免累积误差导致音画错位。
            for cue in build_cues(
                speech,
                mode=self._settings.media_render_subtitle_mode,
                max_chars=self._settings.media_render_subtitle_max_chars,
            ):
                cues.append(
                    SubtitleCue(
                        start_ms=cue.start_ms + offset,
                        end_ms=cue.end_ms + offset,
                        text=cue.text,
                    )
                )
            offset += actual_ms
            clips.append(clip)
            voices.append(voice)
            shot_keys.append(item.need_id)
        return clips, voices, cues, shot_keys

    def _shot_duration(self, speech: SpeechAsset, planned_ms: int | None) -> int:
        """镜头时长严格跟着口播长度走。

        这里不用 max(planned) 抬升：方案里给的时长往往比实际配音长，抬上去只能补静音，
        观众听到的就是"话停了画面还在"。计划时长只作为上限参考，不允许把镜头拉长。
        """

        wanted = speech.duration_ms + NARRATION_GAP_MS
        if planned_ms:
            wanted = min(wanted, planned_ms) if planned_ms >= speech.duration_ms else wanted
        return max(
            self._settings.media_render_clip_min_ms,
            min(wanted, self._settings.media_render_clip_max_ms),
        )

    def _render_clip(self, source: Path, duration_ms: int, clip: Path, index: int) -> None:
        """静态图片走 Ken Burns，视频素材裁到等长；两者都统一画幅与帧率。"""

        if source.suffix.lower() in IMAGE_SUFFIXES:
            self._composer.render_image_clip(source, duration_ms, clip, variant=index)
        else:
            self._composer.render_video_clip(source, duration_ms, clip)

    async def _burn(self, video: Path, cues: list[SubtitleCue], workdir: Path) -> Path:
        """烧录 ASS 字幕；字幕开关关闭或渲染失败时退化为无字幕。"""

        if not self._settings.media_render_subtitle_enabled:
            return video
        ass = workdir / "subtitle.ass"
        write_ass(
            cues,
            ass,
            width=self._options.width,
            height=self._options.height,
            font=self._settings.media_render_subtitle_font,
            font_size=self._settings.media_render_subtitle_font_size,
            margin_v=self._settings.media_render_subtitle_margin_v,
            pop=self._settings.media_render_subtitle_pop,
        )
        write_srt(cues, workdir / "subtitle.srt")
        output = workdir / "subtitled.mp4"
        try:
            await asyncio.to_thread(self._composer.burn_subtitles, video, ass, output)
        except MediaRenderError as exc:
            # 部分精简版 FFmpeg 未编入 libass，此时宁可不烧字幕也要出片。
            logger.warning("字幕烧录失败，改为无字幕输出：%s", exc)
            return video
        return output

    async def _apply_digital_human(
        self,
        video: Path,
        narration: Path,
        sources: list[Path],
        workdir: Path,
    ) -> Path:
        """把数字人小窗叠到成片上；任何失败都退回无画中画的原片。

        数字人是整条链路里唯一依赖外部模型与算力的环节，它坏掉不应该让一条已经
        渲染完成的片子作废，所以这里不上抛异常，只记 warning 并出原片。
        """

        if self._avatar is None:
            return video
        settings = self._settings
        reference = self._reference_image(sources, workdir)
        if reference is None:
            logger.warning("没有可用的形象参考图，跳过数字人画中画。")
            return video

        width = max(64, settings.digital_human_pip_width)
        height = max(64, settings.digital_human_pip_height)
        radius = max(0, min(settings.digital_human_pip_radius, min(width, height) // 2))
        try:
            duration_ms = await asyncio.to_thread(probe_duration_ms, video)
            spec = DigitalHumanSpec(
                reference_image=reference,
                audio=narration,
                duration_ms=duration_ms,
                output=workdir / "avatar.mp4",
                width=width,
                height=height,
                fps=self._options.fps,
            )
            # 缓存键带上 provider 与摆放位置：换位置不该命中旧片段（旧片段已烧进边框位置感），
            # 但只改标题文案时音频未变，应当命中。
            cache_key = spec.cache_key(
                self._avatar.name,
                extra=f"{settings.digital_human_pip_position}|{width}x{height}",
            )
            cached = self._avatar_cache.get(cache_key)
            if cached is not None:
                avatar = workdir / "avatar-cached.mp4"
                avatar.write_bytes(cached.read_bytes())
            else:
                avatar = await self._avatar.generate(spec)
                self._avatar_cache.put(cache_key, avatar)

            mask = workdir / "pip-mask.png"
            mask.write_bytes(rounded_mask_png(width, height, radius))
            border = workdir / "pip-border.png"
            border.write_bytes(
                border_png(width, height, radius, max(1, settings.digital_human_pip_border))
            )
            x, y = pip_origin(
                settings.digital_human_pip_position,
                (self._options.width, self._options.height),
                (width, height),
                settings.digital_human_pip_margin,
                subtitle_reserve(
                    settings.media_render_subtitle_margin_v,
                    settings.media_render_subtitle_font_size,
                ),
            )
            output = workdir / "picture-with-avatar.mp4"
            await asyncio.to_thread(
                self._composer.compose_pip,
                video,
                avatar,
                mask,
                border,
                output,
                x=x,
                y=y,
                duration_ms=duration_ms,
            )
            logger.info(
                "数字人画中画已合成：provider=%s，位置=(%s,%s)，尺寸=%sx%s",
                self._avatar.name,
                x,
                y,
                width,
                height,
            )
            return output
        except Exception as exc:  # noqa: BLE001 - 画中画失败不应该让成片作废
            logger.warning("数字人画中画失败，退回无画中画输出：%s", exc)
            return video

    def _reference_image(self, sources: list[Path], workdir: Path) -> Path | None:
        """形象参考图：显式配置优先，其次借用第一镜的图片素材，最后从视频首帧抽一张。"""

        configured = self._settings.digital_human_reference_image.strip()
        if configured:
            path = Path(configured)
            if path.is_file():
                return path
            logger.warning("配置的形象参考图不存在：%s", path)

        for source in sources:
            if source.suffix.lower() in IMAGE_SUFFIXES and source.is_file():
                return source

        for source in sources:
            if source.suffix.lower() in IMAGE_SUFFIXES or not source.is_file():
                continue
            still = workdir / "avatar-reference.png"
            try:
                run(
                    [
                        ffmpeg_executable(),
                        "-y",
                        "-i",
                        str(source),
                        "-frames:v",
                        "1",
                        "-update",
                        "1",
                        str(still),
                    ],
                    timeout=60,
                )
                if still.is_file() and still.stat().st_size > 0:
                    return still
            except MediaRenderError as exc:
                logger.warning("从视频抽取形象参考帧失败：%s", exc)
        return None

    def _pick_bgm(self) -> Path | None:
        """从本地 BGM 目录随机挑一首，关闭开关或目录为空时返回 None。"""

        if not self._settings.media_render_bgm_enabled:
            return None
        directory = self._settings.media_render_bgm_dir
        if not directory:
            return None
        root = Path(directory)
        if not root.is_dir():
            logger.warning("BGM 目录不存在，忽略背景音乐：%s", root)
            return None
        candidates = [
            path
            for path in sorted(root.glob("**/*"))
            if path.is_file() and path.suffix.lower() in AUDIO_SUFFIXES
        ]
        return random.choice(candidates) if candidates else None

    async def _cover(self, request: object, workdir: Path) -> tuple[bytes, Path]:
        """封面用 Pillow 本地绘制，不依赖浏览器截图。"""

        cover = await asyncio.to_thread(
            render_cover_card,
            str(getattr(request, "cover_title", "") or ""),
            str(getattr(request, "publish_caption", "") or "")[:70],
            list(getattr(request, "tags", []) or []),
        )
        path = workdir / "cover.png"
        path.write_bytes(cover)
        return cover, path

    async def _download(self, client: httpx.AsyncClient, url: str, target: Path) -> Path:
        """支持 https 与本地路径，限制单个输入素材不超过 200 MB。

        本地路径用于单机流水线（file:// 或 Windows 盘符），这样 demo 不需要 TOS 也能跑。
        """

        scheme = urlparse(url).scheme
        if scheme in {"", "file"} or (len(url) > 2 and url[1] == ":"):
            local = Path(url[7:] if url.lower().startswith("file://") else url)
            if not local.is_file():
                raise MediaRenderError(f"本地素材不存在：{local}")
            payload = local.read_bytes()
            if not payload or len(payload) > MAX_ASSET_BYTES:
                raise MediaRenderError("成品素材为空或超过200MB。")
            path = target.with_suffix(local.suffix or ".png")
            path.write_bytes(payload)
            return path
        if scheme != "https":
            raise MediaRenderError("成品素材下载地址必须使用 HTTPS 或本地路径。")
        # 显式传空 headers：client 默认头里带内部密钥，不能带到 TOS 公网请求上。
        response = await client.get(url, headers={})
        response.raise_for_status()
        if not response.content or len(response.content) > MAX_ASSET_BYTES:
            raise MediaRenderError("成品素材为空或超过200MB。")
        suffix = _content_suffix(response.headers.get("content-type", ""), url)
        path = target.with_suffix(suffix)
        path.write_bytes(response.content)
        return path

    async def _upload(
        self,
        client: httpx.AsyncClient,
        task_id: int,
        sequence: int,
        content_type: str,
        content: bytes,
    ) -> str:
        """交给存储层：本地写盘或走 Java 预签名 + TOS，渲染侧不关心细节。"""

        return await self._storage.upload(client, task_id, sequence, content_type, content)


def _narration_text(item: object) -> str:
    """取这一镜要念的文本：默认只念正文。

    headline 已经作为大字显示在画面上，字幕又是从这段文本生成的；一旦把 headline 拼进
    口播，观众就会同时看到"大字标题 + 一字不差的字幕"，听感也像同一句说了两遍。
    所以有正文就只念正文，正文为空才退化成念标题。
    """

    headline = str(getattr(item, "headline", "") or "").strip()
    body = str(getattr(item, "body", "") or "").strip()
    if body:
        return normalize_script(body)
    return normalize_script(headline)


def _repeats_headline(headline: str, body: str) -> bool:
    """正文是否已经包含标题的信息（整句包含，或前几个净字相同）。"""

    def squeeze(text: str) -> str:
        return _PUNCTUATION.sub("", text)

    head, content = squeeze(headline), squeeze(body)
    if not head or not content:
        return False
    if head in content:
        return True
    prefix = min(len(head), _REPEAT_PREFIX_CHARS)
    return content.startswith(head[:prefix])


def _content_suffix(content_type: str, url: str) -> str:
    """结合响应类型和 URL 推断临时文件扩展名。"""

    if "video" in content_type or urlparse(url).path.lower().endswith(".mp4"):
        return ".mp4"
    if "png" in content_type:
        return ".png"
    if "webp" in content_type:
        return ".webp"
    return ".jpg"
