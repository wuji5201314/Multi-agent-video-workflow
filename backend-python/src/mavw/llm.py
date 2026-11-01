"""选题与脚本生成：有 API key 时走 OpenAI 兼容接口，没有就用内置模板。

内置模板不是占位符意义上的"假数据"：它按简报关键词拼出可播的口播稿，
目的是让整条链路（选题→脚本→素材→渲染→发布）在没有大模型额度时也能跑通。
"""

from __future__ import annotations

import json
import logging
import random
import re
from dataclasses import dataclass, field

import httpx

from .config import Settings

logger = logging.getLogger(__name__)


@dataclass
class Topic:
    title: str
    angle: str = ""
    hook: str = ""


@dataclass
class Shot:
    headline: str
    body: str
    duration_ms: int | None = None
    image_query: str = ""


@dataclass
class Script:
    title: str
    cover_title: str
    caption: str
    tags: list[str] = field(default_factory=list)
    shots: list[Shot] = field(default_factory=list)


_SYSTEM_PROMPT = (
    "你是一名短视频编导。输出必须是严格 JSON，不要 Markdown 代码块，不要额外解释。"
)


class ContentWriter:
    """选题 / 脚本生成器，接口与大模型解耦，便于替换供应商。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    # ---------------- 选题 ----------------
    async def topics(self, brief: str, count: int = 3) -> list[Topic]:
        prompt = (
            f"围绕主题「{brief}」，给出 {count} 个竖屏短视频选题。"
            '返回 JSON：{"topics":[{"title":"不超过14字的标题","angle":"切入角度一句话","hook":"开头3秒钩子一句"}]}'
        )
        data = await self._chat(prompt)
        items = (data or {}).get("topics") or []
        topics = [
            Topic(
                title=str(item.get("title") or "").strip()[:40],
                angle=str(item.get("angle") or "").strip(),
                hook=str(item.get("hook") or "").strip(),
            )
            for item in items
            if str(item.get("title") or "").strip()
        ]
        return topics[:count] or _fallback_topics(brief, count)

    # ---------------- 脚本 ----------------
    async def script(self, brief: str, topic: Topic, shot_count: int = 4, note: str = "") -> Script:
        prompt = (
            f"主题：{brief}\n选题：{topic.title}（角度：{topic.angle}）\n"
            f"请写一支 {shot_count} 个镜头的竖屏口播短视频脚本，每个镜头 2-4 句口语短句。\n"
            + (f"用户补充要求：{note}\n" if note else "")
            + "注意三类要求：\n"
            "1) headline 是画面上的大字标题，会单独显示；body 是配音与字幕的稿子，"
            "不要复述 headline（不要用同一个开头或同义句重说一遍），要接着往下讲。\n"
            "2) image_query 会直接交给文生图模型：必须是**实拍照片**的画面描述，只写人物、场景、物件、"
            "光线、景别；严禁写手机或电脑屏幕内容、APP 界面、截图、按钮、图表、字幕条，"
            "也不要要求画面里出现任何文字（模型写中文会变成乱码）。\n"
            "3) 各镜头的 image_query 必须明显不同：换场景、换景别（特写/中景/远景）、换主体，避免几张图长得一样。\n"
            + '返回 JSON：{"title":"标题","cover_title":"封面大字≤14字","caption":"发布文案","tags":["标签1","标签2","标签3"],'
            '"shots":[{"headline":"画面大字≤14字","body":"该镜头口播1-2句，不得复述headline",'
            '"image_query":"实拍照片描述，例如：清晨书桌上摊开的笔记本与一杯咖啡，暖光侧照，浅景深特写"}]}'
        )
        data = await self._chat(prompt)
        parsed = _parse_script(data) if data else None
        return parsed or _fallback_script(brief, topic, shot_count)

    # ---------------- 底层调用 ----------------
    async def _chat(self, prompt: str) -> dict | None:
        if not self._settings.api_key:
            return None
        url = f"{self._settings.base_url.rstrip('/')}/chat/completions"
        payload: dict = {
            "model": self._settings.llm_model,
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.8,
            "max_tokens": self._settings.llm_max_tokens,
        }
        if self._settings.llm_json_mode:
            payload["response_format"] = {"type": "json_object"}
        headers = {"Authorization": f"Bearer {self._settings.api_key}"}
        try:
            async with httpx.AsyncClient(
                timeout=self._settings.llm_timeout_seconds, trust_env=False
            ) as client:
                response = await client.post(url, json=payload, headers=headers)
                response.raise_for_status()
                body = response.json()
            message = ((body.get("choices") or [{}])[0].get("message") or {})
            content = str(message.get("content") or "").strip()
            if not content:
                # deepseek-v4.1-flash 这类推理模型在个别参数下会把答案写进 reasoning_content。
                content = str(message.get("reasoning_content") or "").strip()
            return json.loads(_extract_json(content))
        except Exception as exc:  # noqa: BLE001 - 大模型不可用时必须能退化
            logger.warning("大模型调用失败，改用内置模板：%s", exc)
            return None


def _extract_json(text: str) -> str:
    """模型偶尔会在 JSON 前后加解释，取最外层的一对花括号。"""

    cleaned = _strip_code_fence(text)
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start != -1 and end > start:
        return cleaned[start : end + 1]
    return cleaned


def _strip_code_fence(text: str) -> str:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```[a-zA-Z]*\n", "", cleaned)
        cleaned = re.sub(r"```$", "", cleaned).strip()
    return cleaned


def _parse_script(data: dict) -> Script | None:
    shots = [
        Shot(
            headline=str(item.get("headline") or "").strip()[:40],
            body=str(item.get("body") or "").strip(),
            duration_ms=item.get("duration_ms"),
            image_query=str(item.get("image_query") or "").strip(),
        )
        for item in (data.get("shots") or [])
        if str(item.get("body") or "").strip()
    ]
    if not shots:
        return None
    return Script(
        title=str(data.get("title") or "").strip(),
        cover_title=str(data.get("cover_title") or "").strip()[:40],
        caption=str(data.get("caption") or "").strip(),
        tags=[str(tag).strip() for tag in (data.get("tags") or []) if str(tag).strip()][:6],
        shots=shots,
    )


def _keywords(brief: str) -> list[str]:
    words = [w for w in re.split(r"[\s,，。;；、/|]+", brief) if w]
    return words or [brief or "内容"]


def _fallback_topics(brief: str, count: int) -> list[Topic]:
    words = _keywords(brief)
    core = words[0]
    templates = [
        (f"{core}的三个真相", "用反常识结论抓住注意力", f"很多人以为{core}很简单，其实第一步就错了。"),
        (f"{core}新手最容易踩的坑", "列点式避坑清单", f"如果你刚开始接触{core}，这条能帮你省下大量时间。"),
        (f"我是怎么搞定{core}的", "第一人称经验复盘", f"关于{core}，我把踩过的坑浓缩成了这一条。"),
        (f"{core}十分钟入门", "从零到能用的最小路径", f"不用学全套，先把{core}这四步跑通。"),
    ]
    picked = templates[: max(1, min(count, len(templates)))]
    return [Topic(title=t, angle=a, hook=h) for t, a, h in picked]


def _fallback_script(brief: str, topic: Topic, shot_count: int) -> Script:
    core = _keywords(brief)[0]
    shots = [
        # 开场镜头：headline 上画面，body 接着往下讲，两者不重复（否则配音和字幕都会念两遍）。
        Shot(
            headline=topic.hook[:14] or "先说结论",
            body=f"真正拉开差距的，不是工具，而是起步的前十分钟。这十分钟只做一件事，让第一版先跑起来。",
            image_query=f"{core} 概念 对比画面",
        ),
        Shot(
            headline="第一步最容易被忽略",
            body=f"做{core}先把目标和边界写清楚，后面的动作才不会乱。目标就一句话：日更一条三十秒的口播。",
            image_query=f"{core} 桌面 计划 特写",
        ),
        Shot(
            headline="第二步：把动作拆小",
            body="把大任务拆成今天就能完成的最小动作，先跑通再优化。一轮只改一个变量，才知道哪一步起了作用。",
            image_query=f"{core} 步骤 流程 示意",
        ),
        Shot(
            headline="第三步：留出复盘时间",
            body="每做完一轮就记录哪个动作真的有效，下一轮只加一个变量。连续记三轮，流程自己就长出来了。",
            image_query=f"{core} 复盘 笔记 场景",
        ),
        Shot(
            headline="记住这一句",
            body=f"{core}不是一次做对，而是每轮都比上一轮更清楚一点。",
            image_query=f"{core} 收尾 定格",
        ),
    ]
    picked = shots[: max(2, min(shot_count, len(shots)))]
    return Script(
        title=topic.title or core,
        cover_title=(topic.title or core)[:14],
        caption=f"{topic.title}。{topic.hook} #干货分享",
        tags=[core, "干货分享", "经验总结"][:5],
        shots=picked,
    )
