"""打印 pipeline.db 里最新一版 SCRIPT 与字幕，便于肉眼核对重复与断句。"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path


def main() -> None:
    db = Path(__file__).resolve().parents[1] / "output" / "pipeline.db"
    conn = sqlite3.connect(db)
    row = conn.execute("select payload from steps where code = ? order by id desc", ("SCRIPT",)).fetchone()
    if row:
        script = json.loads(row[0])['script']
        for index, shot in enumerate(script['shots'], start=1):
            print(f"[{index}] 大字: {shot['headline']}")
            print(f"    口播: {shot['body']}")
    print()
    for name, path in sorted((Path(__file__).resolve().parents[1] / "output").rglob("*.srt")):
        print(f"--- {name} ---")
        print(path.read_text(encoding="utf-8").strip())


if __name__ == "__main__":
    main()