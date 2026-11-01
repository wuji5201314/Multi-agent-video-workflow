"""素材生成 provider 的统一接口。

刻意只约定三件事：一个镜头要什么（MaterialSpec）、给一个输出路径、返回这个路径。
出图是模型还是排版卡由实现决定，上层（pipeline/service.py）不需要知道。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class MaterialSpec:
    """一个镜头要生成的素材描述。"""

    index: int
    prompt: str
    headline: str = ""
    body: str = ""
    tags: tuple[str, ...] = ()
    width: int = 1080
    height: int = 1920
    seed: int = 0


class ImageProvider(Protocol):
    """出图实现需要满足的最小接口。"""

    name: str

    async def generate(self, spec: MaterialSpec, output: Path) -> Path:
        """把素材写到 output 并返回它。"""
        ...
