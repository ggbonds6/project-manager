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
import threading
import time
from concurrent.futures import ThreadPoolExecutor
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


# 引擎实例缓存。**按线程隔离**：RapidOCR 在识别过程中会写实例内部状态，
# 多线程共用同一个实例并不安全；用线程局部存储换安全性，
# 代价是每个线程各加载一份模型（每份几十 MB，可以接受）。
_LOCAL = threading.local()


def _build_engine(name: str):
    if name == "paddle":
        from paddleocr import PaddleOCR  # type: ignore

        return PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)

    from rapidocr_onnxruntime import RapidOCR

    return RapidOCR()


def get_engine(name: str = "rapid"):
    """获取（并缓存）**当前线程**的 OCR 引擎实例。"""
    cache = getattr(_LOCAL, "engines", None)
    if cache is None:
        cache = {}
        _LOCAL.engines = cache
    if name not in cache:
        cache[name] = _build_engine(name)
    return cache[name]


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


def ocr_pages(
    image_paths: list[Path],
    engine_name: str = "rapid",
    workers: int | None = None,
) -> tuple[OcrResult, list[OcrResult]]:
    """逐页识别，返回 (合并结果, 每页结果)。

    **并发识别，且顺序严格保持**（页码与结果一一对应）。

    为什么值得并发：实测在 12 核机器上，`OMP_NUM_THREADS=4` 时**单页推理只用掉约 1/3 的
    CPU**——串行处理多页会把其余核心白白闲置。25 页扫描件实测串行 OCR 需 109s。
    `workers` 建议 ≈ CPU 核数 / `OMP_NUM_THREADS`。
    """
    paths = list(image_paths)
    started = time.perf_counter()

    if workers is None:
        from .config import settings  # 局部导入，避免模块级循环依赖

        workers = settings.ocr_workers
    workers = max(1, min(workers, len(paths))) if paths else 1

    if workers == 1:
        per_page = [ocr_image(p, engine_name) for p in paths]
    else:
        # ThreadPoolExecutor.map 保证返回顺序与输入一致，页码不会错位
        with ThreadPoolExecutor(max_workers=workers) as pool:
            per_page = list(pool.map(lambda p: ocr_image(p, engine_name), paths))

    merged = OcrResult(
        lines=[line for page in per_page for line in page.lines],
        elapsed=time.perf_counter() - started,
    )
    return merged, per_page
