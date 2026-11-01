"""图片工作台的模型调用。

两块能力：
1. FluxClient      —— 文生图（NVIDIA NIM，支持 width/height/steps/cfg_scale/negative_prompt/seed）
2. VisionDescriber —— 看图写词（NVIDIA NIM 的 llama-3.2 vision），用于"上传参考图再重绘"

NIM 的 flux 目前不接受自有图片做 i2i（image 字段只认平台内置的 example_id），
所以"参考图"走的是：图片 → 视觉模型描述 → 合并进 prompt → 重新出图。
"""

from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
from dataclasses import dataclass, field
from pathlib import Path

import httpx
from PIL import Image

from ..config import Settings

logger = logging.getLogger(__name__)

# 视觉模型只用来写描述，缩到 512 足够，避免请求体过大
_VISION_MAX_SIDE = 512


@dataclass
class FluxRequest:
    prompt: str
    negative_prompt: str = ""
    width: int = 1024
    height: int = 1536
    steps: int = 20
    cfg_scale: float = 3.5
    seed: int = 0
    model: str = "black-forest-labs/flux-3-image-klein"


@dataclass
class FluxResult:
    image: Image.Image
    seed: int
    elapsed_ms: int


class FluxError(RuntimeError):
    """出图失败（重试耗尽）。"""


class FluxClient:
    """调用 NIM 的 flux 系列模型。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._base = settings.image_api_base.rstrip("/")
        self._headers = {
            "Authorization": f"Bearer {settings.image_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    async def generate(self, request: FluxRequest) -> FluxResult:
        if not self._settings.image_api_key:
            raise FluxError("没有配置 IMAGE_API_KEY，无法出图。")

        # NIM 只认白名单字段，多传一个就 422，所以这里严格按已实测支持的键组装
        payload: dict[str, object] = {
            "prompt": request.prompt,
            "width": request.width,
            "height": request.height,
            "steps": request.steps,
            "cfg_scale": request.cfg_scale,
        }
        if request.negative_prompt:
            payload["negative_prompt"] = request.negative_prompt
        if request.seed:
            payload["seed"] = request.seed

        url = f"{self._base}/{request.model}"
        attempts = max(1, self._settings.image_retries)
        last_error = ""
        started = asyncio.get_event_loop().time()
        for attempt in range(1, attempts + 1):
            try:
                async with httpx.AsyncClient(
                    timeout=self._settings.image_timeout_seconds
                ) as client:
                    response = await client.post(url, json=payload, headers=self._headers)
                if response.status_code == 200:
                    data = json.loads(response.text)
                    artifacts = data.get("artifacts") or []
                    if not artifacts or not artifacts[0].get("base64"):
                        raise RuntimeError("返回体里没有图片")
                    raw = base64.b64decode(artifacts[0]["base64"])
                    image = Image.open(io.BytesIO(raw)).convert("RGB")
                    elapsed = int((asyncio.get_event_loop().time() - started) * 1000)
                    seed = int(data.get("seed") or artifacts[0].get("seed") or request.seed or 0)
                    return FluxResult(image=image, seed=seed, elapsed_ms=elapsed)
                last_error = f"HTTP {response.status_code} {response.text[:180]}"
            except Exception as exc:  # noqa: BLE001 - 网络抖动与解码错误都重试
                last_error = f"{type(exc).__name__}: {exc}"
            logger.warning("出图第 %s/%s 次失败：%s", attempt, attempts, last_error)
            await asyncio.sleep(min(2 * attempt, 6))
        raise FluxError(last_error or "出图失败")


class VisionDescriber:
    """用视觉模型把图片读成一段可以喂给文生图的 prompt。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._url = f"{settings.base_url.rstrip('/')}/chat/completions"
        self._model = settings.vision_model
        self._key = settings.api_key

    async def describe(self, image_path: Path, instruction: str = "") -> tuple[str, str]:
        """返回 (整理后的 prompt, 模型原始输出)。"""

        if not self._key:
            raise FluxError("没有配置 API_KEY，无法识别参考图。")

        data_url = _to_data_url(image_path)
        ask = instruction.strip() or (
            "请把这张图转写成一段可以直接用于文生图的提示词：只描述主体、场景、构图、"
            "光线、色调与风格，不要出现文字、不要评论、不要分点，控制在 80 字以内。"
        )
        body = {
            "model": self._model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": ask},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                }
            ],
            "max_tokens": 400,
            "temperature": 0.4,
        }
        headers = {"Authorization": f"Bearer {self._key}", "Content-Type": "application/json"}
        async with httpx.AsyncClient(timeout=self._settings.llm_timeout_seconds) as client:
            response = await client.post(self._url, json=body, headers=headers)
        if response.status_code != 200:
            raise FluxError(f"视觉模型返回 {response.status_code}：{response.text[:160]}")
        message = ((response.json().get("choices") or [{}])[0].get("message") or {})
        raw = str(message.get("content") or message.get("reasoning_content") or "").strip()
        return _clean_prompt(raw), raw


def _to_data_url(path: Path) -> str:
    image = Image.open(path).convert("RGB")
    image.thumbnail((_VISION_MAX_SIDE, _VISION_MAX_SIDE))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=85)
    encoded = base64.b64encode(buffer.getvalue()).decode()
    return f"data:image/jpeg;base64,{encoded}"


def _clean_prompt(text: str) -> str:
    """去掉模型爱加的引号、前缀和换行。"""

    cleaned = text.strip().strip('"').strip("「」“”")
    for prefix in ("提示词：", "提示词:", "Prompt:", "prompt:"):
        if cleaned.startswith(prefix):
            cleaned = cleaned[len(prefix) :].strip()
    return " ".join(cleaned.split())
