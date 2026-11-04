"""MoneyPrinterTurbo（MPT）适配器：把 vendor 子模块当作"能力引擎"用。

设计要点
--------
- 本模块只依赖 HTTP，**不 import MPT 的任何源码**，因此两套依赖永远不打架。
- 两个角色：
  * `MptProcess`：把 MPT 作为独立子进程拉起（`uvicorn app.asgi:app`），
    并轮询 `/docs` 或 `/ping` 直到就绪；关闭时优雅结束。
  * `MptClient`：对 `/api/v1` 的薄封装，负责脚本、素材词、配音、
    字幕、一条龙出片、任务查询、产物下载。
- 全部网络失败都收敛成 `MptError`，调用方（pipeline）据此决定是否回退到
  项目自带的本地引擎，保证"MPT 挂了也不影响主流程"。
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib import error, request
from urllib.parse import quote

from ..config import PROJECT_ROOT, Settings


class MptError(RuntimeError):
    """MPT 不可用或返回了非 2xx。"""


@dataclass
class MptTask:
    """MPT 异步任务的状态快照（对应 TaskStatusData，额外字段原样保留）。"""

    task_id: str
    state: int
    progress: int
    videos: list[str]
    combined_videos: list[str]
    failed_stage: str | None
    error: str | None
    raw: dict[str, Any]

    @property
    def done(self) -> bool:
        # MPT 约定 state=1 表示成功完成；其它取值见其 task.py。
        return self.state == 1

    @property
    def failed(self) -> bool:
        return self.state < 0 or bool(self.failed_stage or self.error)


def _port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


class MptProcess:
    """管理 MPT 的子进程生命周期（可选）。

    只有调用 `start()` 才会真正拉起；若外部已自行启动 MPT（例如 docker
    或单独终端），可以把 `auto_start` 设为 False 只做健康检查。
    """

    def __init__(self, settings: Settings, *, auto_start: bool = True) -> None:
        self.settings = settings
        self.auto_start = auto_start
        self.vendor = settings.mpt_vendor()
        self.port = settings.mpt_port
        self.host = "127.0.0.1"
        self.python = settings.mpt_python or self._default_python()
        self._proc: subprocess.Popen[bytes] | None = None
        self._log_handle = None

    # ------------------------------------------------------------------ utils
    def _default_python(self) -> str:
        # 优先用当前解释器（保证和主进程同一套依赖视图），但 MPT 有自己的
        # 依赖；调用方可在 MAVW_MPT_PYTHON 指定一个专门装好 MPT 依赖的解释器。
        import sys

        return sys.executable

    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    def is_ready(self) -> bool:
        return _port_open(self.host, self.port)

    def _prepare_config(self) -> None:
        """确保 vendor 里有 config.toml 与必要的运行目录。"""

        if not (self.vendor / "pyproject.toml").is_file():
            raise MptError(
                f"未找到 MoneyPrinterTurbo vendor 目录：{self.vendor}"
                "（请先在仓库根 git clone 到 vendor/MoneyPrinterTurbo）"
            )
        cfg = self.vendor / "config.toml"
        example = self.vendor / "config.example.toml"
        if not cfg.is_file() and example.is_file():
            # 首次启动自动生成，并把它监听的端口对齐到我们期望的端口。
            text = example.read_text(encoding="utf-8")
            cfg.write_text(text, encoding="utf-8")
        # 无论是否新建，都把 listen_port / listen_host 覆盖成受控值，
        # 避免 MPT 默认监听 8080 与我们 8088 的 Java 启动器冲突。
        try:
            import toml  # MPT 依赖里有 toml；主进程通常也有

            data = toml.loads(cfg.read_text(encoding="utf-8"))
            data["listen_host"] = "127.0.0.1"
            data["listen_port"] = self.port
            cfg.write_text(toml.dumps(data), encoding="utf-8")
        except Exception:
            # 没有 toml 库时退回纯文本替换，尽量把端口改对。
            text = cfg.read_text(encoding="utf-8")
            text = _force_toml_keys(text, {"listen_host": '"127.0.0.1"', "listen_port": str(self.port)})
            cfg.write_text(text, encoding="utf-8")

    # --------------------------------------------------------------- lifecycle
    def start(self, *, timeout: float | None = None) -> None:
        if self.is_ready():
            return
        if not self.auto_start:
            raise MptError(
                f"MPT 未运行且 auto_start=False：{self.base_url()}"
            )
        self._prepare_config()
        log_dir = PROJECT_ROOT / "output"
        log_dir.mkdir(parents=True, exist_ok=True)
        self._log_handle = open(log_dir / "mpt.out.log", "ab", buffering=0)
        env = dict(os.environ)
        env.setdefault("PYTHONUNBUFFERED", "1")
        env.setdefault("PYTHONIOENCODING", "utf-8")
        # 让 MPT 用自己目录作为 runtime_root（源码 checkout 默认就是它）。
        env.setdefault("MPT_HOME", str(self.vendor))
        cmd = [
            self.python,
            "-m",
            "uvicorn",
            "app.asgi:app",
            "--host",
            self.host,
            "--port",
            str(self.port),
            "--log-level",
            "warning",
        ]
        self._proc = subprocess.Popen(
            cmd,
            cwd=str(self.vendor),
            env=env,
            stdout=self._log_handle,
            stderr=subprocess.STDOUT,
        )
        deadline = time.time() + (timeout or self.settings.mpt_start_timeout_seconds)
        while time.time() < deadline:
            if self._proc.poll() is not None:
                raise MptError(
                    "MPT 进程启动后立即退出，请查看 output/mpt.out.log"
                )
            if self.is_ready():
                return
            time.sleep(0.5)
        raise MptError(f"MPT 在 {deadline - time.time():.0f}s 内未就绪")

    def stop(self) -> None:
        if self._proc is not None and self._proc.poll() is None:
            try:
                self._proc.terminate()
                self._proc.wait(timeout=15)
            except Exception:
                try:
                    self._proc.kill()
                except Exception:
                    pass
        self._proc = None
        if self._log_handle is not None:
            try:
                self._log_handle.close()
            except Exception:
                pass
            self._log_handle = None


def _force_toml_keys(text: str, kv: dict[str, str]) -> str:
    """在无 toml 库时把顶层键改对（粗暴但够用）。"""

    lines = text.splitlines()
    remaining = dict(kv)
    for i, line in enumerate(lines):
        stripped = line.strip()
        for key, val in list(remaining.items()):
            if stripped.startswith(key + " ") or stripped.startswith(key + "="):
                lines[i] = f"{key} = {val}"
                remaining.pop(key)
                break
    if remaining:
        lines.extend(f"{k} = {v}" for k, v in remaining.items())
    return "\n".join(lines) + "\n"


class MptClient:
    """对 MPT `/api/v1` 的 HTTP 封装。"""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.base = settings.mpt_base_url.rstrip("/")
        self.api_key = settings.mpt_api_key
        self.timeout = settings.mpt_request_timeout_seconds

    # ------------------------------------------------------------------ http
    def _headers(self, *, json_body: bool = True) -> dict[str, str]:
        headers = {"Accept": "application/json"}
        if json_body:
            headers["Content-Type"] = "application/json"
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def _request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        timeout: float | None = None,
    ) -> dict[str, Any]:
        url = f"{self.base}{path}"
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = request.Request(url, data=data, method=method, headers=self._headers(json_body=body is not None))
        try:
            with request.urlopen(req, timeout=timeout or self.timeout) as resp:
                raw = resp.read()
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            raise MptError(f"MPT {method} {path} -> {exc.code}: {detail}") from exc
        except error.URLError as exc:
            raise MptError(f"MPT 不可达（{url}）：{exc.reason}") from exc
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise MptError(f"MPT 返回非 JSON：{raw[:200]!r}") from exc

    def _unwrap(self, payload: dict[str, Any]) -> Any:
        """把 {status,message,data} 拆出 data；status!=200 抛错。"""

        status = payload.get("status", 200)
        if status not in (200, 201):
            raise MptError(f"MPT 返回 status={status}: {payload.get('message')}")
        return payload.get("data")

    # -------------------------------------------------------------- health
    def ping(self) -> bool:
        try:
            self._request("GET", "/ping", timeout=3)
            return True
        except MptError:
            return False

    # ---------------------------------------------------------------- llm
    def generate_script(
        self,
        subject: str,
        *,
        language: str = "",
        paragraphs: int = 1,
        prompt: str = "",
        system_prompt: str = "",
        timeout: float | None = None,
    ) -> str:
        body = {
            "video_subject": subject,
            "video_language": language,
            "paragraph_number": max(1, min(10, paragraphs)),
            "video_script_prompt": prompt,
            "custom_system_prompt": system_prompt,
        }
        data = self._unwrap(self._request("POST", "/api/v1/scripts", body, timeout=timeout))
        return (data or {}).get("video_script", "")

    def generate_terms(
        self,
        subject: str,
        script: str,
        *,
        amount: int = 5,
        match_script: bool = False,
    ) -> list[str]:
        body = {
            "video_subject": subject,
            "video_script": script,
            "amount": max(1, min(50, amount)),
            "match_materials_to_script": match_script,
        }
        data = self._unwrap(self._request("POST", "/api/v1/terms", body))
        terms = (data or {}).get("video_terms", [])
        if isinstance(terms, str):
            terms = [terms]
        return [t for t in terms if t]

    # -------------------------------------------------------------- render
    def create_video(self, params: dict[str, Any]) -> str:
        """提交一条龙出片任务，返回 task_id。params 透传给 MPT。"""

        data = self._unwrap(self._request("POST", "/api/v1/videos", params))
        task_id = (data or {}).get("task_id")
        if not task_id:
            raise MptError(f"MPT 未返回 task_id：{data}")
        return task_id

    def create_audio(self, params: dict[str, Any]) -> str:
        data = self._unwrap(self._request("POST", "/api/v1/audio", params))
        return (data or {}).get("task_id", "")

    def create_subtitle(self, params: dict[str, Any]) -> str:
        data = self._unwrap(self._request("POST", "/api/v1/subtitle", params))
        return (data or {}).get("task_id", "")

    def get_task(self, task_id: str) -> MptTask:
        data = self._unwrap(self._request("GET", f"/api/v1/tasks/{quote(task_id)}"))
        data = data or {}
        return MptTask(
            task_id=data.get("task_id", task_id),
            state=int(data.get("state", 0) or 0),
            progress=int(data.get("progress", 0) or 0),
            videos=list(data.get("videos") or []),
            combined_videos=list(data.get("combined_videos") or []),
            failed_stage=data.get("failed_stage"),
            error=data.get("error"),
            raw=data,
        )

    def wait_task(
        self,
        task_id: str,
        *,
        poll_seconds: float = 3.0,
        timeout: float = 1800,
    ) -> MptTask:
        deadline = time.time() + timeout
        last: MptTask | None = None
        while time.time() < deadline:
            last = self.get_task(task_id)
            if last.done or last.failed:
                return last
            time.sleep(poll_seconds)
        if last is not None:
            return last
        raise MptError(f"等待 MPT 任务超时：{task_id}")

    def download(self, remote_path: str) -> bytes:
        """下载 MPT 产物。remote_path 可为绝对 URL 或 /tasks/... 相对路径。"""

        if remote_path.startswith(("http://", "https://")):
            url = remote_path
        else:
            url = f"{self.base}{remote_path}"
        req = request.Request(url, method="GET", headers=self._headers(json_body=False))
        try:
            with request.urlopen(req, timeout=self.timeout) as resp:
                return resp.read()
        except error.HTTPError as exc:
            raise MptError(f"下载 MPT 产物失败 {exc.code}: {remote_path}") from exc
        except error.URLError as exc:
            raise MptError(f"下载 MPT 产物不可达：{exc.reason}") from exc
