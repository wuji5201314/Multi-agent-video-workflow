"""配音工作台：把一段文案合成语音，可选音色、语速、音调与停顿。

底层直接复用 ``render.tts.VoiceSynthesizer``（edge-tts / OpenAI 兼容网关 / 静音兜底），
这里只负责：把用户可选的参数（音色、语速、音量）临时覆盖到配置上，产物统一落盘到
``output/studio/audio``，再用一个轻量 JSON 文件做历史记录——不写 SQLite，避免为了
一个"试听 + 下载"功能再引一套表结构。

设计取舍：
- 合成是秒级 I/O（edge-tts 走网络），单位是一次请求，因此串行即可，不做并发；
- 参数覆盖用 ``dataclasses.replace`` 生成一份新的 Settings 快照传入合成器，
  不污染全局 ``get_settings()`` 缓存；
- 音色清单内置 edge-tts 的常用中文音色 + 少量英文音色，够"多音色"选项使用；
  真正可用的音色仍以 edge-tts 服务端为准，这里只是给前端一个可点的下拉。
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import asdict, dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import Settings
from .render.tts import SpeechAsset, VoiceSynthesizer, estimate_duration_ms, normalize_script

logger = logging.getLogger(__name__)

# 前端"多音色"下拉使用的内置音色清单。label 给中文可读名，lang 用于分组。
VOICES: tuple[dict[str, str], ...] = (
    {"id": "zh-CN-YunxiNeural", "label": "云希 · 男声 · 阳光", "lang": "zh-CN"},
    {"id": "zh-CN-YunjianNeural", "label": "云健 · 男声 · 浑厚", "lang": "zh-CN"},
    {"id": "zh-CN-YunyangNeural", "label": "云扬 · 男声 · 播报", "lang": "zh-CN"},
    {"id": "zh-CN-YunxiaNeural", "label": "云夏 · 男声 · 少年", "lang": "zh-CN"},
    {"id": "zh-CN-XiaoxiaoNeural", "label": "晓晓 · 女声 · 温暖", "lang": "zh-CN"},
    {"id": "zh-CN-XiaoyiNeural", "label": "晓伊 · 女声 · 元气", "lang": "zh-CN"},
    {"id": "zh-CN-liaoning-XiaobeiNeural", "label": "晓北 · 女声 · 东北", "lang": "zh-CN"},
    {"id": "zh-CN-shaanxi-XiaoniNeural", "label": "晓妮 · 女声 · 陕西", "lang": "zh-CN"},
    {"id": "zh-HK-HiuMaanNeural", "label": "曉曼 · 女声 · 粤语", "lang": "zh-HK"},
    {"id": "en-US-AriaNeural", "label": "Aria · 女声 · 英文", "lang": "en-US"},
    {"id": "en-US-GuyNeural", "label": "Guy · 男声 · 英文", "lang": "en-US"},
)

_DEFAULT_VOICE = "zh-CN-YunxiNeural"
# 语速/音量的取值范围，防止前端传怪值把合成参数搞坏。
_RATE_MIN, _RATE_MAX = -50, 100
_VOLUME_MIN, _VOLUME_MAX = -50, 100


@dataclass(frozen=True)
class SpeechItem:
    """一条配音产物（可直接被前端播放/下载）。"""

    id: str
    file: str
    url: str
    text: str
    voice: str
    rate: str
    volume: str
    duration_ms: int
    elapsed_ms: int
    created_at: str


class AudioStudio:
    """配音工作台服务：合成、落盘、历史。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._root = settings.output_dir() / "studio" / "audio"
        self._root.mkdir(parents=True, exist_ok=True)
        self._history_file = self._root / "_history.json"

    # ---------------- 查询 ----------------

    def voices(self) -> list[dict[str, str]]:
        """返回内置音色清单；默认音色排在第一位便于前端高亮。"""

        ordered = sorted(VOICES, key=lambda v: 0 if v["id"] == self._current_voice() else 1)
        return [dict(v) for v in ordered]

    def _current_voice(self) -> str:
        return self._settings.media_render_tts_voice or _DEFAULT_VOICE

    def path_for(self, file_name: str) -> Path:
        """只允许读取本工作台落盘的音频，防目录穿越。"""

        path = (self._root / file_name).resolve()
        if path.parent != self._root.resolve() or not path.is_file():
            raise FileNotFoundError(file_name)
        return path

    # ---------------- 合成 ----------------

    async def synthesize(
        self,
        text: str,
        *,
        voice: str = "",
        rate: float = 0.0,
        volume: float = 0.0,
        provider: str = "",
    ) -> SpeechItem:
        """把文案合成为一段音频，返回可播放/下载的条目。"""

        script = normalize_script(text, limit=self._settings.media_render_tts_max_chars)
        if not script:
            raise ValueError("文案为空，先写点要念的内容。")
        voice = (voice or self._current_voice()).strip() or _DEFAULT_VOICE
        clamped_rate = max(_RATE_MIN, min(int(round(rate)), _RATE_MAX))
        clamped_volume = max(_VOLUME_MIN, min(int(round(volume)), _VOLUME_MAX))

        # 用配置快照覆盖成当前请求的参数，合成器本身不改动。
        settings = replace(
            self._settings,
            media_render_tts_provider=(provider or self._settings.media_render_tts_provider),
            media_render_tts_voice=voice,
            media_render_tts_rate=f"{clamped_rate:+d}%",
            media_render_tts_volume=f"{clamped_volume:+d}%",
        )

        audio_id = "tts-" + uuid.uuid4().hex[:12]
        output = self._root / f"{audio_id}.wav"
        synthesizer = VoiceSynthesizer(settings)

        started = time.monotonic()
        asset: SpeechAsset = await synthesizer.synthesize(script, output)
        elapsed_ms = int((time.monotonic() - started) * 1000)
        duration = asset.duration_ms or estimate_duration_ms(script)

        item = SpeechItem(
            id=audio_id,
            file=output.name,
            url=f"/api/audio/files/{output.name}",
            text=script,
            voice=voice,
            rate=f"{clamped_rate:+d}%",
            volume=f"{clamped_volume:+d}%",
            duration_ms=duration,
            elapsed_ms=elapsed_ms,
            created_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        )
        self._append_history(item)
        return item

    # ---------------- 历史 ----------------

    def _append_history(self, item: SpeechItem) -> None:
        rows = self._read_history()
        rows.insert(0, asdict(item))
        # 只保留最近 60 条，避免无限膨胀。
        rows = rows[:60]
        try:
            self._history_file.write_text(
                json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        except OSError as exc:  # noqa: BLE001 - 历史写失败不该让合成结果作废
            logger.warning("写入配音历史失败：%s", exc)

    def _read_history(self) -> list[dict[str, Any]]:
        if not self._history_file.is_file():
            return []
        try:
            data = json.loads(self._history_file.read_text(encoding="utf-8"))
            return [row for row in data if isinstance(row, dict)]
        except (OSError, ValueError):
            return []

    def history(self, limit: int = 30) -> list[dict[str, Any]]:
        rows = self._read_history()
        for row in rows:
            name = row.get("file") or ""
            row["url"] = f"/api/audio/files/{name}"
        return rows[: max(1, limit)]

    def delete(self, audio_id: str) -> bool:
        rows = self._read_history()
        keep: list[dict[str, Any]] = []
        removed = False
        for row in rows:
            if row.get("id") == audio_id:
                name = row.get("file") or ""
                if name:
                    try:
                        (self._root / name).unlink(missing_ok=True)
                    except OSError:
                        pass
                removed = True
                continue
            keep.append(row)
        if removed:
            try:
                self._history_file.write_text(
                    json.dumps(keep, ensure_ascii=False, indent=2), encoding="utf-8"
                )
            except OSError:
                pass
        return removed
