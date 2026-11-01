"""图片工作台服务：把参数翻译成模型调用，负责并发批量、落盘与历史。"""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime
from pathlib import Path

from ..config import Settings
from . import presets
from .models import AnalyzeResponse, ImageGenerateRequest, ImageGenerateResponse, ImageItem
from .providers import FluxClient, FluxError, FluxRequest, VisionDescriber
from .store import ImageStore

logger = logging.getLogger(__name__)


class ImageStudio:
    """图片生成工作台。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._flux = FluxClient(settings)
        self._vision = VisionDescriber(settings)
        self._store = ImageStore(settings)
        self._root = settings.output_dir() / "studio"
        (self._root / "images").mkdir(parents=True, exist_ok=True)
        (self._root / "uploads").mkdir(parents=True, exist_ok=True)

    # ---------------- 上传与读取 ----------------

    def save_upload(self, data: bytes, suffix: str) -> str:
        """保存用户上传的参考图，返回文件名（不含目录）。"""

        safe = suffix.lower() if suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"} else ".png"
        name = f"{uuid.uuid4().hex}{safe}"
        path = self._root / "uploads" / name
        path.write_bytes(data)
        return name

    def upload_path(self, name: str) -> Path:
        path = (self._root / "uploads" / name).resolve()
        if not path.is_file():
            raise FileNotFoundError(name)
        return path

    def image_path(self, file_name: str) -> Path:
        """前端只拿文件名，这里限制在 studio/images 目录内，避免路径穿越。"""

        path = (self._root / "images" / file_name).resolve()
        if not path.is_file():
            raise FileNotFoundError(file_name)
        return path

    # ---------------- 生成 ----------------

    async def generate(self, request: ImageGenerateRequest) -> ImageGenerateResponse:
        ratio = presets.RATIOS.get(request.ratio) or presets.RATIOS[presets.DEFAULT_RATIO]
        width, height = presets.resolve_size(request.ratio, request.width, request.height)
        quality = presets.QUALITIES.get(request.quality) or presets.QUALITIES[presets.DEFAULT_QUALITY]
        model = presets.MODELS.get(request.model) or presets.MODELS[presets.DEFAULT_MODEL]

        prompt = presets.build_prompt(request.prompt, request.style)
        reference_note = ""
        if request.reference:
            # 参考图先读成描述，再和用户输入拼在一起：这是当前唯一可用的"图生图"
            try:
                described, _raw = await self._vision.describe(self.upload_path(request.reference))
                reference_note = described
                prompt = f"{prompt}。参考画面：{described}"
            except Exception as exc:  # noqa: BLE001 - 识别失败不该挡住出图
                logger.warning("参考图识别失败，按纯文本出图：%s", exc)

        job_id = datetime.now().strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]
        tasks = [
            self._generate_one(
                job_id=job_id,
                index=index,
                prompt=prompt,
                negative_prompt=request.negative_prompt,
                width=width,
                height=height,
                steps=quality.steps,
                cfg_scale=quality.cfg_scale,
                seed=request.seed + index if request.seed else 0,
                model=model,
                style=request.style,
                quality=request.quality,
                reference=request.reference,
                note=request.note,
            )
            for index in range(request.count)
        ]
        # 并发跑，但 NIM 对同 key 有速率限制，用信号量压到 2 路
        semaphore = asyncio.Semaphore(2)

        async def guarded(coro):
            async with semaphore:
                return await coro

        results = await asyncio.gather(*[guarded(item) for item in tasks], return_exceptions=True)

        items: list[ImageItem] = []
        errors: list[str] = []
        for result in results:
            if isinstance(result, Exception):
                errors.append(str(result))
            else:
                items.append(result)

        return ImageGenerateResponse(
            job_id=job_id,
            items=items,
            errors=errors,
            prompt_used=prompt,
            meta={
                "width": width,
                "height": height,
                "steps": quality.steps,
                "cfg_scale": quality.cfg_scale,
                "model": model,
                "ratio": ratio.key,
                "reference_note": reference_note,
            },
        )

    async def _generate_one(
        self,
        *,
        job_id: str,
        index: int,
        prompt: str,
        negative_prompt: str,
        width: int,
        height: int,
        steps: int,
        cfg_scale: float,
        seed: int,
        model: str,
        style: str,
        quality: str,
        reference: str,
        note: str,
    ) -> ImageItem:
        result = await self._flux.generate(
            FluxRequest(
                prompt=prompt,
                negative_prompt=negative_prompt,
                width=width,
                height=height,
                steps=steps,
                cfg_scale=cfg_scale,
                seed=seed,
                model=model,
            )
        )
        image_id = f"{job_id}-{index}"
        file_name = f"{image_id}.png"
        path = self._root / "images" / file_name
        result.image.save(path, format="PNG")
        created_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        item = {
            "id": image_id,
            "file": file_name,
            "width": result.image.width,
            "height": result.image.height,
            "seed": result.seed,
            "prompt": prompt,
            "negative_prompt": negative_prompt,
            "style": style,
            "quality": quality,
            "model": model.split("/")[-1],
            "reference": reference,
            "note": note,
            "elapsed_ms": result.elapsed_ms,
            "created_at": created_at,
        }
        self._store.add(item)
        return ImageItem(**item, url=f"/api/images/files/{file_name}")

    # ---------------- 识别与历史 ----------------

    async def analyze(self, file_name: str, instruction: str = "") -> AnalyzeResponse:
        prompt, raw = await self._vision.describe(self.upload_path(file_name), instruction)
        return AnalyzeResponse(file=file_name, prompt=prompt, raw=raw)

    def history(self, limit: int = 40) -> list[dict]:
        rows = self._store.list(limit)
        for row in rows:
            row["url"] = f"/api/images/files/{row['file']}"
        return rows

    def delete(self, image_id: str) -> bool:
        row = self._store.get(image_id)
        if not row:
            return False
        try:
            (self._root / "images" / row["file"]).unlink(missing_ok=True)
        except OSError:
            pass
        return self._store.delete(image_id)
