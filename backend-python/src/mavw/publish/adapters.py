"""发布适配器。

本期按用户确认的范围只做「出成片 + 生成发布计划」：
- DryRunAdapter：把标题/文案/标签/视频路径写成一份发布单，人工复制到平台后台；
- WebhookAdapter：配置了 PUBLISH_WEBHOOK_URL 时把同一份发布单 POST 出去，
  方便后续接自己的发布服务（抖音/小红书/视频号都走这一个出口）。
真实平台上传（含 Cookie、上传分片、审核状态回查）留在 adapters 里做扩展点。
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any, Protocol

import httpx


class PublishAdapter(Protocol):
    name: str

    async def publish(self, payload: dict[str, Any]) -> dict[str, Any]: ...


class DryRunAdapter:
    """生成发布单 JSON，不触网。"""

    name = "dry-run"

    def __init__(self, output_dir: Path) -> None:
        self._dir = output_dir / "publish"

    async def publish(self, payload: dict[str, Any]) -> dict[str, Any]:
        self._dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        path = self._dir / f"publish-{payload.get('task_id')}-{stamp}.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return {"platform": self.name, "status": "READY", "plan": str(path)}


class WebhookAdapter:
    """把发布单 POST 给自建发布服务。"""

    name = "webhook"

    def __init__(self, url: str, timeout: float = 30.0) -> None:
        self._url = url
        self._timeout = timeout

    async def publish(self, payload: dict[str, Any]) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=self._timeout, trust_env=False) as client:
            response = await client.post(self._url, json=payload)
            response.raise_for_status()
            return {
                "platform": self.name,
                "status": "SENT",
                "http_status": response.status_code,
                "response": response.text[:500],
            }


def build_adapters(output_dir: Path) -> list[PublishAdapter]:
    adapters: list[PublishAdapter] = [DryRunAdapter(output_dir)]
    webhook = (os.getenv("PUBLISH_WEBHOOK_URL") or "").strip()
    if webhook:
        adapters.append(WebhookAdapter(webhook))
    return adapters
