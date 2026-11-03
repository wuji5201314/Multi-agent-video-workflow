"""IDEA / 命令行一键启动入口（放在 backend-python 根目录，便于右键 Run）。

用法（任选其一）：
    # 在 IDEA 里直接对 run.py 右键 Run
    # 或在命令行：
    python run.py                 # 默认 0.0.0.0:8010
    python run.py --port 8011     # 指定端口
    python run.py --reload        # 改代码自动重载（开发用）

它做的只是：把 ``src`` 加进 import 路径，然后启动 uvicorn 跑 ``mavw.api:app``。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 让 `import mavw` 能找到 backend-python/src
SRC = Path(__file__).resolve().parent / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

import uvicorn  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="启动 Multi-Agent Video Workflow 后端")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8010)
    parser.add_argument("--reload", action="store_true", help="开发模式：代码改动自动重载")
    args = parser.parse_args()
    uvicorn.run(
        "mavw.api:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        reload_dirs=[str(SRC)] if args.reload else None,
    )


if __name__ == "__main__":
    main()
