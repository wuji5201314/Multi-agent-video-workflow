"""成品渲染的入参/出参模型。

字段与 Java 侧 ai_content_preview_task 的报文完全对齐：
Java 用 format=VIDEO 请求，得到 cover_object_key / video_object_key / duration_ms。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

IMAGE_ASSET = "GENERATED_IMAGE"
VIDEO_ASSET = "VIDEO_CLIP"


class RenderItem(BaseModel):
    """一个镜头的素材与口播文案。"""

    need_id: str = Field(default="", description="素材需求 ID，Java 侧用于回填")
    asset_type: str = Field(default=IMAGE_ASSET, description="GENERATED_IMAGE / VIDEO_CLIP")
    source_url: str = Field(description="素材地址：https、file:// 或本地绝对路径")
    headline: str = Field(default="", max_length=40)
    body: str = Field(default="", max_length=200)
    duration_ms: int | None = Field(default=None, ge=1000, le=30000)

    @field_validator("source_url")
    @classmethod
    def _non_empty(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("source_url 不能为空")
        return value.strip()


class RenderRequest(BaseModel):
    """渲染请求，来自 Java 或管线内部。"""

    preview_task_id: int | str = Field(default=0)
    format: Literal["VIDEO", "IMAGE_CAROUSEL"] = "VIDEO"
    cover_title: str = Field(default="", max_length=40)
    publish_caption: str = Field(default="", max_length=800)
    tags: list[str] = Field(default_factory=list)
    items: list[RenderItem] = Field(min_length=1)


class RenderResultItem(BaseModel):
    sequence_no: int
    need_id: str
    object_key: str
    file_size: int
    width: int
    height: int


class RenderResult(BaseModel):
    """渲染结果；key 集合必须与 Java saveResult 读取的一致。"""

    format: str
    cover_object_key: str
    cover_file_size: int
    video_object_key: str | None = None
    video_file_size: int | None = None
    duration_ms: int | None = None
    items: list[RenderResultItem] = Field(default_factory=list)
