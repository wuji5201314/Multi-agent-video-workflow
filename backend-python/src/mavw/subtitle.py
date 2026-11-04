"""字幕工作台：把一段文案切成字幕时间轴，并导出 ASS / SRT / VTT。

没有音频输入时，按标点与字数比例切分，用「每秒约 5.2 字」估算每条的时长（复用
``render.tts.estimate_duration_ms`` 的节奏），所以用户只贴一段口播稿就能立刻得到
一份可下载的字幕文件；这就是「字幕工坊」的独立价值——不必先跑一条完整视频管线。

底层复用 ``render.subtitle`` 的 ``build_cues`` / ``write_ass`` / ``write_srt``，
VTT 是本工作台额外补的一个轻量导出（很多剪辑软件与网页 <track> 直接吃 VTT）。

产物统一落盘到 ``output/studio/subtitle``，用 JSON 文件做历史，与配音工作台同构。
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import Settings
from .render.subtitle import SubtitleCue, build_cues, write_ass, write_srt
from .render.tts import SpeechAsset, estimate_duration_ms, normalize_script

logger = logging.getLogger(__name__)

MODES = (
    {"key": "sentence", "label": "整句"},
    {"key": "word", "label": "逐词"},
)


@dataclass(frozen=True)
class SubtitleItem:
    """一次字幕构建的产物。"""

    id: str
    base: str
    text: str
    mode: str
    max_chars: int
    cue_count: int
    duration_ms: int
    created_at: str
    files: dict[str, str]
    urls: dict[str, str]
    cues: list[dict[str, Any]]


class SubtitleStudio:
    """字幕工作台服务：切分、导出、历史。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._root = settings.output_dir() / "studio" / "subtitle"
        self._root.mkdir(parents=True, exist_ok=True)
        self._history_file = self._root / "_history.json"

    def options(self) -> dict[str, Any]:
        return {
            "modes": [dict(m) for m in MODES],
            "default_mode": self._settings.media_render_subtitle_mode,
            "max_chars": self._settings.media_render_subtitle_max_chars,
            "font": self._settings.media_render_subtitle_font,
            "font_size": self._settings.media_render_subtitle_font_size,
            "margin_v": self._settings.media_render_subtitle_margin_v,
        }

    def path_for(self, file_name: str) -> Path:
        path = (self._root / file_name).resolve()
        if path.parent != self._root.resolve() or not path.is_file():
            raise FileNotFoundError(file_name)
        return path

    def build(
        self,
        text: str,
        *,
        mode: str = "",
        max_chars: int = 0,
        duration_ms: int = 0,
    ) -> SubtitleItem:
        """把文案切成字幕，并落盘 ASS / SRT / VTT 三种格式。"""

        script = normalize_script(text, limit=2000)
        if not script:
            raise ValueError("文案为空，先写点要配字幕的内容。")
        mode = (mode or self._settings.media_render_subtitle_mode or "sentence").strip().lower()
        if mode not in {"sentence", "word"}:
            mode = "sentence"
        chars = max_chars if max_chars and max_chars > 0 else self._settings.media_render_subtitle_max_chars
        chars = max(4, min(int(chars), 40))
        # 没有真实音频：按字数估算总时长。给了 duration_ms 就尊重它。
        total_ms = duration_ms if duration_ms and duration_ms > 0 else estimate_duration_ms(script)

        # 无逐词时间轴时，word 模式也退回按文本切，保证永远能出字幕。
        speech = SpeechAsset(text=script, audio_path=Path("/dev/null"), duration_ms=total_ms, words=())
        cues: list[SubtitleCue] = build_cues(
            speech, mode=mode, max_chars=chars, min_ms=420
        )

        sub_id = "sub-" + uuid.uuid4().hex[:12]
        ass_path = self._root / f"{sub_id}.ass"
        srt_path = self._root / f"{sub_id}.srt"
        vtt_path = self._root / f"{sub_id}.vtt"

        write_ass(
            cues,
            ass_path,
            width=self._settings.media_render_width,
            height=self._settings.media_render_height,
            font=self._settings.media_render_subtitle_font,
            font_size=self._settings.media_render_subtitle_font_size,
            margin_v=self._settings.media_render_subtitle_margin_v,
            pop=self._settings.media_render_subtitle_pop,
        )
        write_srt(cues, srt_path)
        vtt_path.write_text(_to_vtt(cues), encoding="utf-8")

        # 实际覆盖到的时长按最后一条字幕结束时间收口，比估算更贴近真实。
        covered_ms = max((cue.end_ms for cue in cues), default=total_ms)
        item = SubtitleItem(
            id=sub_id,
            base=sub_id,
            text=script,
            mode=mode,
            max_chars=chars,
            cue_count=len(cues),
            duration_ms=covered_ms,
            created_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            files={"ass": ass_path.name, "srt": srt_path.name, "vtt": vtt_path.name},
            urls={
                "ass": f"/api/subtitle/files/{ass_path.name}",
                "srt": f"/api/subtitle/files/{srt_path.name}",
                "vtt": f"/api/subtitle/files/{vtt_path.name}",
            },
            cues=[{"start_ms": c.start_ms, "end_ms": c.end_ms, "text": c.text} for c in cues],
        )
        self._append_history(item)
        return item

    # ---------------- 历史 ----------------

    def _append_history(self, item: SubtitleItem) -> None:
        rows = self._read_history()
        record = asdict(item)
        # 历史里不用塞完整 cues，省点体积，只保留预览用前几条。
        record["cues"] = record["cues"][:6]
        rows.insert(0, record)
        rows = rows[:60]
        try:
            self._history_file.write_text(
                json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        except OSError as exc:  # noqa: BLE001
            logger.warning("写入字幕历史失败：%s", exc)

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
            files = row.get("files") or {}
            row["urls"] = {ext: f"/api/subtitle/files/{name}" for ext, name in files.items()}
        return rows[: max(1, limit)]

    def delete(self, sub_id: str) -> bool:
        rows = self._read_history()
        keep: list[dict[str, Any]] = []
        removed = False
        for row in rows:
            if row.get("id") == sub_id:
                for name in (row.get("files") or {}).values():
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


def _srt_time(ms: int) -> str:
    total = max(0, ms)
    hours, remainder = divmod(total, 3600000)
    minutes, remainder = divmod(remainder, 60000)
    seconds, millis = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}.{millis:03d}"


def _to_vtt(cues: list[SubtitleCue]) -> str:
    """WebVTT：与 SRT 类似，时间轴用点号且头部要 WEBVTT。"""

    lines = ["WEBVTT", ""]
    for cue in cues:
        lines.append(f"{_srt_time(cue.start_ms)} --> {_srt_time(cue.end_ms)}")
        lines.append(cue.text)
        lines.append("")
    return "\n".join(lines)
