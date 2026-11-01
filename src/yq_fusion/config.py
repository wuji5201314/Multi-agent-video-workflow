"""集中配置：全部走环境变量，默认值保证离线也能一条命令出片。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]


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
    base_url: str = "https://api.siliconflow.cn/v1"
    api_key: str = ""
    llm_model: str = "deepseek-ai/DeepSeek-V4-Flash"
    llm_timeout_seconds: int = 120

    # ---------- 管线 ----------
    pipeline_db_path: str = ""
    pipeline_output_dir: str = ""
    pipeline_auto_approve: bool = False
    pipeline_topic_candidates: int = 3
    pipeline_shot_count: int = 4
    pipeline_material_dir: str = ""

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
        base_url=_str("BASE_URL", "https://api.siliconflow.cn/v1"),
        api_key=_str("API_KEY", ""),
        llm_model=_str("LLM_MODEL", "deepseek-ai/DeepSeek-V4-Flash"),
        llm_timeout_seconds=_int("LLM_TIMEOUT_SECONDS", 120),
        pipeline_db_path=_str("PIPELINE_DB_PATH", ""),
        pipeline_output_dir=_str("PIPELINE_OUTPUT_DIR", ""),
        pipeline_auto_approve=_bool("PIPELINE_AUTO_APPROVE", False),
        pipeline_topic_candidates=_int("PIPELINE_TOPIC_CANDIDATES", 3),
        pipeline_shot_count=_int("PIPELINE_SHOT_COUNT", 4),
        pipeline_material_dir=_str("PIPELINE_MATERIAL_DIR", ""),
    )
