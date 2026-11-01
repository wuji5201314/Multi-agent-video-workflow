"""可中断、可反馈的生成管线服务。

状态全部落在 SQLite（output/pipeline.db），所以进程随时可以停：
重启后接着 advance 就从断点继续，人工反馈写在 feedback 表里留痕。

步骤与产出（对齐 yq-admin aimedia 的语义）：
    TOPIC    选题候选        → 停在 GATE 等确认选题
    SCRIPT   口播脚本/分镜   → 停在 GATE 等改稿
    MATERIAL 素材匹配        → 停在 GATE 等换图/删镜头
    RENDER   成品视频        → 停在 GATE 等审片
    PUBLISH  发布单/投放     → 收尾
"""

from __future__ import annotations

import json
import logging
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from ..config import Settings
from ..generate import MaterialSpec, build_provider
from ..llm import ContentWriter, Script, Shot, Topic
from ..publish.adapters import build_adapters
from ..render.card import render_material_card
from ..render.models import RenderItem, RenderRequest, RenderResult
from .steps import GATES, STEP_ORDER, STEP_TITLES, Step, StepStatus, TaskStatus

logger = logging.getLogger(__name__)

# 模型出图时补上的画风约束：实拍照片、竖屏、不留文字（大字与字幕由渲染层叠加）。
_IMAGE_STYLE_HINT = (
    "实拍照片（不是截图、不是界面、不是图标、不是插画），竖屏 9:16 构图，自然光，浅景深，"
    "画面里不要出现任何文字、字母、水印或 logo"
)

# flux 画中文 UI 会得到满屏伪字，所以描述里出现这些词就改写成实拍隐喻。
_SCREEN_WORDS = (
    "界面", "截图", "屏幕", "按钮", "弹窗", "菜单", "图标", "列表", "进度条",
    "UI", "ui", "APP", "app", "App", "表格", "图表",
)


def _material_prompt(shot: dict[str, Any], brief: str) -> str:
    """把镜头里的画面描述扩成可直接出图的 prompt。"""

    base = str(shot.get("image_query") or "").strip() or str(shot.get("headline") or "").strip()
    if not base:
        base = f"{brief} 相关场景"
    if any(word in base for word in _SCREEN_WORDS):
        base = (
            f"{base}；改用实拍方式呈现：人手、设备与真实环境同框，"
            "屏幕内容不作为画面主体、不留可读文字"
        )
    return f"{base}。{_IMAGE_STYLE_HINT}"


_SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    brief        TEXT    NOT NULL,
    style        TEXT    NOT NULL DEFAULT '',
    status       TEXT    NOT NULL,
    current_step TEXT    NOT NULL,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);
