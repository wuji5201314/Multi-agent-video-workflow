"""YanQue-Media-Fusion：内容生成 → 成品渲染 → 发布 的一体化融合项目。

来源分工：
- 编排（人工确认 / 可中断 / 可反馈）：移植自 YanQue-AI 的 LangGraph 内容生成链路；
- 渲染（TTS 配音 / 字幕 / 镜头动效 / ffmpeg 合成）：移植自 MoneyPrinterTurbo 系渲染层；
- 业务字段与状态机：对齐 yq-admin 的 aimedia 模块（Java 侧可直接对接同名字段）。
"""

__version__ = "0.1.0"
