"""画风 / 尺寸 / 质量预设。

前端把这些做成下拉与分段控件，后端统一翻译成 prompt 后缀和采样参数，
避免用户自己拼提示词，也避免同一套词在不同模型上效果跳变。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Ratio:
    key: str
    label: str
    width: int
    height: int


# flux 的尺寸是 16 的倍数友好区，这里给的都是常用竖/横/方形
RATIOS: dict[str, Ratio] = {
    "1:1": Ratio("1:1", "方形 1:1", 1024, 1024),
    "3:4": Ratio("3:4", "竖版 3:4", 864, 1152),
    "4:3": Ratio("4:3", "横版 4:3", 1152, 864),
    "9:16": Ratio("9:16", "竖屏 9:16", 768, 1344),
    "16:9": Ratio("16:9", "横屏 16:9", 1344, 768),
    "2:3": Ratio("2:3", "海报 2:3", 832, 1248),
    "21:9": Ratio("21:9", "宽银幕 21:9", 1536, 640),
}

DEFAULT_RATIO = "9:16"


@dataclass(frozen=True)
class StylePreset:
    key: str
    label: str
    suffix: str


# 每一条都只追加描述性词，不覆盖用户自己的主体描述
STYLES: dict[str, StylePreset] = {
    "none": StylePreset("none", "不指定", ""),
    "photo": StylePreset(
        "photo", "写实摄影", "真实照片质感，自然光，浅景深，35mm 镜头，色彩通透，细节丰富"
    ),
    "cinematic": StylePreset(
        "cinematic", "电影感", "电影级构图，宽银幕质感，戏剧性侧光，高动态范围，胶片颗粒"
    ),
    "illustration": StylePreset("illustration", "插画", "扁平插画风格，干净的色块，克制的配色，无颗粒"),
    "3d": StylePreset("3d", "3D 渲染", "细腻的 3D 渲染，柔和全局光照，材质真实，轻微景深"),
    "watercolor": StylePreset("watercolor", "水彩", "水彩手绘质感，纸张纹理，透明度渐变，笔触自然"),
    "cyberpunk": StylePreset("cyberpunk", "赛博朋克", "赛博朋克氛围，霓虹灯光，紫青对比色，湿润反光地面"),
    "film": StylePreset("film", "胶片", "复古胶片色调，明显颗粒，轻微暗角，暖色偏移"),
    "minimal": StylePreset("minimal", "极简", "极简构图，大面积留白，单一主体，柔和阴影"),
}


@dataclass(frozen=True)
class QualityPreset:
    key: str
    label: str
    steps: int
    cfg_scale: float


QUALITIES: dict[str, QualityPreset] = {
    "fast": QualityPreset("fast", "快速", 8, 2.5),
    "standard": QualityPreset("standard", "标准", 20, 3.5),
    "fine": QualityPreset("fine", "精细", 35, 4.5),
}

DEFAULT_QUALITY = "standard"

# 模型：klein 是蒸馏版最快，dev 质量更好但更慢，schnell 适合草图
# qwen 系列走 OpenAI 兼容协议（/v1/images/generations），中文渲染强，适合带字封面
# 注意：模型 ID 随服务商而异，可在「设置 → 出图模型」直接手填覆盖这里
MODELS: dict[str, str] = {
    "klein": "black-forest-labs/flux-3-image-klein",
    "dev": "black-forest-labs/flux.1-dev",
    "schnell": "black-forest-labs/flux.1-schnell",
    "qwen": "qwen-image",
    "qwen-edit": "qwen-image-edit",
    "seedream": "doubao-seedream-3-0-t2i-250415",
    "kolors": "Kwai-Kolors/Kolors",
}

DEFAULT_MODEL = "klein"

# 走 OpenAI 兼容协议（/v1/images/generations）的模型前缀，用于前端提示与尺寸对齐
OPENAI_STYLE_MODELS = ("qwen", "qwen-edit", "seedream", "kolors")


def is_openai_style(model_key: str) -> bool:
    """该模型档是否走 OpenAI 兼容协议（尺寸需对齐到其支持档位）。"""

    return (model_key or "").lower() in OPENAI_STYLE_MODELS


def build_prompt(prompt: str, style: str) -> str:
    """主体描述 + 画风后缀，去重空格。"""

    base = (prompt or "").strip()
    preset = STYLES.get(style or "none")
    if preset is None or not preset.suffix:
        return base
    if preset.suffix in base:
        return base
    return f"{base}。{preset.suffix}"


def resolve_size(ratio: str, width: int | None, height: int | None) -> tuple[int, int]:
    """自定义宽高优先；否则按比例预设；都没有就竖屏 9:16。"""

    if width and height:
        return max(256, min(width, 2048)), max(256, min(height, 2048))
    preset = RATIOS.get(ratio or DEFAULT_RATIO) or RATIOS[DEFAULT_RATIO]
    return preset.width, preset.height


# qwen-image / seedream 常见的支持尺寸档，按宽高比就近对齐
_OPENAI_SIZE_TIERS: tuple[tuple[int, int], ...] = (
    (1024, 1024),  # 1:1
    (1328, 1328),  # 1:1 高清
    (1024, 1536),  # 2:3 竖
    (768, 1344),   # 9:16 竖
    (928, 1664),   # 9:16 高竖
    (1024, 768),   # 3:2 横
    (1344, 768),   # 16:9 横
    (1664, 928),   # 16:9 宽
    (1536, 640),   # 21:9
)


def resolve_openai_size(width: int, height: int) -> tuple[int, int]:
    """把任意宽高按宽高比就近对齐到 OpenAI 协议支持的尺寸档（避免 4xx）。"""

    target = (width / height) if height else 1.0
    best = min(_OPENAI_SIZE_TIERS, key=lambda wh: abs((wh[0] / wh[1]) - target))
    return best
