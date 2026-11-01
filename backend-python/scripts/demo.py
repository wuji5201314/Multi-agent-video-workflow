"""一条命令跑完整条管线：选题 → 脚本 → 素材 → 成片 → 发布单。

用法：
    python scripts/demo.py --brief "AI 剪辑入门" --auto          # 全自动，出片即止
    python scripts/demo.py --brief "AI 剪辑入门"                  # 每个 GATE 停下来等你确认
    python scripts/demo.py --task-id 3                            # 从中断处继续
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from mavw.config import get_settings  # noqa: E402
from mavw.pipeline.service import PipelineService  # noqa: E402
from mavw.pipeline.steps import Step  # noqa: E402


def _print_step(task: dict, step_code: str) -> None:
    row = next((item for item in task["steps"] if item["code"] == step_code), None)
    if row is None:
        return
    print(f"\n=== {row['title']}（{row['status']}）===")
    payload = row["payload"]
    if step_code == Step.TOPIC.value:
        for index, item in enumerate(payload.get("candidates") or []):
            mark = "*" if index == payload.get("chosen_index") else " "
            print(f" {mark} [{index}] {item['title']}  —— {item['angle']}")
            print(f"       钩子：{item['hook']}")
    elif step_code == Step.SCRIPT.value:
        script = payload.get("script") or {}
        print(f" 标题：{script.get('title')}")
        print(f" 封面：{script.get('cover_title')}")
        print(f" 文案：{script.get('caption')}")
        print(f" 标签：{script.get('tags')}")
        for index, shot in enumerate(script.get("shots") or []):
            print(f"  {index}. [{shot.get('headline')}] {shot.get('body')}")
    elif step_code == Step.MATERIAL.value:
        for item in payload.get("shots") or []:
            print(f"  {item['need_id']} {item['headline']} → {item['source_url']}")
    elif step_code == Step.RENDER.value:
        print(f" 成片：{payload.get('video_path')}")
        print(f" 封面：{payload.get('cover_path')}")
        print(f" 时长：{payload.get('duration_ms')} ms，大小：{payload.get('video_file_size')} bytes")
    elif step_code == Step.PUBLISH.value:
        print(json.dumps(payload.get("plan"), ensure_ascii=False, indent=2))


async def _interactive(task: dict, service: PipelineService) -> dict:
    step = Step(task["current_step"])
    _print_step(task, step.value)
    if step is Step.TOPIC:
        raw = input("选哪个选题？回车=0，输入编号或自定义标题：").strip()
        if raw.isdigit():
            return await service.submit(task["task_id"], step, "approve", payload={"chosen_index": int(raw)})
        if raw:
            return await service.submit(
                task["task_id"], step, "edit", note=raw, payload={"chosen_index": 0, "feedback": raw}
            )
        return await service.submit(task["task_id"], step, "approve", payload={"chosen_index": 0})
    raw = input("回车=通过，输入文字=打回并要求修改（会带着这句话重跑）：").strip()
    if raw:
        return await service.submit(task["task_id"], step, "reject", note=raw)
    return await service.submit(task["task_id"], step, "approve")


async def run(args: argparse.Namespace) -> int:
    settings = get_settings()
    service = PipelineService(settings)
    task = service.get_task(args.task_id) if args.task_id else service.create_task(args.brief)
    task_id = task["task_id"]
    print(f"任务 #{task_id} 已创建：{task['brief']}（数据库 {settings.db_path()}）")

    while True:
        task = await service.advance(task_id, auto=False)
        if task["status"] == "DONE":
            print("\n管线完成。")
            _print_step(task, Step.PUBLISH.value)
            return 0
        if task["status"] == "FAILED":
            failed = next((s for s in task["steps"] if s["status"] == "FAILED"), None)
            print(f"\n任务失败于 {failed['title']}：{failed['error']}")
            return 1
        if args.auto:
            task = await service.submit(task_id, Step(task["current_step"]), "approve")
            continue
        task = await _interactive(task, service)


def main() -> int:
    parser = argparse.ArgumentParser(description="Multi-agent-video-workflow 端到端演示")
    parser.add_argument("--brief", default="AI 剪辑入门", help="内容简报")
    parser.add_argument("--task-id", type=int, default=0, help="从已有任务继续")
    parser.add_argument("--auto", action="store_true", help="自动确认所有 GATE")
    args = parser.parse_args()
    if not args.task_id and not args.brief:
        parser.error("需要 --brief 或 --task-id")
    return asyncio.run(run(args))


if __name__ == "__main__":
    raise SystemExit(main())
