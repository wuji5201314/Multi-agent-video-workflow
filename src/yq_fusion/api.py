"""HTTP 接口层：既服务于本项目管线，也兼容 Java（yq-admin）的成品渲染调用。"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .config import get_settings
from .pipeline.service import PipelineService
from .pipeline.steps import Step
from .render.models import RenderRequest, RenderResult

logger = logging.getLogger(__name__)


class CreateTaskBody(BaseModel):
    brief: str = Field(min_length=1, max_length=500)
    style: str = ""


class GateBody(BaseModel):
    decision: str = Field(default="approve", description="approve | edit | reject")
    note: str = ""
    payload: dict[str, Any] = Field(default_factory=dict)


def create_app() -> FastAPI:
    settings = get_settings()
    service = PipelineService(settings)
    app = FastAPI(title="YanQue Media Fusion", version="0.1.0")

    @app.get("/api/health")
    async def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "storage_backend": settings.storage_backend,
            "tts_provider": settings.media_render_tts_provider,
            "video": [settings.media_render_width, settings.media_render_height],
            "has_llm_key": bool(settings.api_key),
        }

    @app.post("/api/tasks")
    async def create_task(body: CreateTaskBody) -> dict[str, Any]:
        task = service.create_task(body.brief, body.style)
        return await service.advance(task["task_id"], auto=False)

    @app.get("/api/tasks")
    async def list_tasks(limit: int = 20) -> list[dict[str, Any]]:
        return service.list_tasks(limit)

    @app.get("/api/tasks/{task_id}")
    async def get_task(task_id: int) -> dict[str, Any]:
        try:
            return service.get_task(task_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/api/tasks/{task_id}/advance")
    async def advance(task_id: int, auto: bool = False) -> dict[str, Any]:
        try:
            return await service.advance(task_id, auto=auto)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/api/tasks/{task_id}/gates/{step}")
    async def gate(task_id: int, step: str, body: GateBody) -> dict[str, Any]:
        try:
            return await service.submit(
                task_id, Step(step.upper()), body.decision, body.note, body.payload
            )
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/ai/content-generation/topics/previews/render", response_model=RenderResult)
    async def render_preview(request: RenderRequest) -> RenderResult:
        """与 yq-admin 的 ContentGenerationPythonClient.renderPreview 对齐的同名路径。"""

        if request.format != "VIDEO":
            raise HTTPException(status_code=400, detail="本项目只处理 format=VIDEO 的成片请求。")
        return await service.render_preview(request)

    return app


app = create_app()
