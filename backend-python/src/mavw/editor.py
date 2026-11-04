"""视频编辑工作台：对已上传素材做本地处理（ffmpeg）。

支持的操作（全部走 ffmpeg，不上传云端）：
- trim      裁剪时长（start/end 秒）
- scale     等比缩放到目标宽度（高度自适应）
- vertical  居中裁成 9:16 竖屏（适合抖音/小红书）
- mute      去掉音轨
- extract   只导出音轨为 mp3
- speed     变速（0.5x - 2x）
"""

from __future__ import annotations

import json
import shutil
import time
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path

from .config import Settings
from .render.ffmpeg_tools import MediaRenderError, ffmpeg_executable, probe_duration_ms, run

ACTIONS = {
    "trim": "裁剪时长",
    "vertical": "转竖屏 9:16",
    "scale": "等比缩放",
    "mute": "去掉声音",
    "extract": "导出音频",
    "speed": "变速",
}

UPLOAD_SUFFIXES = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v"}
MAX_UPLOAD_BYTES = 500 * 1024 * 1024  # 500MB


@dataclass(frozen=True)
class EditItem:
    id: str
    source: str          # 原始上传文件名
    action: str
    params: dict
    output: str          # 结果文件名
    url: str
    duration_ms: int
    size: int
    elapsed_ms: int
    created_at: str


class EditStudio:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self.root = settings.output_dir() / "studio" / "edit"
        self.uploads = self.root / "uploads"
        self.exports = self.root / "exports"
        self.uploads.mkdir(parents=True, exist_ok=True)
        self.exports.mkdir(parents=True, exist_ok=True)
        self._history_path = self.root / "_history.json"

    # ---------------- 上传 ----------------
    def save_upload(self, data: bytes, suffix: str) -> str:
        if len(data) > MAX_UPLOAD_BYTES:
            raise ValueError("素材超过 500MB，请压缩后再上传")
        suffix = suffix.lower() if suffix else ".mp4"
        if suffix not in UPLOAD_SUFFIXES:
            raise ValueError(f"不支持的素材格式：{suffix}")
        name = uuid.uuid4().hex[:12] + suffix
        (self.uploads / name).write_bytes(data)
        return name

    def upload_path(self, name: str) -> Path:
        return self._safe(self.uploads, name)

    def export_path(self, name: str) -> Path:
        return self._safe(self.exports, name)

    @staticmethod
    def _safe(base: Path, name: str) -> Path:
        target = (base / name).resolve()
        if base.resolve() not in target.parents and target != base.resolve():
            raise ValueError("非法文件名")
        return target

    def actions(self) -> list[dict]:
        return [{"key": k, "label": v} for k, v in ACTIONS.items()]

    # ---------------- 处理 ----------------
    def edit(
        self,
        source: str,
        action: str,
        *,
        start: float = 0.0,
        end: float = 0.0,
        width: int = 1080,
        speed: float = 1.0,
    ) -> EditItem:
        src = self.upload_path(source)
        if not src.exists():
            raise FileNotFoundError("素材不存在，请先上传")
        action = action if action in ACTIONS else "vertical"
        ffmpeg = ffmpeg_executable()
        out_name = uuid.uuid4().hex[:12] + (".mp3" if action == "extract" else ".mp4")
        out = self.exports / out_name
        params: dict = {}

        cmd = [ffmpeg, "-y", "-i", str(src)]
        if action == "trim":
            start = max(0.0, float(start or 0))
            params = {"start": start, "end": end}
            if start:
                cmd += ["-ss", f"{start:.3f}"]
            if end and end > start:
                cmd += ["-t", f"{(end - start):.3f}"]
            cmd += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac"]
        elif action == "vertical":
            params = {"width": width}
            # 等比放大到覆盖 目标宽 x 目标高(9:16) 后居中裁剪
            h = int(round(width * 16 / 9))
            vf = (
                f"scale={width}:{h}:force_original_aspect_ratio=increase,"
                f"crop={width}:{h}"
            )
            cmd += ["-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac"]
        elif action == "scale":
            params = {"width": width}
            cmd += ["-vf", f"scale={width}:-2", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac"]
        elif action == "mute":
            params = {}
            cmd += ["-an", "-c:v", "copy"]
        elif action == "extract":
            params = {}
            cmd += ["-vn", "-c:a", "libmp3lame", "-q:a", "4"]
        elif action == "speed":
            speed = min(2.0, max(0.5, float(speed or 1.0)))
            params = {"speed": speed}
            # 视频 setpts，音频 atempo（atempo 支持 0.5-2.0）
            vf = f"setpts={1/speed:.4f}*PTS"
            af = f"atempo={speed:.4f}"
            cmd += ["-filter_complex", f"[0:v]{vf}[v];[0:a]{af}[a]", "-map", "[v]", "-map", "[a]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac"]

        cmd += [str(out)]

        started = time.time()
        try:
            run(cmd, timeout=self._settings.media_render_timeout_seconds or 600)
        except MediaRenderError as exc:  # noqa: F841
            raise RuntimeError(f"ffmpeg 处理失败：{exc}") from exc
        elapsed = int((time.time() - started) * 1000)

        if not out.exists():
            raise RuntimeError("处理未生成输出文件")

        try:
            duration_ms = probe_duration_ms(out)
        except Exception:  # noqa: BLE001
            duration_ms = 0

        item = EditItem(
            id="edit-" + uuid.uuid4().hex[:12],
            source=source,
            action=action,
            params=params,
            output=out_name,
            url=f"/api/edit/files/{out_name}",
            duration_ms=duration_ms,
            size=out.stat().st_size,
            elapsed_ms=elapsed,
            created_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        )
        self._append(item)
        return item

    # ---------------- 历史 ----------------
    def _load(self) -> list[dict]:
        if not self._history_path.exists():
            return []
        try:
            return json.loads(self._history_path.read_text("utf-8")) or []
        except Exception:  # noqa: BLE001
            return []

    def _append(self, item: EditItem) -> None:
        rows = self._load()
        rows.insert(0, asdict(item))
        self._history_path.write_text(
            json.dumps(rows[:80], ensure_ascii=False, indent=2), "utf-8"
        )

    def history(self, limit: int = 40) -> list[dict]:
        return self._load()[:limit]

    def get(self, edit_id: str) -> dict | None:
        for row in self._load():
            if row.get("id") == edit_id:
                return row
        return None

    def delete(self, edit_id: str) -> bool:
        row = self.get(edit_id)
        if not row:
            return False
        rows = [r for r in self._load() if r.get("id") != edit_id]
        self._history_path.write_text(
            json.dumps(rows, ensure_ascii=False, indent=2), "utf-8"
        )
        # 顺手删掉产物文件
        try:
            out = self.exports / row.get("output", "")
            if out.exists():
                out.unlink()
        except Exception:  # noqa: BLE001
            pass
        return True
