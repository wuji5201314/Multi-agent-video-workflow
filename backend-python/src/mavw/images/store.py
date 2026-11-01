"""图片工作台的落库（SQLite），和管线库同一个 output 目录但独立建表。"""

from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from ..config import Settings

_SCHEMA = """
CREATE TABLE IF NOT EXISTS images (
    id              TEXT PRIMARY KEY,
    file            TEXT NOT NULL,
    width           INTEGER NOT NULL DEFAULT 0,
    height          INTEGER NOT NULL DEFAULT 0,
    seed            INTEGER NOT NULL DEFAULT 0,
    prompt          TEXT NOT NULL DEFAULT '',
    negative_prompt TEXT NOT NULL DEFAULT '',
    style           TEXT NOT NULL DEFAULT '',
    quality         TEXT NOT NULL DEFAULT '',
    model           TEXT NOT NULL DEFAULT '',
    reference       TEXT NOT NULL DEFAULT '',
    note            TEXT NOT NULL DEFAULT '',
    elapsed_ms      INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_images_created ON images(created_at DESC);
"""


class ImageStore:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._db = settings.output_dir() / "studio.db"

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        self._db.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(self._db)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def init(self) -> None:
        with self._connect() as conn:
            conn.executescript(_SCHEMA)

    def add(self, item: dict[str, Any]) -> None:
        self.init()
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO images (id, file, width, height, seed, prompt, negative_prompt,"
                " style, quality, model, reference, note, elapsed_ms, created_at)"
                " VALUES (:id, :file, :width, :height, :seed, :prompt, :negative_prompt,"
                " :style, :quality, :model, :reference, :note, :elapsed_ms, :created_at)",
                item,
            )

    def list(self, limit: int = 40) -> list[dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM images ORDER BY created_at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(row) for row in rows]

    def get(self, image_id: str) -> dict[str, Any] | None:
        self.init()
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM images WHERE id = ?", (image_id,)).fetchone()
        return dict(row) if row else None

    def delete(self, image_id: str) -> bool:
        self.init()
        with self._connect() as conn:
            cur = conn.execute("DELETE FROM images WHERE id = ?", (image_id,))
        return cur.rowcount > 0
