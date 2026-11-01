"""字幕时间轴与 ASS/SRT 生成。

移植自 MoneyPrinterTurbo 的 subtitle.py：保留"有逐词时间轴就按词切片、没有就按
标点与字数比例切分"的两套策略，以及描边、渐入渐出、弹跳这三种抖音常见样式；
渲染方式改为 ASS 文本由 FFmpeg 的 ass 滤镜烧录，比逐帧贴图轻得多。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .tts import SpeechAsset, WordCue


_SENTENCE_SPLIT = re.compile(r"(?<=[。！？；!?;])")
_CLAUSE_SPLIT = re.compile(r"(?<=[，、,：:])")


@dataclass(frozen=True)
class SubtitleCue:
    """一条字幕：起止时间（毫秒）与显示文本。"""

    start_ms: int
    end_ms: int
    text: str


def build_cues(
    speech: SpeechAsset,
    *,
    mode: str = "sentence",
    max_chars: int = 16,
    min_ms: int = 420,
) -> list[SubtitleCue]:
    """按配置生成字幕时间轴；word 模式需要真实的逐词时间轴。"""

    if mode == "word" and speech.word_level:
        return _cues_from_words(speech.words, max_chars=max_chars, min_ms=min_ms)
    return _cues_from_text(speech.text, speech.duration_ms, max_chars=max_chars, min_ms=min_ms)


def _cues_from_words(
    words: tuple[WordCue, ...],
    *,
    max_chars: int,
    min_ms: int,
) -> list[SubtitleCue]:
    """把逐词时间轴按字数合并成字幕行，避免一行只有一个字闪一下。"""

    cues: list[SubtitleCue] = []
    buffer: list[WordCue] = []
    length = 0
    for word in words:
        buffer.append(word)
        length += len(word.text)
        if length >= max_chars:
            cues.append(_merge_words(buffer, min_ms=min_ms))
            buffer = []
            length = 0
    if buffer:
        cues.append(_merge_words(buffer, min_ms=min_ms))
    return _fix_overlaps(cues)


def _merge_words(words: list[WordCue], *, min_ms: int) -> SubtitleCue:
    return SubtitleCue(
        start_ms=words[0].start_ms,
        end_ms=max(words[-1].end_ms, words[0].start_ms + min_ms),
        text="".join(word.text for word in words),
    )


def _cues_from_text(
    text: str,
    duration_ms: int,
    *,
    max_chars: int,
    min_ms: int,
) -> list[SubtitleCue]:
    """没有逐词时间轴时，按标点切句再按字数比例分配时间。"""

    lines = _split_lines(text, max_chars=max_chars)
    total = sum(len(line) for line in lines)
    if total <= 0 or duration_ms <= 0:
        return []
    cues: list[SubtitleCue] = []
    cursor = 0
    for line in lines:
        span = max(int(duration_ms * len(line) / total), min_ms)
        cues.append(SubtitleCue(start_ms=cursor, end_ms=min(cursor + span, duration_ms), text=line))
        cursor += span
    return _fix_overlaps(cues)


def _split_lines(text: str, *, max_chars: int) -> list[str]:
    """先按句号切，再按逗号切，绝不在词中间硬切。

    早期版本对超长小节做 clause[:max_chars] 硬切，字幕会出现“…今天就能完成的最”这种
    断在半截的短语，看上去像卡帧。现在保留整段交给 ASS 自动折行（WrapStyle 0），
    观众看到的是完整句子，只是排成两行。
    """

    lines: list[str] = []
    for sentence in _SENTENCE_SPLIT.split(text or ""):
        sentence = sentence.strip()
        if not sentence:
            continue
        for clause in _CLAUSE_SPLIT.split(sentence):
            clause = clause.strip()
            if clause:
                lines.append(clause)
    if lines:
        return lines
    fallback = (text or "").strip()
    return [fallback] if fallback else []


def _fix_overlaps(cues: list[SubtitleCue]) -> list[SubtitleCue]:
    """相邻字幕不允许重叠，后一条至少比前一条晚 60ms 出现。"""

    fixed: list[SubtitleCue] = []
    for cue in cues:
        if fixed and cue.start_ms <= fixed[-1].start_ms:
            cue = SubtitleCue(start_ms=fixed[-1].start_ms + 60, end_ms=cue.end_ms, text=cue.text)
        if cue.end_ms <= cue.start_ms:
            cue = SubtitleCue(start_ms=cue.start_ms, end_ms=cue.start_ms + 300, text=cue.text)
        fixed.append(cue)
    return fixed


def write_ass(
    cues: list[SubtitleCue],
    output: Path,
    *,
    width: int,
    height: int,
    font: str,
    font_size: int,
    margin_v: int,
    pop: bool,
) -> None:
    """写出 ASS 字幕；文件名保持固定，便于以工作目录相对路径交给 ass 滤镜。"""

    header = [
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {width}",
        f"PlayResY: {height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "YCbCr Matrix: TV.709",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour,"
        " BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle,"
        " BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        f"Style: Default,{font},{font_size},&H00FFFFFF,&H000000FF,&HCC000000,&H99000000,"
        f"-1,0,0,0,100,100,0.4,0,1,3.4,2.0,2,90,90,{margin_v},1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    events = [
        "Dialogue: 0,{start},{end},Default,,0,0,0,,{tags}{text}".format(
            start=_timestamp(cue.start_ms),
            end=_timestamp(cue.end_ms),
            tags=(
                r"{\fad(70,70)\blur2\fscx84\fscy84\t(0,120,\fscx100\fscy100)}"
                if pop
                else r"{\fad(160,160)\blur2}"
            ),
            text=_escape(cue.text),
        )
        for cue in cues
    ]
    output.write_text("\n".join(header + events) + "\n", encoding="utf-8")


def write_srt(cues: list[SubtitleCue], output: Path) -> None:
    """额外导出 SRT，方便排查时间轴问题或交给其他工具复用。"""

    blocks = [
        f"{index}\n{_srt_time(cue.start_ms)} --> {_srt_time(cue.end_ms)}\n{cue.text}\n"
        for index, cue in enumerate(cues, start=1)
    ]
    output.write_text("\n".join(blocks), encoding="utf-8")


def _timestamp(ms: int) -> str:
    """ASS 时间轴格式 h:mm:ss.cc。"""

    total = max(0, ms)
    hours, remainder = divmod(total, 3600000)
    minutes, remainder = divmod(remainder, 60000)
    seconds, centis = divmod(remainder, 1000)
    return f"{hours}:{minutes:02d}:{seconds:02d}.{centis // 10:02d}"


def _srt_time(ms: int) -> str:
    """SRT 时间轴格式 hh:mm:ss,mmm。"""

    total = max(0, ms)
    hours, remainder = divmod(total, 3600000)
    minutes, remainder = divmod(remainder, 60000)
    seconds, millis = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"


def _escape(text: str) -> str:
    """转义 ASS 控制字符，避免模型文案里的大括号破坏样式标签。"""

    return text.replace("\\", "＼").replace("{", "（").replace("}", "）").strip()
