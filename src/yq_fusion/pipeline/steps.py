"""管线步骤定义。

与 MoneyPrinterTurbo 的差别就在这里：它是 selected → 一口气跑完，
本项目每一步产出后都停在 GATE 上等人工确认，确认/修改/打回都由 submit 驱动。
"""

from __future__ import annotations

from enum import Enum


class Step(str, Enum):
    TOPIC = "TOPIC"
    SCRIPT = "SCRIPT"
    MATERIAL = "MATERIAL"
    RENDER = "RENDER"
    PUBLISH = "PUBLISH"


STEP_ORDER: tuple[Step, ...] = (
    Step.TOPIC,
    Step.SCRIPT,
    Step.MATERIAL,
    Step.RENDER,
    Step.PUBLISH,
)

# 每个产出步骤之后都要人工确认一次，这就是"可中断 + 可反馈"的落点。
GATES: frozenset[Step] = frozenset({Step.TOPIC, Step.SCRIPT, Step.MATERIAL, Step.RENDER})

STEP_TITLES: dict[Step, str] = {
    Step.TOPIC: "选题生成",
    Step.SCRIPT: "口播脚本",
    Step.MATERIAL: "素材匹配",
    Step.RENDER: "成品渲染",
    Step.PUBLISH: "发布投放",
}


class StepStatus(str, Enum):
    PENDING = "PENDING"
    WAITING = "WAITING_INPUT"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class TaskStatus(str, Enum):
    WAITING_INPUT = "WAITING_INPUT"
    RUNNING = "RUNNING"
    DONE = "DONE"
    FAILED = "FAILED"
