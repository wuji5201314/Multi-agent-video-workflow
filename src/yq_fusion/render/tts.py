"""把文案合成配音并给出逐词时间轴。

移植自 MoneyPrinterTurbo 的 voice.py，只保留三条能直接落地的路线：

- edge：微软 Edge 在线合成，免费、无需 Key，并返回逐词 WordBoundary 时间轴；
- openai：任意 OpenAI 兼容的 /audio/speech 网关（例如硅基流动的 CosyVoice），只回音频；
- silent：不配音，用字数估算时长，保证整条链路依然能出片。

所有结果统一转成 48kHz 双声道 WAV，后续拼接口播和混音都不再受各家容器差异影响。
"""

from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass
from pathlib import Path

import httpx

from ..config import Settings
from .ffmpeg_tools import (
    MediaRenderError,
    ffmpeg_executable,
    probe_duration_ms,
    run,
    silence_wav,
)


logger = logging.getLogger(__name__)

# 只削尾部静音：先整体倒放，削掉"开头"（原尾部）静音，再倒回来。
# start_duration 是保留长度，留 120ms 尾音，避免收尾太突然。
TRIM_TAIL_FILTER = (
    "areverse,silenceremove=start_periods=1:start_duration=0.12:"
    "start_threshold=-40dB,areverse"
)
# 句号处的停顿被 TTS 拉长到 0.65-0.75s，听着像卡住；把长于 0.55s 的静音压回 0.30s。
# 只在整句字幕模式下启用：逐词字幕依赖真实的逐词时间轴，压了会错位。
TIGHTEN_PAUSE_FILTER = (
    "silenceremove=stop_periods=-1:stop_duration=0.30:"
    "stop_threshold=-40dB:detection=peak"
)

_MARKUP = re.compile(r"[`*>#\[\]{}<>|\\~^]")
_WHITESPACE = re.compile(r"\s+")
# 中文口播大约每秒 5 个字，仅在拿不到真实音频时用于估算时长。
CHARS_PER_SECOND = 5.2


@dataclass(frozen=True)
class WordCue:
    """一个词或短语在整句配音中的时间区间（毫秒）。"""

    text: str
    start_ms: int
    end_ms: int


@dataclass(frozen=True)
class SpeechAsset:
    """一段配音：音频文件、总时长与逐词时间轴。"""

    text: str
    audio_path: Path
    duration_ms: int
    words: tuple[WordCue, ...] = ()

    @property
    def word_level(self) -> bool:
        """是否拿到逐词时间轴；没有就只能按字数比例切字幕。"""

        return bool(self.words)


def normalize_script(text: str, *, limit: int = 400) -> str:
    """去掉模型爱写的 Markdown 记号与多余空白，避免把符号一起念出来。"""

    cleaned = _WHITESPACE.sub(" ", _MARKUP.sub("", text or "")).strip()
    if len(cleaned) > limit:
        cleaned = cleaned[:limit].rstrip("，。、；：,.;: ")
    return cleaned


def estimate_duration_ms(text: str) -> int:
    """按中文字数估算配音时长，只在没有真实音频时使用。"""

    return max(1200, int(len(text) / CHARS_PER_SECOND * 1000))


