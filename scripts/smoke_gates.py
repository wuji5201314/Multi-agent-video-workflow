"""验证「可中断 + 可反馈」：不打网络、不渲染，只跑 GATE 状态机。

覆盖：建任务 → 停在选题 GATE → 选第 2 个选题 → 停在脚本 GATE →
打回并附修改要求 → 脚本重跑（后续步骤产物失效）→ 进程重启后从库里恢复继续。
"""

from __future__ import annotations

import asyncio
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from yq_fusion.config import Settings  # noqa: E402
from yq_fusion.pipeline.service import PipelineService  # noqa: E402
from yq_fusion.pipeline.steps import Step  # noqa: E402


def _settings(tmp: Path) -> Settings:
    return Settings(pipeline_db_path=str(tmp / "gates.db"), pipeline_output_dir=str(tmp))


async def main() -> int:
    with tempfile.TemporaryDirectory(prefix="yq-gates-") as temp:
        tmp = Path(temp)
        service = PipelineService(_settings(tmp))
        task = service.create_task("AI剪辑入门")
        task_id = task["task_id"]

        task = await service.advance(task_id)
        assert task["status"] == "WAITING_INPUT", task["status"]
        assert task["current_step"] == Step.TOPIC.value, task["current_step"]
        candidates = task["steps"][0]["payload"]["candidates"]
        assert len(candidates) >= 3, candidates
        print(f"[1] 停在选题 GATE，候选 {len(candidates)} 条：{candidates[0]['title']}")

        task = await service.submit(task_id, Step.TOPIC, "approve", payload={"chosen_index": 1})
        assert task["status"] == "WAITING_INPUT" and task["current_step"] == Step.SCRIPT.value
        script = task["steps"][1]["payload"]["script"]
        assert script["title"] == candidates[1]["title"], script["title"]
        print(f"[2] 选定第 2 条后停在脚本 GATE：{script['title']} / {len(script['shots'])} 个镜头")

        first_shot = script["shots"][0]["body"]
        task = await service.submit(
            task_id, Step.SCRIPT, "reject", note="第一步写得更具体一点"
        )
        assert task["status"] == "WAITING_INPUT" and task["current_step"] == Step.SCRIPT.value
        assert task["steps"][1]["feedback"] == "第一步写得更具体一点"
        assert task["steps"][2]["status"] == "PENDING", task["steps"][2]["status"]
        print("[3] 打回脚本后重新生成，素材及后续步骤已重置")

        # 模拟进程重启：新建 service 实例读同一个库继续
        reborn = PipelineService(_settings(tmp))
        resumed = reborn.get_task(task_id)
        assert resumed["current_step"] == Step.SCRIPT.value
        assert len(resumed["gate_log"]) == 2, len(resumed["gate_log"])
        print("[4] 重启后从库里恢复，gate_log 完整保留 2 条")

        edited = json_shots = resumed["steps"][1]["payload"]["script"]["shots"]
        json_shots[0]["headline"] = "先说结论：第一步最容易错"
        task = await reborn.submit(
            task_id,
            Step.SCRIPT,
            "edit",
            payload={"script": {**resumed["steps"][1]["payload"]["script"], "shots": json_shots}},
        )
        assert task["current_step"] == Step.SCRIPT.value
        assert task["steps"][1]["payload"]["script"]["shots"][0]["headline"] == "先说结论：第一步最容易错"
        print(f"[5] 手工改稿保留：[{edited[0]['headline']}] 原句 [{first_shot[:12]}...]")

        task = await reborn.submit(task_id, Step.SCRIPT, "approve")
        assert task["current_step"] == Step.MATERIAL.value, task["current_step"]
        materials = task["steps"][2]["payload"]["shots"]
        assert materials and all(Path(item["source_url"]).is_file() for item in materials)
        print(f"[6] 素材步骤产出 {len(materials)} 张占位图，停在素材 GATE")
    print("\nGATE 链路自测通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
