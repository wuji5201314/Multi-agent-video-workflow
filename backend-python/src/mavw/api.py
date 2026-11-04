"""HTTP 接口层：既服务于本项目管线，也兼容 Java（yq-admin）的成品渲染调用。"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .config import get_settings
from .runtime_settings import RuntimeSettingsStore
from .audio import AudioStudio
from .subtitle import SubtitleStudio
from .images import (
    AnalyzeRequest,
    AnalyzeResponse,
    ImageGenerateRequest,
    ImageGenerateResponse,
    ImageStudio,
)
from .pipeline.service import PipelineService
from .pipeline.steps import Step
from .render.models import RenderRequest, RenderResult

logger = logging.getLogger(__name__)


# 出图模型的中文显示名：flux 写不好中文，qwen/seedream 中文渲染强，供前端下拉展示
_IMAGE_MODEL_LABELS: dict[str, str] = {
    "klein": "FLUX.3 Klein · 快·弱中文",
    "dev": "FLUX.1 Dev · 质量好·弱中文",
    "schnell": "FLUX.1 Schnell · 草图",
    "qwen": "Qwen-Image · 中文强★",
    "qwen-edit": "Qwen-Image-Edit · 改图/改字",
    "seedream": "Seedream 3.0 · 中文强",
    "kolors": "Kolors · 快手可图",
}


def _image_model_choices(presets: Any) -> list[dict[str, Any]]:
    """把 presets.MODELS 转成 {key,label,model,openai_style} 供前端渲染模型选择。"""

    choices: list[dict[str, Any]] = []
    for key in presets.MODELS:
        choices.append(
            {
                "key": key,
                "label": _IMAGE_MODEL_LABELS.get(key, key),
                "model": presets.MODELS[key],
                "openai_style": presets.is_openai_style(key),
            }
        )
    return choices


async def _read_json(request: Request) -> dict[str, Any]:
    """按 UTF-8 读取并解析 JSON 请求体。

    Windows 上 starlette 解析 JSON 时可能回退到系统代码页(cp936)，中文会变成乱码。
    这里显式用 UTF-8 解码原始字节，保证中文正确。
    """
    raw = await request.body()
    if not raw:
        return {}
    text = raw.decode("utf-8-sig")
    data = json.loads(text)
    return data if isinstance(data, dict) else {}


class CreateTaskBody(BaseModel):
    brief: str = Field(min_length=1, max_length=500)
    style: str = ""


class GateBody(BaseModel):
    decision: str = Field(default="approve", description="approve | edit | reject")
    note: str = ""
    payload: dict[str, Any] = Field(default_factory=dict)


class TtsBody(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    voice: str = ""
    rate: float = Field(default=0.0, ge=-50, le=100)
    volume: float = Field(default=0.0, ge=-50, le=100)
    provider: str = ""


class SubtitleBody(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    mode: str = ""
    max_chars: int = Field(default=0, ge=0, le=40)
    duration_ms: int = Field(default=0, ge=0)


# 上传参考图的体积上限，避免一次 POST 把内存打满
_MAX_UPLOAD_BYTES = 20 * 1024 * 1024


def create_app() -> FastAPI:
    base_settings = get_settings()
    runtime = RuntimeSettingsStore(base_settings)
    settings = runtime.apply()  # 套用用户覆盖（模型 / Key / 服务地址）
    service = PipelineService(settings)
    studio = ImageStudio(settings)
    audio = AudioStudio(settings)
    subtitles = SubtitleStudio(settings)
    app = FastAPI(title="Multi-Agent Video Workflow", version="0.2.0")

    def _rebuild(effective) -> None:
        """用户改了配置后，用新的 Settings 重建所有持有者。"""
        nonlocal settings, service, studio, audio, subtitles
        settings = effective
        service = PipelineService(settings)
        studio = ImageStudio(settings)
        audio = AudioStudio(settings)
        subtitles = SubtitleStudio(settings)

    @app.get("/api/health")
    async def health() -> dict[str, Any]:
        # ffmpeg 是否可用直接决定配音/字幕/成片能不能真正跑起来，提前探测一次。
        from .render.ffmpeg_tools import MediaRenderError, ffmpeg_executable

        try:
            has_ffmpeg = bool(ffmpeg_executable())
        except MediaRenderError:
            has_ffmpeg = False
        return {
            "status": "ok",
            "storage_backend": settings.storage_backend,
            "tts_provider": settings.media_render_tts_provider,
            "video": [settings.media_render_width, settings.media_render_height],
            "has_llm_key": bool(settings.api_key),
            "has_image_key": bool(settings.image_api_key),
            "has_ffmpeg": has_ffmpeg,
        }

    # ---------------- 用户配置（模型 / API Key / 服务地址） ----------------

    @app.get("/api/settings")
    async def get_settings_view() -> dict[str, Any]:
        return runtime.view(settings)

    @app.post("/api/settings")
    async def save_settings(request: Request) -> dict[str, Any]:
        payload = await _read_json(request)
        effective = runtime.save(payload)
        _rebuild(effective)
        logger.info("用户配置已更新，覆盖字段: %s", ", ".join(sorted(runtime.load().keys())) or "(无)")
        return {"status": "ok", "settings": runtime.view(settings)}

    @app.post("/api/settings/reset")
    async def reset_settings() -> dict[str, Any]:
        if runtime.path.exists():
            runtime.path.unlink()
        effective = runtime.apply()
        _rebuild(effective)
        return {"status": "ok", "settings": runtime.view(settings)}

    @app.get("/api/settings/options")
    async def settings_options() -> dict[str, Any]:
        """给配置页下拉框用的候选模型清单。"""
        return {
            "llm_models": [
                "deepseek-ai/deepseek-v4.1-flash",
                "deepseek-ai/deepseek-v3.1",
                "moonshotai/kimi-k2-instruct",
                "qwen/qwen3-235b-a22b",
                "openai/gpt-4.1",
            ],
            "image_models": [
                "black-forest-labs/flux-3-image-klein",
                "black-forest-labs/flux.1-dev",
                "black-forest-labs/flux.1-schnell",
                "qwen-image",
                "qwen-image-edit",
                "doubao-seedream-3-0-t2i-250415",
                "Kwai-Kolors/Kolors",
            ],
            "image_providers": ["nvidia", "openai", "placeholder"],
            "image_base_urls": [
                "https://ai.api.nvidia.com/v1/genai",
                "https://integrate.api.nvidia.com/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://ark.cn-beijing.volces.com/api/v3",
                "https://api.openai.com/v1",
            ],
            "image_model_labels": {
                "black-forest-labs/flux-3-image-klein": "FLUX.3 Klein（快·不擅长中文）",
                "black-forest-labs/flux.1-dev": "FLUX.1 Dev（质量好·不擅长中文）",
                "black-forest-labs/flux.1-schnell": "FLUX.1 Schnell（草图）",
                "qwen-image": "Qwen-Image（中文渲染强·推荐封面）",
                "qwen-image-edit": "Qwen-Image-Edit（图像编辑/改字）",
                "doubao-seedream-3-0-t2i-250415": "Seedream（中文强·火山方舟）",
            },
            "tts_providers": ["edge", "openai", "silent"],
            "tts_voices": [
                "zh-CN-YunxiNeural",
                "zh-CN-YunjianNeural",
                "zh-CN-XiaoxiaoNeural",
                "zh-CN-XiaoyiNeural",
            ],
            "llm_base_urls": [
                "https://integrate.api.nvidia.com/v1",
                "https://api.openai.com/v1",
                "https://api.deepseek.com/v1",
            ],
        }

    @app.post("/api/tasks")
    async def create_task(request: Request) -> dict[str, Any]:
        body = CreateTaskBody(**(await _read_json(request)))
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
    async def gate(task_id: int, step: str, request: Request) -> dict[str, Any]:
        body = GateBody(**(await _read_json(request)))
        try:
            return await service.submit(
                task_id, Step(step.upper()), body.decision, body.note, body.payload
            )
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/tasks/{task_id}/materials/{index}")
    async def material_raw(task_id: int, index: int) -> FileResponse:
        """按镜头序号返回素材原图；只允许数据库里记录过的路径，避免任意文件读取。"""

        try:
            task = service.get_task(task_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        row = next((item for item in task["steps"] if item["code"] == "MATERIAL"), None)
        shots = (row or {}).get("payload", {}).get("shots") or []
        if index < 0 or index >= len(shots):
            raise HTTPException(status_code=404, detail=f"没有第 {index} 个镜头")
        path = Path(str(shots[index].get("image_path") or ""))
        if not path.is_file():
            raise HTTPException(status_code=404, detail="素材文件不存在")
        return FileResponse(path)

    @app.get("/api/tasks/{task_id}/artifacts/{artifact_id}")
    async def artifact_raw(task_id: int, artifact_id: int) -> FileResponse:
        """返回成片/封面本体，供前端 video 标签播放。"""

        try:
            task = service.get_task(task_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        row = next((item for item in task["artifacts"] if item["id"] == artifact_id), None)
        if row is None:
            raise HTTPException(status_code=404, detail="产物不存在")
        path = Path(str(row["path"]))
        if not path.is_file():
            raise HTTPException(status_code=404, detail="产物文件已丢失")
        return FileResponse(path)

    @app.post("/ai/content-generation/topics/previews/render", response_model=RenderResult)
    async def render_preview(request: RenderRequest) -> RenderResult:
        """与 yq-admin 的 ContentGenerationPythonClient.renderPreview 对齐的同名路径。"""

        if request.format != "VIDEO":
            raise HTTPException(status_code=400, detail="本项目只处理 format=VIDEO 的成片请求。")
        return await service.render_preview(request)

    # ---------------- 图片工作台 ----------------

    @app.get("/api/images/options")
    async def image_options() -> dict[str, Any]:
        """把预设交给前端渲染，避免两边各写一份配置。"""

        from .images import presets

        return {
            "ratios": [{"key": r.key, "label": r.label, "width": r.width, "height": r.height} for r in presets.RATIOS.values()],
            "styles": [{"key": s.key, "label": s.label} for s in presets.STYLES.values()],
            "qualities": [
                {"key": q.key, "label": q.label, "steps": q.steps, "cfg_scale": q.cfg_scale}
                for q in presets.QUALITIES.values()
            ],
            "models": _image_model_choices(presets),
            "has_image_key": bool(settings.image_api_key),
            "vision_model": settings.vision_model,
        }

    @app.post("/api/images/generate", response_model=ImageGenerateResponse)
    async def images_generate(request: Request) -> ImageGenerateResponse:
        body = ImageGenerateRequest(**(await _read_json(request)))
        try:
            return await studio.generate(body)
        except Exception as exc:  # noqa: BLE001 - 把模型侧错误原样回给前端
            logger.exception("图片生成失败")
            raise HTTPException(status_code=502, detail=str(exc)) from exc

    @app.post("/api/images/upload")
    async def images_upload(request: Request) -> dict[str, str]:
        """按原始二进制收图（不依赖 multipart），返回可被 generate 引用的文件名。"""

        body = await request.body()
        if not body:
            raise HTTPException(status_code=400, detail="空文件")
        if len(body) > _MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=413, detail="图片超过 20MB")
        content_type = (request.headers.get("content-type") or "").lower()
        suffix = ".png"
        if "jpeg" in content_type or "jpg" in content_type:
            suffix = ".jpg"
        elif "webp" in content_type:
            suffix = ".webp"
        name = studio.save_upload(body, suffix)
        return {"file": name, "url": f"/api/images/uploads/{name}"}

    @app.post("/api/images/analyze", response_model=AnalyzeResponse)
    async def images_analyze(request: Request) -> AnalyzeResponse:
        body = AnalyzeRequest(**(await _read_json(request)))
        try:
            return await studio.analyze(body.file, body.instruction)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="参考图不存在") from exc
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=502, detail=str(exc)) from exc

    @app.get("/api/images/history")
    async def images_history(limit: int = 40) -> list[dict[str, Any]]:
        return studio.history(limit)

    @app.delete("/api/images/{image_id}")
    async def images_delete(image_id: str) -> dict[str, bool]:
        return {"deleted": studio.delete(image_id)}

    @app.get("/api/images/files/{file_name}")
    async def images_file(file_name: str) -> FileResponse:
        try:
            return FileResponse(studio.image_path(file_name))
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="图片不存在") from exc

    @app.get("/api/images/uploads/{file_name}")
    async def images_upload_file(file_name: str) -> FileResponse:
        try:
            return FileResponse(studio.upload_path(file_name))
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="参考图不存在") from exc

    # ---------------- 配音工作台 ----------------

    @app.get("/api/audio/voices")
    async def audio_voices() -> dict[str, Any]:
        return {
            "voices": audio.voices(),
            "provider": settings.media_render_tts_provider,
            "max_chars": settings.media_render_tts_max_chars,
        }

    @app.post("/api/audio/tts")
    async def audio_tts(request: Request) -> dict[str, Any]:
        # 手动按 UTF-8 解码请求体，规避 Windows 上 starlette 默认用系统代码页(cp936)解析 JSON 的中文乱码问题。
        body = TtsBody(**(await _read_json(request)))
        try:
            item = await audio.synthesize(
                body.text,
                voice=body.voice,
                rate=body.rate,
                volume=body.volume,
                provider=body.provider,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except Exception as exc:  # noqa: BLE001 - 语音服务侧错误原样回给前端
            logger.exception("配音合成失败")
            raise HTTPException(status_code=502, detail=str(exc)) from exc
        return asdict(item)

    @app.get("/api/audio/history")
    async def audio_history(limit: int = 30) -> list[dict[str, Any]]:
        return audio.history(limit)

    @app.delete("/api/audio/{audio_id}")
    async def audio_delete(audio_id: str) -> dict[str, bool]:
        return {"deleted": audio.delete(audio_id)}

    @app.get("/api/audio/files/{file_name}")
    async def audio_file(file_name: str) -> FileResponse:
        try:
            return FileResponse(audio.path_for(file_name), media_type="audio/wav")
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="音频不存在") from exc

    # ---------------- 字幕工作台 ----------------

    @app.get("/api/subtitle/options")
    async def subtitle_options() -> dict[str, Any]:
        return subtitles.options()

    @app.post("/api/subtitle/build")
    async def subtitle_build(request: Request) -> dict[str, Any]:
        # 同上：显式 UTF-8 解析，避免中文乱码。
        body = SubtitleBody(**(await _read_json(request)))
        try:
            item = subtitles.build(
                body.text,
                mode=body.mode,
                max_chars=body.max_chars,
                duration_ms=body.duration_ms,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except Exception as exc:  # noqa: BLE001
            logger.exception("字幕构建失败")
            raise HTTPException(status_code=500, detail=str(exc)) from exc
        return asdict(item)

    @app.get("/api/subtitle/history")
    async def subtitle_history(limit: int = 30) -> list[dict[str, Any]]:
        return subtitles.history(limit)

    @app.delete("/api/subtitle/{sub_id}")
    async def subtitle_delete(sub_id: str) -> dict[str, bool]:
        return {"deleted": subtitles.delete(sub_id)}

    @app.get("/api/subtitle/files/{file_name}")
    async def subtitle_file(file_name: str) -> FileResponse:
        try:
            return FileResponse(subtitles.path_for(file_name))
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="字幕文件不存在") from exc

    @app.middleware("http")
    async def no_cache_static(request: Request, call_next):
        """前端还在快速迭代，静态文件一律不缓存，避免浏览器拿旧 JS。"""

        response = await call_next(request)
        if not request.url.path.startswith("/api"):
            response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        return response

    # 前端可能由 yq-admin 的 web 容器托管在别的端口，跨源调用这些 /api 时需要放行。
    origins = [item.strip() for item in settings.cors_origins.split(",") if item.strip()]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins or ["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 前端静态资源放在最后挂载，避免 "/" 吞掉上面的 /api 与 /ai 路由。
    # 目录来自 backend-java/frontend（可用 WEB_ROOT 覆盖）；找不到就退化成纯 API 服务。
    web_dir = settings.web_dir()
    if web_dir.is_dir():
        app.mount("/", StaticFiles(directory=web_dir, html=True), name="web")
    else:
        logger.warning("前端目录不存在，仅提供 API：%s", web_dir)

    return app


app = create_app()
