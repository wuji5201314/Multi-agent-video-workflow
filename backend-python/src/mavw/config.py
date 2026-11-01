"""集中配置：全部走环境变量，默认值保证离线也能一条命令出片。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[2]

# 允许把配置写进 backend-python/.env（该文件在 .gitignore 里），进程启动时自动读取。
load_dotenv(PROJECT_ROOT / ".env")


def _str(name: str, default: str) -> str:
    return (os.getenv(name) or default).strip()


def _int(name: str, default: int) -> int:
    raw = (os.getenv(name) or "").strip()
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    raw = (os.getenv(name) or "").strip()
    try:
        return float(raw) if raw else default
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    raw = (os.getenv(name) or "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on", "y"}


@dataclass(frozen=True)
class Settings:
    """运行期配置快照。字段命名与 YanQue-AI 的 media_render_* / yanque_* 保持一致。"""

    # ---------- 成片画面 ----------
    media_render_width: int = 1080
    media_render_height: int = 1920
    media_render_fps: int = 30
    media_render_crf: int = 22
    media_render_preset: str = "veryfast"
    media_render_timeout_seconds: int = 900
    media_render_clip_min_ms: int = 2600
    media_render_clip_max_ms: int = 15000

    # ---------- 字幕 ----------
    media_render_subtitle_enabled: bool = True
    media_render_subtitle_mode: str = "sentence"  # sentence | word
    media_render_subtitle_max_chars: int = 14
    media_render_subtitle_font: str = "Microsoft YaHei"
    media_render_subtitle_font_size: int = 60
    media_render_subtitle_margin_v: int = 420
    media_render_subtitle_pop: bool = False

    # ---------- 数字人（L2 画中画） ----------
    # 关闭时不产生任何额外算力开销，整条链路与原来完全一致。
    digital_human_enabled: bool = False
    # placeholder=离线占位主播（零算力）；wav2lip=本地轻量对口型；api=远程推理
    digital_human_provider: str = "placeholder"
    digital_human_pip_width: int = 320
    digital_human_pip_height: int = 400
    digital_human_pip_position: str = "top-right"
    digital_human_pip_margin: int = 48
    digital_human_pip_radius: int = 26
    digital_human_pip_border: int = 4
    # 参考形象：留空则自动取第一镜素材当形象，零配置也能跑通
    digital_human_reference_image: str = ""
    digital_human_cache_dir: str = ""
    digital_human_timeout_seconds: int = 1800
    # wav2lip
    digital_human_wav2lip_dir: str = ""
    digital_human_wav2lip_checkpoint: str = ""
    digital_human_wav2lip_static: bool = False
    digital_human_python: str = ""
    # api
    digital_human_api_url: str = ""
    digital_human_api_key: str = ""
    digital_human_api_image_field: str = "image"
    digital_human_api_audio_field: str = "audio"

    # ---------- BGM ----------
    media_render_bgm_enabled: bool = False
    media_render_bgm_dir: str = ""
    media_render_bgm_volume: float = 0.2

    # ---------- 配音 ----------
    media_render_tts_provider: str = "edge"  # edge | openai | silent
    media_render_tts_voice: str = "zh-CN-YunxiNeural"
    media_render_tts_rate: str = "+8%"
    media_render_tts_volume: str = "+0%"
    media_render_tts_base_url: str = ""
    media_render_tts_api_key: str = ""
    media_render_tts_model: str = ""
    media_render_tts_speed: float = 1.0
    media_render_tts_max_chars: int = 240
    media_render_tts_timeout_seconds: int = 120
    media_render_tts_fallback_silent: bool = True

    # ---------- 存储 ----------
    storage_backend: str = "local"  # local | presign
    local_storage_root: str = ""
    presign_endpoint: str = "/yq-admin/internal/ai/content-preview-assets/presign"
    yanque_admin_base_url: str = "http://127.0.0.1:8080"
    yanque_internal_api_key: str = ""

    # ---------- 大模型（缺 key 时自动退回内置模板，保证离线可跑） ----------
    base_url: str = "https://integrate.api.nvidia.com/v1"
    api_key: str = ""
    llm_model: str = "deepseek-ai/deepseek-v4.1-flash"
    llm_timeout_seconds: int = 180
    # 推理型模型的思考过程也吃 token，给足才不会出现 content 为空。
    llm_max_tokens: int = 3000
    # NIM 上的 deepseek 开 json_object 会把结果写进 reasoning_content，默认关掉。
    llm_json_mode: bool = False

    # ---------- 管线 ----------
    pipeline_db_path: str = ""
    pipeline_output_dir: str = ""
    pipeline_auto_approve: bool = False
    pipeline_topic_candidates: int = 3
    pipeline_shot_count: int = 4
    pipeline_material_dir: str = ""

    # ---------- 素材生成（模型出图） ----------
    # provider: nvidia=调用 NVIDIA NIM 的 flux 出图；placeholder=Pillow 排版卡兜底
    image_provider: str = "placeholder"
    image_api_base: str = "https://ai.api.nvidia.com/v1/genai"
    image_api_key: str = ""
    image_model: str = "black-forest-labs/flux-3-image-klein"
    # flux 支持的宽高比里没有 9:16，1:2 最接近（生成后统一裁成 9:16）
    image_aspect_ratio: str = "1:2"
    image_timeout_seconds: int = 180
    image_retries: int = 3
    # 同一 prompt 固定 seed 便于复现；0 表示每次随机
    image_seed: int = 0
    # 看图写词用的视觉模型（上传参考图后先描述再重绘）
    vision_model: str = "meta/llama-3.2-90b-vision-instruct"

    # ---------- 前端 ----------
    # 前端源码放在 backend-java/frontend，将来跟 yq-admin 的 web 端一起部署；
    # Python 这边只是本地开发时的静态服务器，留空则自动指向该目录。
    web_root: str = ""
    cors_origins: str = "*"

    def images_dir(self) -> Path:
        """模型出图的落盘目录，跟随 output 一起被忽略。"""

        return self.output_dir() / "assets" / "materials"

    def web_dir(self) -> Path:
        """前端静态资源目录，默认取仓库下的 backend-java/frontend。"""

        raw = self.web_root
        if raw:
            path = Path(raw)
            return path if path.is_absolute() else PROJECT_ROOT / path
        return PROJECT_ROOT.parent / "backend-java" / "frontend"

    def storage_root(self) -> Path:
        raw = self.local_storage_root or str(PROJECT_ROOT / "output" / "assets")
        path = Path(raw)
        return path if path.is_absolute() else PROJECT_ROOT / path

    def output_dir(self) -> Path:
        raw = self.pipeline_output_dir or str(PROJECT_ROOT / "output")
        path = Path(raw)
        return path if path.is_absolute() else PROJECT_ROOT / path

    def db_path(self) -> Path:
        raw = self.pipeline_db_path or str(PROJECT_ROOT / "output" / "pipeline.db")
        path = Path(raw)
        return path if path.is_absolute() else PROJECT_ROOT / path

    def digital_human_cache(self) -> Path:
        """数字人片段缓存目录，跟随 output 一起被 gitignore。"""

        if self.digital_human_cache_dir:
            path = Path(self.digital_human_cache_dir)
            return path if path.is_absolute() else PROJECT_ROOT / path
        return self.output_dir() / "digital-human-cache"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings(
        media_render_width=_int("MEDIA_RENDER_WIDTH", 1080),
        media_render_height=_int("MEDIA_RENDER_HEIGHT", 1920),
        media_render_fps=_int("MEDIA_RENDER_FPS", 30),
        media_render_crf=_int("MEDIA_RENDER_CRF", 22),
        media_render_preset=_str("MEDIA_RENDER_PRESET", "veryfast"),
        media_render_timeout_seconds=_int("MEDIA_RENDER_TIMEOUT_SECONDS", 900),
        media_render_clip_min_ms=_int("MEDIA_RENDER_CLIP_MIN_MS", 2600),
        media_render_clip_max_ms=_int("MEDIA_RENDER_CLIP_MAX_MS", 15000),
        media_render_subtitle_enabled=_bool("MEDIA_RENDER_SUBTITLE_ENABLED", True),
        media_render_subtitle_mode=_str("MEDIA_RENDER_SUBTITLE_MODE", "sentence"),
        media_render_subtitle_max_chars=_int("MEDIA_RENDER_SUBTITLE_MAX_CHARS", 14),
        media_render_subtitle_font=_str("MEDIA_RENDER_SUBTITLE_FONT", "Microsoft YaHei"),
        media_render_subtitle_font_size=_int("MEDIA_RENDER_SUBTITLE_FONT_SIZE", 60),
        media_render_subtitle_margin_v=_int("MEDIA_RENDER_SUBTITLE_MARGIN_V", 420),
        media_render_subtitle_pop=_bool("MEDIA_RENDER_SUBTITLE_POP", False),
        media_render_bgm_enabled=_bool("MEDIA_RENDER_BGM_ENABLED", False),
        media_render_bgm_dir=_str("MEDIA_RENDER_BGM_DIR", ""),
        media_render_bgm_volume=_float("MEDIA_RENDER_BGM_VOLUME", 0.2),
        digital_human_enabled=_bool("DIGITAL_HUMAN_ENABLED", False),
        digital_human_provider=_str("DIGITAL_HUMAN_PROVIDER", "placeholder"),
        digital_human_pip_width=_int("DIGITAL_HUMAN_PIP_WIDTH", 320),
        digital_human_pip_height=_int("DIGITAL_HUMAN_PIP_HEIGHT", 400),
        digital_human_pip_position=_str("DIGITAL_HUMAN_PIP_POSITION", "top-right"),
        digital_human_pip_margin=_int("DIGITAL_HUMAN_PIP_MARGIN", 48),
        digital_human_pip_radius=_int("DIGITAL_HUMAN_PIP_RADIUS", 26),
        digital_human_pip_border=_int("DIGITAL_HUMAN_PIP_BORDER", 4),
        digital_human_reference_image=_str("DIGITAL_HUMAN_REFERENCE_IMAGE", ""),
        digital_human_cache_dir=_str("DIGITAL_HUMAN_CACHE_DIR", ""),
        digital_human_timeout_seconds=_int("DIGITAL_HUMAN_TIMEOUT_SECONDS", 1800),
        digital_human_wav2lip_dir=_str("DIGITAL_HUMAN_WAV2LIP_DIR", ""),
        digital_human_wav2lip_checkpoint=_str("DIGITAL_HUMAN_WAV2LIP_CHECKPOINT", ""),
        digital_human_wav2lip_static=_bool("DIGITAL_HUMAN_WAV2LIP_STATIC", False),
        digital_human_python=_str("DIGITAL_HUMAN_PYTHON", ""),
        digital_human_api_url=_str("DIGITAL_HUMAN_API_URL", ""),
        digital_human_api_key=_str("DIGITAL_HUMAN_API_KEY", ""),
        digital_human_api_image_field=_str("DIGITAL_HUMAN_API_IMAGE_FIELD", "image"),
        digital_human_api_audio_field=_str("DIGITAL_HUMAN_API_AUDIO_FIELD", "audio"),
        media_render_tts_provider=_str("MEDIA_RENDER_TTS_PROVIDER", "edge"),
        media_render_tts_voice=_str("MEDIA_RENDER_TTS_VOICE", "zh-CN-YunxiNeural"),
        media_render_tts_rate=_str("MEDIA_RENDER_TTS_RATE", "+8%"),
        media_render_tts_volume=_str("MEDIA_RENDER_TTS_VOLUME", "+0%"),
        media_render_tts_base_url=_str("MEDIA_RENDER_TTS_BASE_URL", ""),
        media_render_tts_api_key=_str("MEDIA_RENDER_TTS_API_KEY", ""),
        media_render_tts_model=_str("MEDIA_RENDER_TTS_MODEL", ""),
        media_render_tts_speed=_float("MEDIA_RENDER_TTS_SPEED", 1.0),
        media_render_tts_max_chars=_int("MEDIA_RENDER_TTS_MAX_CHARS", 240),
        media_render_tts_timeout_seconds=_int("MEDIA_RENDER_TTS_TIMEOUT_SECONDS", 120),
        media_render_tts_fallback_silent=_bool("MEDIA_RENDER_TTS_FALLBACK_SILENT", True),
        storage_backend=_str("STORAGE_BACKEND", "local"),
        local_storage_root=_str("LOCAL_STORAGE_ROOT", ""),
        presign_endpoint=_str(
            "PRESIGN_ENDPOINT", "/yq-admin/internal/ai/content-preview-assets/presign"
        ),
        yanque_admin_base_url=_str("YANQUE_ADMIN_BASE_URL", "http://127.0.0.1:8080"),
        yanque_internal_api_key=_str("YANQUE_INTERNAL_API_KEY", ""),
        base_url=_str("BASE_URL", "https://integrate.api.nvidia.com/v1"),
        api_key=_str("API_KEY", ""),
        llm_model=_str("LLM_MODEL", "deepseek-ai/deepseek-v4.1-flash"),
        llm_timeout_seconds=_int("LLM_TIMEOUT_SECONDS", 180),
        llm_max_tokens=_int("LLM_MAX_TOKENS", 3000),
        llm_json_mode=_bool("LLM_JSON_MODE", False),
        image_provider=_str("IMAGE_PROVIDER", "placeholder"),
        image_api_base=_str("IMAGE_API_BASE", "https://ai.api.nvidia.com/v1/genai"),
        image_api_key=_str("IMAGE_API_KEY", ""),
        image_model=_str("IMAGE_MODEL", "black-forest-labs/flux-3-image-klein"),
        image_aspect_ratio=_str("IMAGE_ASPECT_RATIO", "1:2"),
        image_timeout_seconds=_int("IMAGE_TIMEOUT_SECONDS", 180),
        image_retries=_int("IMAGE_RETRIES", 3),
        image_seed=_int("IMAGE_SEED", 0),
        vision_model=_str("VISION_MODEL", "meta/llama-3.2-90b-vision-instruct"),
        pipeline_db_path=_str("PIPELINE_DB_PATH", ""),
        pipeline_output_dir=_str("PIPELINE_OUTPUT_DIR", ""),
        pipeline_auto_approve=_bool("PIPELINE_AUTO_APPROVE", False),
        pipeline_topic_candidates=_int("PIPELINE_TOPIC_CANDIDATES", 3),
        pipeline_shot_count=_int("PIPELINE_SHOT_COUNT", 4),
        pipeline_material_dir=_str("PIPELINE_MATERIAL_DIR", ""),
    )
