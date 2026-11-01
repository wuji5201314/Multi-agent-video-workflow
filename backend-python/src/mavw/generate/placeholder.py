"""没有出图服务时的兜底：Pillow 排版卡。

保证「没配 key 也能出片」，也让测试不依赖外网。
"""

from __future__ import annotations

from pathlib import Path

from ..render.card import render_material_card
from .base import MaterialSpec


class PlaceholderProvider:
    """用渐变底 + 大字 + 标签条拼一张竖屏卡。"""

    name = "placeholder"

    async def generate(self, spec: MaterialSpec, output: Path) -> Path:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(
            render_material_card(spec.headline, spec.body, spec.index + 1, list(spec.tags))
        )
        return output
