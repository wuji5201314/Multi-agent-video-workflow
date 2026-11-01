"""图片工作台的请求/响应模型。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class ImageGenerateRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=2000)
    negative_prompt: str = Field(default="", max_length=500)
    style: str = "none"
    ratio: str = "9:16"
    width: int | None = None
    height: int | None = None
    quality: str = "standard"
    count: int = Field(default=1, ge=1, le=4)
    seed: int = Field(default=0, ge=0)
    model: str = "klein"
    # 参考图：先调 /api/images/upload 拿到文件名，再带过来做"看图写词"
    reference: str = ""
    reference_strength: float = Field(default=0.6, ge=0.0, le=1.0)
    note: str = ""


class ImageItem(BaseModel):
    id: str
    file: str
    url: str
    width: int
    height: int
    seed: int
    prompt: str
    style: str
    quality: str
    model: str
    reference: str = ""
    created_at: str
    elapsed_ms: int


class ImageGenerateResponse(BaseModel):
    job_id: str
    items: list[ImageItem]
    errors: list[str] = Field(default_factory=list)
    prompt_used: str
    meta: dict[str, Any] = Field(default_factory=dict)


class AnalyzeRequest(BaseModel):
    file: str = Field(min_length=1)
    instruction: str = ""


class AnalyzeResponse(BaseModel):
    file: str
    prompt: str
    raw: str
