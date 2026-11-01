"""用 Pillow 画 1080x1920 封面卡与素材卡（不依赖 Playwright/浏览器）。

旧实现靠无头浏览器截图 HTML 模板，机器上没装浏览器就跑不动；
这里改成纯 Pillow 绘制，保证离线环境也能出图，视觉上仍是深色渐变 + 大标题 + 标签。
"""

from __future__ import annotations

import io
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CARD_WIDTH = 1080
CARD_HEIGHT = 1920
_FONT_CANDIDATES = (
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/System/Library/Fonts/PingFang.ttc",
)


def _font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for candidate in _FONT_CANDIDATES:
        if Path(candidate).is_file():
            try:
                return ImageFont.truetype(candidate, size)
            except OSError:
                continue
    return ImageFont.load_default()


def _wrap(draw: ImageDraw.ImageDraw, text: str, font, max_width: int) -> list[str]:
    """按像素宽度折行，中英文都适用。"""

    lines: list[str] = []
    current = ""
    for char in text:
        probe = current + char
        if draw.textlength(probe, font=font) <= max_width or not current:
            current = probe
            continue
        lines.append(current)
        current = char
    if current:
        lines.append(current)
    return lines


def _gradient(width: int, height: int, top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    base = Image.new("RGB", (1, height))
    draw = ImageDraw.Draw(base)
    for y in range(height):
        ratio = y / max(height - 1, 1)
        draw.point(
            (0, y),
            fill=(
                int(top[0] + (bottom[0] - top[0]) * ratio),
                int(top[1] + (bottom[1] - top[1]) * ratio),
                int(top[2] + (bottom[2] - top[2]) * ratio),
            ),
        )
    return base.resize((width, height))


def render_card(
    title: str,
    body: str = "",
    tags: list[str] | None = None,
    *,
    badge: str = "",
    seed: int = 0,
    width: int = CARD_WIDTH,
    height: int = CARD_HEIGHT,
) -> bytes:
    """生成一张竖屏卡片，返回 PNG 字节。"""

    palettes = (
        ((18, 24, 48), (86, 40, 120)),
        ((10, 34, 42), (22, 96, 88)),
        ((42, 18, 24), (128, 52, 44)),
        ((16, 22, 36), (56, 72, 148)),
        ((30, 26, 16), (120, 96, 40)),
    )
    top, bottom = palettes[seed % len(palettes)]
    canvas = _gradient(width, height, top, bottom)
    draw = ImageDraw.Draw(canvas)

    # 右上角柔光，让纯渐变不至于太死板。
    glow = Image.new("RGB", (width, height), (0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse(
        (int(width * 0.45), int(-height * 0.12), int(width * 1.25), int(height * 0.5)),
        fill=(255, 214, 150),
    )
    canvas = Image.blend(canvas, Image.composite(glow, canvas, glow.convert("L").point(lambda v: int(v * 0.35))), 0.25)
    draw = ImageDraw.Draw(canvas)

    margin = int(width * 0.09)
    if badge:
        badge_font = _font(int(width * 0.038))
        draw.rounded_rectangle(
            (margin, int(height * 0.085), margin + int(draw.textlength(badge, font=badge_font)) + 56, int(height * 0.085) + 76),
            radius=38,
            fill=(28, 32, 48),
            outline=(255, 255, 255),
            width=3,
        )
        draw.text((margin + 28, int(height * 0.085) + 16), badge, font=badge_font, fill=(255, 255, 255))

    title_font = _font(int(width * 0.105))
    title_lines = _wrap(draw, title or "未命名标题", title_font, width - margin * 2)[:3]
    cursor_y = int(height * 0.30)
    for line in title_lines:
        draw.text((margin, cursor_y), line, font=title_font, fill=(255, 255, 255))
        cursor_y += int(width * 0.135)

    if body:
        body_font = _font(int(width * 0.052))
        body_lines = _wrap(draw, body, body_font, width - margin * 2)[:5]
        cursor_y += int(height * 0.02)
        for line in body_lines:
            draw.text((margin, cursor_y), line, font=body_font, fill=(226, 232, 240))
            cursor_y += int(width * 0.078)

    if tags:
        tag_font = _font(int(width * 0.040))
        cursor_x, cursor_y = margin, int(height * 0.845)
        for tag in tags[:5]:
            text = f"#{tag}"
            box_width = int(draw.textlength(text, font=tag_font)) + 48
            if cursor_x + box_width > width - margin:
                cursor_x, cursor_y = margin, cursor_y + 92
            draw.rounded_rectangle(
                (cursor_x, cursor_y, cursor_x + box_width, cursor_y + 78),
                radius=34,
                fill=(255, 255, 255),
            )
            draw.text((cursor_x + 24, cursor_y + 14), text, font=tag_font, fill=(24, 28, 40))
            cursor_x += box_width + 22

    buffer = io.BytesIO()
    canvas.save(buffer, format="PNG")
    return buffer.getvalue()


def render_cover_card(title: str, subtitle: str, tags: list[str] | None) -> bytes:
    """封面卡：比素材卡多一行副标题。"""

    return render_card(title, subtitle, tags, badge="封面", seed=7)


def render_material_card(headline: str, body: str, index: int, tags: list[str] | None = None) -> bytes:
    """素材卡：在没有真实配图时作为占位素材，保证链路可跑通。"""

    return render_card(headline, body, tags, badge=f"0{index}", seed=index)
