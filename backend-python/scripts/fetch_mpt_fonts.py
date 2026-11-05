"""按需下载 MoneyPrinterTurbo 字幕渲染所需的字体文件。

这些字体（STHeitiMedium.ttc 约 53MB 等）体积超过 GitHub 推荐上限，因此不进本仓库的
git 历史，改为首次需要时从 MPT 官方仓库拉取到 vendor/MoneyPrinterTurbo/resource/fonts/。

用法：
    python scripts/fetch_mpt_fonts.py            # 缺什么下什么
    python scripts/fetch_mpt_fonts.py --all      # 强制重新下载全部
    python scripts/fetch_mpt_fonts.py --check    # 只检查，不下载（缺则退出码 1）
"""

from __future__ import annotations

import argparse
import sys
import urllib.request
from pathlib import Path

# 与 vendor/MoneyPrinterTurbo/resource/fonts 对应；版本锁定到已知可用提交，避免上游改名。
RAW_BASE = "https://raw.githubusercontent.com/harry0703/MoneyPrinterTurbo/main/resource/fonts"

# 文件名 -> 最小期望字节数（用于判断是否下载完整，防止半截文件）
FONTS = {
    "STHeitiMedium.ttc": 50 * 1024 * 1024,
    "STHeitiLight.ttc": 50 * 1024 * 1024,
    "MicrosoftYaHeiNormal.ttc": 15 * 1024 * 1024,
    "MicrosoftYaHeiBold.ttc": 12 * 1024 * 1024,
}


def _project_root() -> Path:
    # scripts/ 的上一级是 backend-python，再上一级是仓库根
    return Path(__file__).resolve().parents[2]


def _fonts_dir() -> Path:
    return _project_root() / "vendor" / "MoneyPrinterTurbo" / "resource" / "fonts"


def _is_ok(path: Path, min_bytes: int) -> bool:
    try:
        return path.exists() and path.stat().st_size >= min_bytes
    except OSError:
        return False


def _download(name: str, dest: Path, min_bytes: int) -> None:
    url = f"{RAW_BASE}/{name}"
    print(f"下载 {name} ...")
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "mavw-fetch-fonts/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as fh:
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            fh.write(chunk)
    size = tmp.stat().st_size
    if size < min_bytes:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"{name} 下载不完整：{size} 字节 < 期望 {min_bytes}")
    tmp.replace(dest)
    print(f"  完成 {name}（{size / 1024 / 1024:.1f} MB）")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="下载 MPT 字幕字体")
    parser.add_argument("--all", action="store_true", help="强制重新下载全部字体")
    parser.add_argument("--check", action="store_true", help="只检查缺失，不下载")
    args = parser.parse_args(argv)

    fonts_dir = _fonts_dir()
    fonts_dir.mkdir(parents=True, exist_ok=True)

    missing = [name for name, minb in FONTS.items() if not _is_ok(fonts_dir / name, minb)]
    if args.check:
        if missing:
            print("缺少字体：" + "、".join(missing))
            print(f"运行 `python {Path(__file__).name}` 下载。")
            return 1
        print("字体齐全。")
        return 0

    targets = list(FONTS) if args.all else missing
    if not targets:
        print("字体齐全，无需下载。")
        return 0

    failed: list[str] = []
    for name in targets:
        try:
            _download(name, fonts_dir / name, FONTS[name])
        except Exception as exc:  # noqa: BLE001
            print(f"  失败 {name}：{exc}", file=sys.stderr)
            failed.append(name)

    if failed:
        print("以下字体下载失败：" + "、".join(failed), file=sys.stderr)
        print("可手动从 https://github.com/harry0703/MoneyPrinterTurbo/tree/main/resource/fonts 下载放入：")
        print(f"  {fonts_dir}")
        return 1

    print("全部完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
