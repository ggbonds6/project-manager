"""OCR 引擎封装（可切换后端）。

默认用 **RapidOCR**，理由：
- 它加载的是 **PaddleOCR 同一批模型**（转成 ONNX），中文识别表现一致；
- 只依赖 `onnxruntime`，**不需要 paddlepaddle**——内网离线装机省事很多。

若验证后认为效果不达标，可切完整 PaddleOCR：
    pip install -e ".[paddle]"        # 装 paddleocr + paddlepaddle
    设置 OCR_ENGINE=paddle            # 见 config.py
两个后端输出统一为 `OcrResult`，上层代码不用改。

⚠️ 模型首次加载较慢（数秒），故做进程内单例缓存；不要每张图重新构造引擎。
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# 让 onnxruntime / paddle 不抢占全部线程（与推理服务同机时避免互相拖慢）
os.environ.setdefault("OMP_NUM_THREADS", "4")


@dataclass
class OcrLine:
    text: str
    score: float
    box: Any = None


@dataclass
class OcrResult:
    lines: list[OcrLine] = field(default_factory=list)
    elapsed: float = 0.0

    @property
    def text(self) -> str:
        """按行拼接的纯文本（保持识别顺序）。"""
        return "\n".join(line.text for line in self.lines)

    @property
    def avg_score(self) -> float:
        if not self.lines:
            return 0.0
        return sum(line.score for line in self.lines) / len(self.lines)

    @property
    def low_confidence(self) -> list[OcrLine]:
        """低置信行（<0.8），供人工复核时重点看。"""
        return [line for line in self.lines if line.score < 0.8]


_ENGINE: Any = None
_ENGINE_NAME: str | None = None


def _build_engine(name: str):
    if name == "paddle":
        from paddleocr import PaddleOCR  # type: ignore

        return PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)

    from rapidocr_onnxruntime import RapidOCR

    return RapidOCR()


def get_engine(name: str = "rapid"):
    """获取（并缓存）OCR 引擎实例。"""
    global _ENGINE, _ENGINE_NAME
    if _ENGINE is None or _ENGINE_NAME != name:
        _ENGINE = _build_engine(name)
        _ENGINE_NAME = name
    return _ENGINE


def _parse_rapid(raw) -> list[OcrLine]:
    """RapidOCR 返回 [[box, text, score], ...]。"""
    lines: list[OcrLine] = []
    if not raw:
        return lines
    for item in raw:
        try:
            box, text, score = item[0], item[1], float(item[2])
        except (IndexError, TypeError, ValueError):
            continue
        if text:
            lines.append(OcrLine(text=str(text), score=score, box=box))
    return lines


def _parse_paddle(raw) -> list[OcrLine]:
    """PaddleOCR 返回 [[ [box, (text, score)], ... ]]。"""
    lines: list[OcrLine] = []
    if not raw:
        return lines
    page = raw[0] or []
    for item in page:
        try:
            box, (text, score) = item[0], item[1]
        except (IndexError, TypeError, ValueError):
            continue
        if text:
            lines.append(OcrLine(text=str(text), score=float(score), box=box))
    return lines


def ocr_image(path: str | Path, engine_name: str = "rapid") -> OcrResult:
    """识别单张图片（PNG/JPG）。"""
    started = time.perf_counter()
    engine = get_engine(engine_name)
    raw = engine(str(path))
    # RapidOCR 返回 (result, elapse)；PaddleOCR 返回 [[...]]
    if isinstance(raw, tuple):
        raw = raw[0]
    lines = _parse_paddle(raw) if engine_name == "paddle" else _parse_rapid(raw)
    return OcrResult(lines=lines, elapsed=time.perf_counter() - started)


def ocr_pages(image_paths: list[Path], engine_name: str = "rapid") -> tuple[OcrResult, list[OcrResult]]:
    """逐页识别，返回 (合并结果, 每页结果)。"""
    per_page: list[OcrResult] = []
    started = time.perf_counter()
    for img in image_paths:
        per_page.append(ocr_image(img, engine_name))
    merged = OcrResult(
        lines=[line for page in per_page for line in page.lines],
        elapsed=time.perf_counter() - started,
    )
    return merged, per_page