class VoiceSynthesizer:
    """按配置选择配音供应商，并把结果统一成 48kHz WAV。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def synthesize(self, text: str, output: Path) -> SpeechAsset:
        """合成一段口播；供应商异常时是否退化为静音由配置决定。"""

        script = normalize_script(text, limit=self._settings.media_render_tts_max_chars)
        if not script:
            return self._silent("", output, estimate_duration_ms(""))
        provider = self._settings.media_render_tts_provider.strip().lower()
        try:
            if provider == "edge":
                return await self._edge(script, output)
            if provider == "openai":
                return await self._openai(script, output)
            if provider == "silent":
                return self._silent(script, output, estimate_duration_ms(script))
            raise MediaRenderError(f"不支持的配音供应商：{provider}")
        except MediaRenderError:
            raise
        except Exception as exc:  # noqa: BLE001 - 网络、证书、解析异常都要兜底
            if self._settings.media_render_tts_fallback_silent:
                return self._silent(script, output, estimate_duration_ms(script))
            raise MediaRenderError(f"配音合成失败：{exc}") from exc

    async def _edge(self, script: str, output: Path) -> SpeechAsset:
        """用 edge-tts 合成，并利用 WordBoundary 事件得到逐词时间轴。"""

        try:
            import edge_tts
        except ImportError as exc:  # pragma: no cover - 依赖缺失时才有意义
            raise MediaRenderError("未安装 edge-tts，请执行 uv pip install edge-tts。") from exc

        raw = output.with_suffix(".mp3")
        audio = bytearray()
        words: list[WordCue] = []
        communicator = edge_tts.Communicate(
            script,
            self._settings.media_render_tts_voice,
            rate=self._settings.media_render_tts_rate,
            volume=self._settings.media_render_tts_volume,
            boundary="WordBoundary",
            connect_timeout=15,
            receive_timeout=90,
        )
        async for chunk in communicator.stream():
            if chunk["type"] == "audio":
                audio.extend(chunk["data"])
            elif chunk["type"] == "WordBoundary":
                # Edge 的时间单位是 100 纳秒，除以 10000 得到毫秒。
                start_ms = int(chunk["offset"]) // 10000
                duration_ms = int(chunk["duration"]) // 10000
                words.append(
                    WordCue(
                        text=str(chunk["text"]),
                        start_ms=start_ms,
                        end_ms=start_ms + max(duration_ms, 60),
                    )
                )
        if not audio:
            raise MediaRenderError("Edge 配音没有返回音频数据。")
        raw.write_bytes(bytes(audio))
        try:
            await asyncio.to_thread(self._to_wav, raw, output)
        finally:
            raw.unlink(missing_ok=True)
        await asyncio.to_thread(self._trim_tail, output)
        duration = await asyncio.to_thread(probe_duration_ms, output)
        return SpeechAsset(
            text=script,
            audio_path=output,
            duration_ms=duration,
            words=tuple(words),
        )

    async def _openai(self, script: str, output: Path) -> SpeechAsset:
        """调用 OpenAI 兼容的 /audio/speech 接口，仅返回音频无时间轴。"""

        base_url = self._settings.media_render_tts_base_url or self._settings.base_url
        api_key = self._settings.media_render_tts_api_key or self._settings.api_key
        payload = {
            "model": self._settings.media_render_tts_model,
            "input": script,
            "voice": self._settings.media_render_tts_voice,
            "response_format": "mp3",
            "speed": self._settings.media_render_tts_speed,
        }
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        async with httpx.AsyncClient(
            timeout=self._settings.media_render_tts_timeout_seconds,
            trust_env=False,
        ) as client:
            response = await client.post(
                f"{base_url.rstrip('/')}/audio/speech",
                json=payload,
                headers=headers,
            )
        if response.status_code >= 400:
            raise MediaRenderError(
                "配音接口返回错误："
                + response.text[:300]
            )
        raw = output.with_suffix(".mp3")
        raw.write_bytes(response.content)
        try:
            await asyncio.to_thread(self._to_wav, raw, output)
        finally:
            raw.unlink(missing_ok=True)
        await asyncio.to_thread(self._trim_tail, output)
        duration = await asyncio.to_thread(probe_duration_ms, output)
        return SpeechAsset(text=script, audio_path=output, duration_ms=duration)

    def _silent(self, script: str, output: Path, duration_ms: int) -> SpeechAsset:
        """无配音兜底：生成等长静音，字幕仍按估算时间轴推进。"""

        silence_wav(output, duration_ms)
        return SpeechAsset(text=script, audio_path=output, duration_ms=duration_ms)

    def _trim_tail(self, path: Path) -> None:
        """裁掉 TTS 音频尾部自带的空白。

        edge/openai 这类在线合成普遍在句末留 0.8-1s 静音，不裁的话每个镜头之间会先
        静 0.85s 再叠 250ms 换气间隙，成片听起来就是"话停了画面还在动"的卡顿。
        """

        filters = TRIM_TAIL_FILTER
        if self._settings.media_render_subtitle_mode != "word":
            filters += "," + TIGHTEN_PAUSE_FILTER
        trimmed = path.with_name(path.stem + "-trim.wav")
        try:
            run(
                [
                    ffmpeg_executable(),
                    "-y",
                    "-i",
                    str(path),
                    "-af",
                    filters,
                    "-ar",
                    "48000",
                    "-ac",
                    "2",
                    "-c:a",
                    "pcm_s16le",
                    str(trimmed),
                ],
                timeout=self._settings.media_render_timeout_seconds,
            )
        except MediaRenderError as exc:
            # 裁静音失败不该让整条任务失败，直接用原音频（只是会多一段空白）。
            logger.warning("裁掉配音尾部静音失败，沿用原音频：%s", exc)
            trimmed.unlink(missing_ok=True)
            return
        trimmed.replace(path)

    def _to_wav(self, source: Path, output: Path) -> None:
        """统一下混成 48kHz 双声道 WAV，便于后续无损拼接。"""

        run(
            [
                ffmpeg_executable(),
                "-y",
                "-i",
                str(source),
                "-vn",
                "-ar",
                "48000",
                "-ac",
                "2",
                "-c:a",
                "pcm_s16le",
                str(output),
            ],
            timeout=self._settings.media_render_timeout_seconds,
        )
