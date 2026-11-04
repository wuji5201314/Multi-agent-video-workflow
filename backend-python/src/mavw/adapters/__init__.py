"""外部能力引擎适配器。

当下只有一个成员：MoneyPrinterTurbo（MPT）。它以 vendor 子模块的形式
放在仓库 vendor/ 下，通过它自己的 HTTP API（/api/v1）被借用能力，
本包永远不 import MPT 的源码，避免依赖打架。
"""

from .mpt import MptClient, MptError, MptProcess

__all__ = ["MptClient", "MptError", "MptProcess"]
