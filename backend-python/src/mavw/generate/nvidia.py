"""NVIDIA NIM 出图：black-forest-labs/flux-3-image-klein。

接口形态（实测）：
    POST https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux-3-image-klein
    { "prompt": "...", "aspect_ratio": "1:2", "seed": 123 }
    -> { "artifacts": [ { "base64": "<jpeg>" } ] }

注意两点：
- body 只接受 prompt / aspect_ratio / seed 等少数键，多传会 422（extra_forbidden）。
- aspect_ratio 的合法值里没有 9:16，1:2 最接近竖屏，出图后统一裁成目标画幅。
"""

from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
from pathlib import Path

import httpx
from PIL import Image

from ..config import Settings
from .base import MaterialSpec

logger = logging.getLogger(__name__)


def cover_resize(image: Image.Image, width: int, height: int) -> Image.Image:
    """等比放大到能盖住目标框，再居中裁剪，避免画面被拉伸变形。"""

    src_ratio = image.width / image.height
    dst_ratio = width / height
    if src_ratio > dst_ratio:
        new_h = height
        new_w = max(width, int(round(height * src_ratio)))
    else:
        new_w = width
        new_h = max(height, int(round(width / src_ratio)))
    resized = image.resize((new_w, new_h), Image.LANCZOS)
    left = max(0, (new_w - width) // 2)
    top = max(0, (new_h - height) // 2)
    return resized.crop((left, top, left + width, top + height))


class NvidiaFluxProvider:
    """调用 NVIDIA NIM 的 flux 出图。"""

    name = "nvidia"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._url = f"{settings.image_api_base.rstrip('/')}/{settings.image_model}"
        self._headers = {
            "Authorization": f"Bearer {settings.image_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    async def generate(self, spec: MaterialSpec, output: Path) -> Path:
        payload: dict[str, object] = {
            "prompt": spec.prompt,
            "aspect_ratio": self._settings.image_aspect_ratio,
        }
        if spec.seed:
            payload["seed"] = spec.seed
        attempts = max(1, self._settings.image_retries)
        last_error = ""
        for attempt in range(1, attempts + 1):
            try:
                async with httpx.AsyncClient(
                    timeout=self._settings.image_timeout_seconds
                ) as client:
                    response = await client.post(self._url, json=payload, headers=self._headers)
                if response.status_code == 200:
                    image = self._decode(response.text)
                    output.parent.mkdir(parents=True, exist_ok=True)
                    image.save(output, format="PNG")
                    logger.info("镜头 %s 出图完成：%s", spec.index + 1, output.name)
                    return output
                last_error = f"HTTP {response.status_code} {response.text[:160]}"
            except Exception as exc:  # noqa: BLE001 - 网络与解码错误都重试
                last_error = f"{type(exc).__name__}: {exc}"
            logger.warning("镜头 %s 第 %s/%s 次出图失败：%s", spec.index + 1, attempt, attempts, last_error)
            await asyncio.sleep(min(2 * attempt, 6))
        raise RuntimeError(f"镜头 {spec.index + 1} 出图失败：{last_error}")

    def _decode(self, text: str) -> Image.Image:
        data = json.loads(text)
        artifacts = data.get("artifacts") or []
        if not artifacts or not artifacts[0].get("base64"):
            raise RuntimeError("返回体里没有图片数据")
        raw = base64.b64decode(artifacts[0]["base64"])
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        return cover_resize(image, self._settings.media_render_width, self._settings.media_render_height)
