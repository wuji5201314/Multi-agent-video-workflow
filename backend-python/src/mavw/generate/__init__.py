"""素材生成：按配置选 provider。"""

from __future__ import annotations

from ..config import Settings
from .base import ImageProvider, MaterialSpec
from .nvidia import NvidiaFluxProvider
from .placeholder import PlaceholderProvider


def build_provider(settings: Settings) -> ImageProvider:
    """有 key 且显式选了 nvidia 才走模型，否则退回排版卡。"""

    if settings.image_provider == "nvidia" and settings.image_api_key:
        return NvidiaFluxProvider(settings)
    return PlaceholderProvider()


__all__ = ["ImageProvider", "MaterialSpec", "build_provider"]
