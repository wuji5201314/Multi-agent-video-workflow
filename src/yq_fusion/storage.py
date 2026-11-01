"""存储抽象：默认落本地目录，需要时可切回 Java 预签名 + TOS。"""

from __future__ import annotations

import asyncio
import time
from pathlib import Path
from urllib.parse import urlparse

import httpx

from .config import Settings

INTERNAL_TOKEN_HEADER = "X-Internal-Token"

_SUFFIX = {"image/png": ".png", "image/jpeg": ".jpg", "video/mp4": ".mp4", "audio/wav": ".wav"}


class StorageError(RuntimeError):
    """存储层失败。"""


class LocalStorage:
    """把成品写到本地 output/assets，返回相对路径作为 object_key。"""

    def __init__(self, root: Path) -> None:
        self._root = root
        self._root.mkdir(parents=True, exist_ok=True)

    async def upload(
        self,
        client: httpx.AsyncClient | None,
        task_id: int | str,
        sequence: int,
        content_type: str,
        content: bytes,
    ) -> str:
        def _write() -> str:
            folder = self._root / f"preview-{task_id}"
            folder.mkdir(parents=True, exist_ok=True)
            suffix = _SUFFIX.get(content_type, Path(urlparse(str(task_id)).path).suffix or ".bin")
            name = f"seq{sequence:02d}-{int(time.time() * 1000)}-{len(content)}{suffix}"
            (folder / name).write_bytes(content)
            return str(Path(folder.name) / name)

        return await asyncio.to_thread(_write)

    def path_for(self, key: str) -> Path:
        return self._root / key


class PresignStorage:
    """沿用 YanQue 的 TOS 预签名通道：POST presign → PUT 上传 → 返回 object_key。"""

    def __init__(self, base_url: str, endpoint: str, token: str) -> None:
        self._base = base_url.rstrip("/")
        self._endpoint = endpoint if endpoint.startswith("/") else f"/{endpoint}"
        self._token = token

    async def upload(
        self,
        client: httpx.AsyncClient | None,
        task_id: int | str,
        sequence: int,
        content_type: str,
        content: bytes,
    ) -> str:
        if client is None:
            raise StorageError("预签名上传需要 httpx 客户端。")
        try:
            presign_id = int(task_id)
        except (TypeError, ValueError) as exc:
            raise StorageError(f"预签名上传要求数字型 previewTaskId：{task_id}") from exc
        response = await client.post(
            f"{self._base}{self._endpoint}",
            json={
                "previewTaskId": presign_id,
                "sequenceNo": sequence,
                "fileSize": len(content),
                "contentType": content_type,
            },
        )
        response.raise_for_status()
        payload = response.json()
        if int(payload.get("code") or 0) != 200:
            raise StorageError(str(payload.get("message") or "成品TOS预签名失败。"))
        data = payload.get("data") or {}
        upload_url = str(data.get("uploadUrl") or "")
        object_key = str(data.get("objectKey") or "")
        headers = {str(k): str(v) for k, v in (data.get("headers") or {}).items()}
        if not upload_url or not object_key:
            raise StorageError("成品TOS预签名结果不完整。")
        upload = await client.put(upload_url, content=content, headers=headers)
        upload.raise_for_status()
        return object_key


def build_storage(settings: Settings):
    if settings.storage_backend.lower() == "presign":
        return PresignStorage(
            settings.yanque_admin_base_url,
            settings.presign_endpoint,
            settings.yanque_internal_api_key,
        )
    return LocalStorage(settings.storage_root())


def internal_headers(settings: Settings) -> dict[str, str]:
    if settings.storage_backend.lower() != "presign":
        return {}
    return {INTERNAL_TOKEN_HEADER: settings.yanque_internal_api_key} if settings.yanque_internal_api_key else {}
