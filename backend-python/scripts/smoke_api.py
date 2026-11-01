"""HTTP 接口自测：起服务 → health → 建任务停在 GATE → 通过 → 直接渲染入口。

只跑到素材 GATE 就收尾（不触发 TTS/渲染），保证快速可回归。
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:8010"


def _call(method: str, path: str, body: dict | None = None) -> dict:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        f"{BASE}{path}", data=data, method=method, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    server = subprocess.Popen(
        [sys.executable, str(ROOT / "scripts" / "serve.py"), "--port", "8010"],
        cwd=str(ROOT),
    )
    try:
        for _ in range(40):
            try:
                health = _call("GET", "/api/health")
                break
            except Exception:
                time.sleep(0.5)
        else:
            print("服务未能启动")
            return 1
        print("[1] health:", health)

        task = _call("POST", "/api/tasks", {"brief": "AI剪辑入门"})
        assert task["status"] == "WAITING_INPUT" and task["current_step"] == "TOPIC", task["status"]
        print(f"[2] 建任务 #{task['task_id']} 停在 {task['current_step']}")

        task = _call(
            "POST",
            f"/api/tasks/{task['task_id']}/gates/TOPIC",
            {"decision": "approve", "payload": {"chosen_index": 2}},
        )
        assert task["current_step"] == "SCRIPT", task["current_step"]
        print(f"[3] 选题确认后推进到 {task['current_step']}")

        task = _call("POST", f"/api/tasks/{task['task_id']}/gates/SCRIPT", {"decision": "approve"})
        assert task["current_step"] == "MATERIAL", task["current_step"]
        materials = task["steps"][2]["payload"]["shots"]
        print(f"[4] 脚本确认后产出 {len(materials)} 个素材位，停在 {task['current_step']}")
        print("\nAPI 自测通过。")
        return 0
    finally:
        server.terminate()
        server.wait(timeout=15)


if __name__ == "__main__":
    raise SystemExit(main())
