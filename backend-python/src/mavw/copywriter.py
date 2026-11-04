"""文案工作台：详情页文案 / 口播稿 / 仿写改写。

与 ContentWriter 的区别：这里输出的是**自由文本**（不是 JSON 结构），
面向"直接拿去用的文案"，并保留历史记录。
"""

from __future__ import annotations

import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path

import httpx

from .config import Settings

# 文案类型：key -> (中文名, 系统提示)
COPY_KINDS: dict[str, tuple[str, str]] = {
    "detail": (
        "详情页文案",
        "你是电商详情页文案专家。根据产品信息，写一份结构化详情页文案："
        "开头痛点、核心卖点（3-5 条，每条一句话+一句展开）、使用场景、信任背书、行动号召。"
        "语言有说服力，不浮夸，中文。",
    ),
    "oral": (
        "口播稿",
        "你是短视频口播稿撰稿人。写一段 30-60 秒的竖屏口播稿："
        "前 3 秒强钩子，中段 2-3 个信息点，结尾一句行动号召。"
        "口语化、有节奏、每句不要太长，中文。",
    ),
    "rewrite": (
        "文案仿写",
        "你是文案改写专家。把用户给的参考文案改写成新的口吻（去重、换表达、保留核心信息），"
        "避免与原句雷同，输出可直接使用的新文案，中文。",
    ),
    "title": (
        "标题党生成",
        "你是新媒体标题专家。根据用户给的主题，给出 8 个高点击率的标题，"
        "风格多样（悬念/数字/对比/情绪），每行一个，不带序号，中文。",
    ),
}

STYLES = {
    "none": "",
    "hard_sell": "风格：强营销、强转化，多用数字和对比。",
    "soft": "风格：温和种草、真诚分享感。",
    "professional": "风格：专业理性、条理清晰。",
    "fun": "风格：轻松幽默、有网感。",
}


@dataclass(frozen=True)
class CopyItem:
    id: str
    kind: str
    topic: str
    style: str
    text: str
    model: str
    elapsed_ms: int
    created_at: str


class CopyStudio:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self.root = settings.output_dir() / "studio" / "copy"
        self.root.mkdir(parents=True, exist_ok=True)
        self._history_path = self.root / "_history.json"

    def kinds(self) -> list[dict]:
        return [{"key": k, "label": v[0]} for k, v in COPY_KINDS.items()]

    def styles(self) -> list[dict]:
        labels = {
            "none": "默认",
            "hard_sell": "强营销",
            "soft": "种草",
            "professional": "专业",
            "fun": "轻松",
        }
        return [{"key": k, "label": labels.get(k, k)} for k in STYLES]

    async def write(
        self,
        topic: str,
        *,
        kind: str = "detail",
        style: str = "none",
        extra: str = "",
    ) -> CopyItem:
        topic = (topic or "").strip()
        if not topic:
            raise ValueError("主题不能为空")
        kind = kind if kind in COPY_KINDS else "detail"
        style = style if style in STYLES else "none"
        system = COPY_KINDS[kind][1]
        style_hint = STYLES.get(style, "")
        if style_hint:
            system = system + "\n" + style_hint

        started = time.time()
        text = await self._compose(system, topic, extra)
        elapsed = int((time.time() - started) * 1000)

        item = CopyItem(
            id="copy-" + uuid.uuid4().hex[:12],
            kind=kind,
            topic=topic,
            style=style,
            text=text,
            model=self._settings.llm_model,
            elapsed_ms=elapsed,
            created_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        )
        self._append(item)
        return item

    async def _compose(self, system: str, topic: str, extra: str) -> str:
        if not self._settings.api_key:
            return self._fallback(system, topic, extra)
        url = f"{self._settings.base_url.rstrip('/')}/chat/completions"
        user = f"主题/产品：{topic}"
        if extra.strip():
            user += f"\n补充要求：{extra.strip()}"
        payload = {
            "model": self._settings.llm_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.85,
            "max_tokens": self._settings.llm_max_tokens,
        }
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
                content = str(message.get("reasoning_content") or "").strip()
            if content:
                return content
            return self._fallback(system, topic, extra)
        except Exception:  # noqa: BLE001 - 网络失败也回退到模板
            return self._fallback(system, topic, extra)

    def _fallback(self, system: str, topic: str, extra: str) -> str:
        # 没有配置大模型 key 时给一个可用的骨架，标明需要填写的部分。
        head = "（未配置大模型密钥，以下是骨架，可在设置页填入 API Key 后重新生成）\n\n"
        if "详情页" in system:
            return head + (
                f"【{topic}】\n"
                f"痛点：{topic} 常见的问题……\n"
                "核心卖点：\n1. 卖点一 —— 一句话展开\n2. 卖点二 —— 一句话展开\n3. 卖点三 —— 一句话展开\n"
                "使用场景：……\n信任背书：……\n立即下单，……"
            )
        if "口播" in system:
            return head + (
                f"（前3秒钩子）你知道吗，{topic}……\n"
                "（信息点1）……\n（信息点2）……\n（信息点3）……\n"
                "（行动号召）现在就……"
            )
        if "标题" in system:
            return head + "\n".join(
                f"{i}. {topic}：{w}" for i, w in enumerate(
                    ["你不得不知道的事", "90% 的人都做错了", "看完少走三年弯路",
                     "这个方法绝了", "为什么高手都在用", "真相终于说清楚了",
                     "别再踩这个坑", "一句话点醒无数人"], start=1)
            )
        return head + f"{topic}\n\n（在此改写参考文案，保留核心信息，换一种表达。）\n{extra}"

    # ---------------- 历史 ----------------
    def _load(self) -> list[dict]:
        import json

        if not self._history_path.exists():
            return []
        try:
            return json.loads(self._history_path.read_text("utf-8")) or []
        except Exception:  # noqa: BLE001
            return []

    def _append(self, item: CopyItem) -> None:
        import json

        rows = self._load()
        rows.insert(0, asdict(item))
        rows = rows[:80]
        self._history_path.write_text(
            json.dumps(rows, ensure_ascii=False, indent=2), "utf-8"
        )

    def history(self, limit: int = 40) -> list[dict]:
        return self._load()[:limit]

    def get(self, copy_id: str) -> dict | None:
        for row in self._load():
            if row.get("id") == copy_id:
                return row
        return None

    def delete(self, copy_id: str) -> bool:
        import json

        rows = self._load()
        kept = [r for r in rows if r.get("id") != copy_id]
        if len(kept) == len(rows):
            return False
        self._history_path.write_text(
            json.dumps(kept, ensure_ascii=False, indent=2), "utf-8"
        )
        return True