CREATE TABLE IF NOT EXISTS steps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     INTEGER NOT NULL,
    code        TEXT    NOT NULL,
    order_no    INTEGER NOT NULL,
    status      TEXT    NOT NULL,
    payload     TEXT    NOT NULL DEFAULT '{}',
    feedback    TEXT    NOT NULL DEFAULT '',
    error       TEXT    NOT NULL DEFAULT '',
    updated_at  TEXT    NOT NULL,
    UNIQUE (task_id, code)
);
CREATE TABLE IF NOT EXISTS gate_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    INTEGER NOT NULL,
    step_code  TEXT    NOT NULL,
    decision   TEXT    NOT NULL,
    note       TEXT    NOT NULL DEFAULT '',
    payload    TEXT    NOT NULL DEFAULT '{}',
    created_at TEXT    NOT NULL
);
CREATE TABLE IF NOT EXISTS artifacts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    INTEGER NOT NULL,
    step_code  TEXT    NOT NULL,
    kind       TEXT    NOT NULL,
    path       TEXT    NOT NULL,
    size       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
);
"""

_IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}


def _now() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


class PipelineService:
    """管线对外唯一入口，API 层与脚本层都只调它。"""

    def __init__(self, settings: Settings, writer: ContentWriter | None = None) -> None:
        self._settings = settings
        self._db = settings.db_path()
        self._db.parent.mkdir(parents=True, exist_ok=True)
        self._writer = writer or ContentWriter(settings)
        self._adapters = build_adapters(settings.output_dir())
        self._renderer = None
        self._init_db()

    # ---------------- 基础设施 ----------------
    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        """每次操作独立连接，退出即关闭，避免 Windows 上句柄占用导致库文件删不掉。"""

        conn = sqlite3.connect(self._db)
        conn.row_factory = sqlite3.Row
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.executescript(_SCHEMA)

    def _renderer_instance(self):
        if self._renderer is None:
            # 延迟构造：TTS/FFmpeg 依赖只在真正渲染时才加载。
            from ..render.renderer import VideoPreviewRenderer

            self._renderer = VideoPreviewRenderer(self._settings)
        return self._renderer

    # ---------------- 任务 ----------------
    def create_task(self, brief: str, style: str = "") -> dict[str, Any]:
        brief = brief.strip()
        if not brief:
            raise ValueError("brief 不能为空")
        now = _now()
        with self._connect() as conn:
            cursor = conn.execute(
                "INSERT INTO tasks (brief, style, status, current_step, created_at, updated_at)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (brief, style, TaskStatus.RUNNING.value, Step.TOPIC.value, now, now),
            )
            task_id = int(cursor.lastrowid)
            conn.executemany(
                "INSERT INTO steps (task_id, code, order_no, status, updated_at)"
                " VALUES (?, ?, ?, ?, ?)",
                [
                    (task_id, step.value, index, StepStatus.PENDING.value, now)
                    for index, step in enumerate(STEP_ORDER)
                ],
            )
        return self.get_task(task_id)

    def get_task(self, task_id: int) -> dict[str, Any]:
        with self._connect() as conn:
            task = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
            if task is None:
                raise KeyError(f"任务不存在：{task_id}")
            steps = conn.execute(
                "SELECT * FROM steps WHERE task_id = ? ORDER BY order_no", (task_id,)
            ).fetchall()
            logs = conn.execute(
                "SELECT * FROM gate_log WHERE task_id = ? ORDER BY id", (task_id,)
            ).fetchall()
            artifacts = conn.execute(
                "SELECT * FROM artifacts WHERE task_id = ? ORDER BY id", (task_id,)
            ).fetchall()
        return {
            "task_id": task_id,
            "brief": task["brief"],
            "style": task["style"],
            "status": task["status"],
            "current_step": task["current_step"],
            "created_at": task["created_at"],
            "updated_at": task["updated_at"],
            "steps": [
                {
                    "code": row["code"],
                    "title": STEP_TITLES[Step(row["code"])],
                    "status": row["status"],
                    "payload": json.loads(row["payload"]),
                    "feedback": row["feedback"],
                    "error": row["error"],
                    "updated_at": row["updated_at"],
                }
                for row in steps
            ],
            "gate_log": [dict(row) for row in logs],
            "artifacts": [dict(row) for row in artifacts],
        }

    def list_tasks(self, limit: int = 20) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM tasks ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(row) for row in rows]

    def _step_row(self, conn: sqlite3.Connection, task_id: int, step: Step) -> sqlite3.Row:
        row = conn.execute(
            "SELECT * FROM steps WHERE task_id = ? AND code = ?", (task_id, step.value)
        ).fetchone()
        if row is None:
            raise KeyError(f"任务 {task_id} 没有步骤 {step.value}")
        return row

    def _update_step(
        self,
        task_id: int,
        step: Step,
        *,
        status: StepStatus,
        payload: dict[str, Any] | None = None,
        feedback: str | None = None,
        error: str | None = None,
    ) -> None:
        sets = ["status = ?", "updated_at = ?"]
        values: list[Any] = [status.value, _now()]
        if payload is not None:
            sets.append("payload = ?")
            values.append(json.dumps(payload, ensure_ascii=False))
        if feedback is not None:
            sets.append("feedback = ?")
            values.append(feedback)
        if error is not None:
            sets.append("error = ?")
            values.append(error)
        values.extend([task_id, step.value])
        with self._connect() as conn:
            conn.execute(
                f"UPDATE steps SET {', '.join(sets)} WHERE task_id = ? AND code = ?", values
            )

    def _touch_task(self, task_id: int, status: TaskStatus, current: Step) -> None:
        with self._connect() as conn:
            conn.execute(
                "UPDATE tasks SET status = ?, current_step = ?, updated_at = ? WHERE id = ?",
                (status.value, current.value, _now(), task_id),
            )

    def _add_artifact(self, task_id: int, step: Step, kind: str, path: Path) -> None:
        size = path.stat().st_size if path.is_file() else 0
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO artifacts (task_id, step_code, kind, path, size, created_at)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (task_id, step.value, kind, str(path), size, _now()),
            )

    # ---------------- 推进 ----------------
    async def advance(self, task_id: int, auto: bool | None = None) -> dict[str, Any]:
        """从当前位置往下跑到下一个 GATE（或跑完）。

        auto=True 时自动确认每个 gate（--auto 演示模式）；
        auto=False/None 时每个 GATE 都会停下并返回 waiting。
        """

        auto_approve = self._settings.pipeline_auto_approve if auto is None else auto
        guard = 0
        while True:
            guard += 1
            if guard > 40:
                raise RuntimeError("管线推进次数异常，疑似死循环。")
            task = self.get_task(task_id)
            pending = next(
                (row for row in task["steps"] if row["status"] != StepStatus.SUCCEEDED.value),
                None,
            )
            if pending is None:
                self._touch_task(task_id, TaskStatus.DONE, Step.PUBLISH)
                return self.get_task(task_id)
            step = Step(pending["code"])
            if pending["status"] == StepStatus.WAITING.value:
                if not auto_approve:
                    self._touch_task(task_id, TaskStatus.WAITING_INPUT, step)
                    return self.get_task(task_id)
                await self.submit(task_id, step, "approve")
                continue
            self._touch_task(task_id, TaskStatus.RUNNING, step)
            self._update_step(task_id, step, status=StepStatus.RUNNING)
            try:
                payload = await self._run_step(task_id, step)
            except Exception as exc:  # noqa: BLE001 - 失败要落库供人工重试
                logger.exception("步骤执行失败：%s", step.value)
                self._update_step(task_id, step, status=StepStatus.FAILED, error=str(exc))
                self._touch_task(task_id, TaskStatus.FAILED, step)
                return self.get_task(task_id)
            gate = step in GATES
            self._update_step(
                task_id,
                step,
                status=StepStatus.WAITING if gate else StepStatus.SUCCEEDED,
                payload=payload,
                error="",
            )
            if not gate:
                continue

    # ---------------- 人工反馈 ----------------
    async def submit(
        self,
        task_id: int,
        step: Step | str,
        decision: str = "approve",
        note: str = "",
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """在某个 GATE 上做决定：approve 通过 / edit 改完再跑 / reject 打回重做。"""

        step = step if isinstance(step, Step) else Step(step)
        decision = decision.lower()
        if decision not in {"approve", "edit", "reject"}:
            raise ValueError("decision 只能是 approve / edit / reject")
        task = self.get_task(task_id)
        current = next((row for row in task["steps"] if row["code"] == step.value), None)
        if current is None:
            raise KeyError(f"任务 {task_id} 没有步骤 {step.value}")
        merged = dict(current["payload"])
        if payload:
            merged.update(payload)
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO gate_log (task_id, step_code, decision, note, payload, created_at)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (task_id, step.value, decision, note, json.dumps(payload or {}, ensure_ascii=False), _now()),
            )
        if decision == "approve":
            self._update_step(task_id, step, status=StepStatus.SUCCEEDED, payload=merged, feedback=note)
        else:
            # 改稿/打回：保留 payload 覆盖项，步骤回到 PENDING 重新执行。
            self._update_step(task_id, step, status=StepStatus.PENDING, payload=merged, feedback=note, error="")
            # 后续步骤的产物已失效，一并重置。
            later = [s for s in STEP_ORDER[STEP_ORDER.index(step) + 1:]]
            for code in later:
                self._update_step(task_id, code, status=StepStatus.PENDING, payload={}, error="")
        return await self.advance(task_id)

    # ---------------- 各步骤实现 ----------------
    async def _run_step(self, task_id: int, step: Step) -> dict[str, Any]:
        task = self.get_task(task_id)
        if step is Step.TOPIC:
            return await self._step_topic(task)
        if step is Step.SCRIPT:
            return await self._step_script(task)
        if step is Step.MATERIAL:
            return await self._step_material(task)
        if step is Step.RENDER:
            return await self._step_render(task)
        if step is Step.PUBLISH:
            return await self._step_publish(task)
        raise ValueError(f"未知步骤：{step}")

    def _payload(self, task: dict[str, Any], step: Step) -> dict[str, Any]:
        row = next((row for row in task["steps"] if row["code"] == step.value), None)
        return dict(row["payload"]) if row else {}

    async def _step_topic(self, task: dict[str, Any]) -> dict[str, Any]:
        previous = self._payload(task, Step.TOPIC)
        candidates = await self._writer.topics(
            task["brief"], self._settings.pipeline_topic_candidates
        )
        chosen = previous.get("chosen_index")
        return {
            "candidates": [
                {"title": item.title, "angle": item.angle, "hook": item.hook} for item in candidates
            ],
            "chosen_index": chosen,
            "feedback": previous.get("feedback", ""),
        }

    async def _step_script(self, task: dict[str, Any]) -> dict[str, Any]:
        topic_payload = self._payload(task, Step.TOPIC)
        candidates = topic_payload.get("candidates") or []
        index = int(topic_payload.get("chosen_index") or 0)
        if candidates:
            picked = candidates[min(max(index, 0), len(candidates) - 1)]
            topic = Topic(
                title=picked.get("title", ""),
                angle=picked.get("angle", ""),
                hook=picked.get("hook", ""),
            )
        else:
            topic = Topic(title=task["brief"])
        script: Script = await self._writer.script(
            task["brief"],
            topic,
            self._settings.pipeline_shot_count,
            note=self._payload(task, Step.TOPIC).get("feedback", ""),
        )
        previous = self._payload(task, Step.SCRIPT)
        shots = [shot.__dict__ for shot in script.shots]
        if previous.get("script", {}).get("shots"):
            # 人工改过稿就以人工版本为准，模型只补齐缺失字段。
            shots = previous["script"]["shots"]
        return {
            "script": {
                "title": script.title,
                "cover_title": script.cover_title,
                "caption": script.caption,
                "tags": script.tags,
                "shots": [dict(shot) for shot in shots],
            }
        }

    async def _step_material(self, task: dict[str, Any]) -> dict[str, Any]:
        """为每个镜头准备一张图：人工指定的优先，其次本地素材库，最后交给出图 provider。"""

        script = self._payload(task, Step.SCRIPT).get("script") or {}
        shots = list(script.get("shots") or [])
        tags = tuple(script.get("tags") or [])
        previous = self._payload(task, Step.MATERIAL).get("shots") or []
        override = {item.get("index"): item for item in previous if item.get("index") is not None}
        library = self._material_library()
        task_dir = self._settings.output_dir() / "assets" / "materials" / f"task-{task['task_id']}"
        task_dir.mkdir(parents=True, exist_ok=True)
        provider = build_provider(self._settings)
        items: list[dict[str, Any]] = []
        for index, shot in enumerate(shots):
            chosen = override.get(index, {})
            image_path = chosen.get("image_path")
            prompt = str(chosen.get("image_prompt") or _material_prompt(shot, task["brief"])).strip()
            if image_path and Path(image_path).is_file():
                # 人工指定过、或上一轮已定稿的图直接复用，不重复烧模型额度。
                path = Path(image_path)
                source = "manual"
            elif library:
                path = library[index % len(library)]
                source = "library"
            else:
                path = task_dir / f"shot-{index:02d}.png"
                spec = MaterialSpec(
                    index=index,
                    prompt=prompt,
                    headline=str(shot.get("headline") or ""),
                    body=str(shot.get("body") or ""),
                    tags=tags,
                    width=self._settings.media_render_width,
                    height=self._settings.media_render_height,
                    seed=self._settings.image_seed,
                )
                path = await provider.generate(spec, path)
                source = provider.name
            items.append(
                {
                    "index": index,
                    "need_id": f"N{index + 1:02d}",
                    "asset_type": "GENERATED_IMAGE",
                    "image_path": str(path),
                    "source_url": str(path),
                    "headline": shot.get("headline", ""),
                    "body": shot.get("body", ""),
                    "duration_ms": shot.get("duration_ms"),
                    "image_query": shot.get("image_query", ""),
                    "image_prompt": prompt,
                    "generated_by": source,
                }
            )
        return {"shots": items}

    def _material_library(self) -> list[Path]:
        raw = self._settings.pipeline_material_dir
        if not raw:
            return []
        root = Path(raw)
        if not root.is_dir():
            logger.warning("素材目录不存在，忽略：%s", root)
            return []
        return [
            path
            for path in sorted(root.glob("**/*"))
            if path.is_file() and path.suffix.lower() in _IMAGE_SUFFIXES
        ]

    async def _step_render(self, task: dict[str, Any]) -> dict[str, Any]:
        script = self._payload(task, Step.SCRIPT).get("script") or {}
        material = self._payload(task, Step.MATERIAL).get("shots") or []
        if not material:
            raise RuntimeError("没有素材，无法渲染。")
        request = RenderRequest(
            preview_task_id=task["task_id"],
            format="VIDEO",
            cover_title=script.get("cover_title") or script.get("title") or task["brief"],
            publish_caption=script.get("caption") or "",
            tags=script.get("tags") or [],
            items=[
                RenderItem(
                    need_id=item["need_id"],
                    asset_type=item.get("asset_type") or "GENERATED_IMAGE",
                    source_url=item["source_url"],
                    headline=item.get("headline", ""),
                    body=item.get("body", ""),
                    duration_ms=item.get("duration_ms"),
                )
                for item in material
            ],
        )
        result: RenderResult = await self._renderer_instance().render(request)
        storage = getattr(self._renderer_instance(), "_storage", None)
        video_path = ""
        cover_path = ""
        if hasattr(storage, "path_for") and result.video_object_key:
            video_path = str(storage.path_for(result.video_object_key))
            cover_path = str(storage.path_for(result.cover_object_key))
            for path, kind in ((Path(video_path), "video"), (Path(cover_path), "cover")):
                if path.is_file():
                    self._add_artifact(task["task_id"], Step.RENDER, kind, path)
        return {
            "video_object_key": result.video_object_key,
            "video_file_size": result.video_file_size,
            "cover_object_key": result.cover_object_key,
            "duration_ms": result.duration_ms,
            "video_path": video_path,
            "cover_path": cover_path,
        }

    async def _step_publish(self, task: dict[str, Any]) -> dict[str, Any]:
        script = self._payload(task, Step.SCRIPT).get("script") or {}
        render = self._payload(task, Step.RENDER)
        payload = {
            "task_id": task["task_id"],
            "brief": task["brief"],
            "title": script.get("title") or "",
            "caption": script.get("caption") or "",
            "tags": script.get("tags") or [],
            "video_path": render.get("video_path") or "",
            "duration_ms": render.get("duration_ms"),
            "platforms": ["douyin", "xiaohongshu"],
        }
        results = [await adapter.publish(payload) for adapter in self._adapters]
        return {"plan": results, "payload": payload}

    # ---------------- 直接渲染入口（Java 对接用） ----------------
    async def render_preview(self, request: RenderRequest) -> RenderResult:
        return await self._renderer_instance().render(request)