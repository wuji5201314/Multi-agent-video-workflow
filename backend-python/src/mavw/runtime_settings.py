"""运行期用户配置：把「每个用户不同的模型 / API Key / 服务地址」持久化到本地。

设计要点
--------
Settings 是 frozen 的启动期快照（全部来自 env）。多用户场景下，
每个使用者希望用自己的大模型 / 出图 Key / TTS 服务，因此需要一层
"可覆盖、可持久化" 的配置。这里用 output/settings.json 存一份最新配置，
并把它 merge 回 Settings（dataclasses.replace），供各 Studio 重建时使用。

默认值永远以 env（Settings 原值）为准；json 里没写的键不覆盖。
API Key 读取时做掩码展示，落盘为明文（本地单机部署，文件在 output/ 下）。
"""

from __future__ import annotations

import json
from dataclasses import asdict, replace
from pathlib import Path
from typing import Any

from .config import Settings

# 允许用户覆盖的字段 -> 分组，用于前端分组展示与校验
FIELD_GROUPS: dict[str, list[str]] = {
    "llm": ["base_url", "api_key", "llm_model"],
    "image": ["image_provider", "image_api_base", "image_api_key", "image_model", "vision_model"],
    "tts": ["media_render_tts_provider", "media_render_tts_voice", "media_render_tts_base_url", "media_render_tts_api_key"],
    "video": ["media_render_width", "media_render_height", "media_render_fps"],
}

# 需要脱敏的字段
SECRET_FIELDS = {"api_key", "image_api_key", "media_render_tts_api_key"}

FIELD_LABELS = {
    "base_url": "大模型服务地址 (Base URL)",
    "api_key": "大模型 API Key",
    "llm_model": "文本模型",
    "image_provider": "出图提供方",
    "image_api_base": "出图服务地址",
    "image_api_key": "出图 API Key",
    "image_model": "出图模型",
    "vision_model": "参考图识别模型",
    "media_render_tts_provider": "配音引擎 (edge/openai/silent)",
    "media_render_tts_voice": "默认音色",
    "media_render_tts_base_url": "TTS 服务地址",
    "media_render_tts_api_key": "TTS API Key",
    "media_render_width": "成片宽",
    "media_render_height": "成片高",
    "media_render_fps": "帧率",
}

FIELD_HINTS = {
    "base_url": "OpenAI 兼容接口，例如 https://integrate.api.nvidia.com/v1",
    "llm_model": "例如 deepseek-ai/deepseek-v4.1-flash",
    "image_provider": "nvidia / openai / placeholder",
    "image_api_base": "例如 https://ai.api.nvidia.com/v1/genai",
    "image_model": "例如 black-forest-labs/flux-3-image-klein",
    "vision_model": "支持图片的对话模型，用于参考图重绘",
    "media_render_tts_provider": "edge 免密钥；openai 需填服务地址与 Key",
    "media_render_tts_voice": "例如 zh-CN-YunxiNeural",
}


def _coerce(field: str, value: Any, settings: Settings) -> Any:
    """把 json 里的值按 Settings 中对应字段的类型转换。"""
    current = getattr(settings, field, None)
    if isinstance(current, bool):
        if isinstance(value, bool):
            return value
        return str(value).strip().lower() in {"1", "true", "yes", "on"}
    if isinstance(current, int):
        try:
            return int(str(value).strip())
        except (TypeError, ValueError):
            return current
    return "" if value is None else str(value)


class RuntimeSettingsStore:
    """读写 output/settings.json 的用户覆盖配置。"""

    def __init__(self, base: Settings) -> None:
        self.base = base
        self.root = base.output_dir()
        self.path = self.root / "settings.json"

    # ---------- 读 ----------
    def load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {}
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (json.JSONDecodeError, OSError):
            return {}

    def apply(self) -> Settings:
        """返回把用户覆盖 merge 之后的 Settings。"""
        overrides = self.load()
        if not overrides:
            return self.base
        allowed = {f for fields in FIELD_GROUPS.values() for f in fields}
        coerced: dict[str, Any] = {}
        for key, value in overrides.items():
            if key not in allowed:
                continue
            coerced[key] = _coerce(key, value, self.base)
        if not coerced:
            return self.base
        return replace(self.base, **coerced)

    # ---------- 写 ----------
    def save(self, payload: dict[str, Any]) -> Settings:
        allowed = {f for fields in FIELD_GROUPS.values() for f in fields}
        current = self.load()
        for key, value in payload.items():
            if key in allowed:
                # 空字符串表示"清除覆盖、回到默认"，直接删键
                if isinstance(value, str) and value.strip() == "":
                    current.pop(key, None)
                else:
                    current[key] = value
        self.root.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(current, ensure_ascii=False, indent=2), encoding="utf-8")
        return self.apply()

    # ---------- 给前端展示 ----------
    def view(self, effective: Settings | None = None) -> dict[str, Any]:
        eff = effective or self.apply()
        overrides = self.load()
        groups: dict[str, Any] = {}
        for group, fields in FIELD_GROUPS.items():
            items = []
            for field in fields:
                raw = getattr(eff, field, "")
                masked = bool(getattr(eff, field, "")) if field in SECRET_FIELDS else None
                items.append(
                    {
                        "key": field,
                        "label": FIELD_LABELS.get(field, field),
                        "hint": FIELD_HINTS.get(field, ""),
                        "value": "" if field in SECRET_FIELDS else raw,
                        "has_value": masked,
                        "secret": field in SECRET_FIELDS,
                        "overridden": field in overrides,
                    }
                )
            groups[group] = items
        return {
            "path": str(self.path),
            "overridden": sorted(overrides.keys()),
            "groups": groups,
        }
