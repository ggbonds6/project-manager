"""平台 OCR 客户端（PaddleOCR-VL，走内网平台网关）。

手册见仓库根目录 `平台OCR调用使用手册.md`。

| 项 | 值 |
| --- | --- |
| 端点 | `POST {base}/ocr`、`GET {base}/ocr/health` |
| 地址/密钥 | **复用大模型的两个环境变量**（同一网关、同一个 sk）；可用 `OCR_BASE_URL` / `OCR_API_KEY` 单独覆盖 |
| 输出 | 每页 `markdown` + `blocks`（版面块，含 bbox） |
| 性能 | 单页 ~1.8s；**实测并发 12 → 2.45 页/秒**（同页两次结果完全一致，服务端是确定性的） |

## 三个必须记住的实测结论

**① 发图用 150 DPI JPEG，不要 300 DPI PNG。**
120~300 DPI 的识别结果**完全一致**（VL 模型内部会下采样到 `ocr_max_pixels` ≈ 100 万像素），
但 300 DPI PNG 每页 6.7MB、150 DPI JPEG 每页 396KB——**差 17 倍**，白等的是上传时间。

**② `blocks` 的位置随请求形式变化。**
单页请求（`{"image": ...}`）在**顶层**；批量请求（`{"images": [...]}`）顶层**没有** blocks，
在 `results[i].blocks`。本模块统一从 `results` 取，两种形式都能拿到（实测确认）。

**③ 印章默认不识别，而且会编造。**
默认就返回 `label == "seal"` 的块（内容为空）——这是"这页有章"的**免费探测**信号，不用开参数。
但低 DPI 下开 `seal: true` 会**幻觉**：实测 150 DPI 图上读出「中国银行股份有限公司」，
而真实印章是移动公司的章，完全不相干（印章裁剪图太小、放大后仍糊，模型就编）。
故 `OCR_SEAL` **默认 false**；确需读印章时必须用**高 DPI 原图**重跑（手册的"两遍法"）。
"""

from __future__ import annotations

import base64
import json
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from .config import settings

MAX_BATCH_PAGES = 16
"""单次请求的最大页数（手册 §7.2：≤16 页/请求）。"""


class PlatformOcrError(RuntimeError):
    """平台 OCR 调用失败。消息里直接写"下一步该查什么"，便于自助排障。"""


@dataclass
class PlatformPage:
    """平台返回的一页结果。"""

    markdown: str = ""
    blocks: list[dict] = field(default_factory=list)
    width: int = 0
    height: int = 0
    elapsed: float = 0.0
    """本页耗时**估算值**：平台按批返回，拿不到单页耗时，按服务端 total_ms 均摊（见 `_ocr_group`）。"""
    error: str = ""

    @property
    def ok(self) -> bool:
        return not self.error

    @property
    def seal_count(self) -> int:
        """检测到的印章块数（内容为空，仅表示"这页有章"）。"""
        return sum(1 for b in self.blocks if b.get("label") == "seal")


@dataclass
class OcrHealth:
    ok: bool = False
    workers: int = 0
    idle: int = 0
    options: list[str] = field(default_factory=list)
    detail: str = ""
    ts: float = 0.0


# ── 连通性探测（带缓存）───────────────────────────────────────────
#
# 每次上传都去探测一次会拖慢响应；但完全不探测又无法判断"平台能不能用"。
# 折中：结果缓存 TTL 秒，期间复用；失败时不缓存太久，便于平台恢复后自动接回。

_HEALTH: OcrHealth | None = None
_HEALTH_TTL_OK = 60.0
_HEALTH_TTL_FAIL = 15.0
_HEALTH_LOCK = threading.Lock()


def health(force: bool = False, timeout: float = 10.0) -> OcrHealth:
    """探测平台 OCR 是否可用（结果带缓存）。"""
    global _HEALTH
    with _HEALTH_LOCK:
        cached = _HEALTH
    if not force and cached is not None:
        ttl = _HEALTH_TTL_OK if cached.ok else _HEALTH_TTL_FAIL
        if time.time() - cached.ts < ttl:
            return cached

    url = settings.ocr_base_url.rstrip("/") + "/ocr/health"
    result = OcrHealth(ts=time.time())
    if not settings.ocr_base_url:
        result.detail = "未配置 OCR 地址（OCR_BASE_URL 或 LLM_BASE_URL）"
    else:
        try:
            req = urllib.request.Request(url)
            if settings.ocr_api_key:
                req.add_header("Authorization", "Bearer " + settings.ocr_api_key)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                d = json.loads(resp.read().decode())
            result.ok = d.get("status") == "ok"
            result.workers = int(d.get("workers") or 0)
            result.idle = int(d.get("idle") or 0)
            result.options = list(d.get("options") or [])
            result.detail = f"workers={result.workers} idle={result.idle}"
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", "replace")[:160]
            result.detail = f"HTTP {exc.code}: {body}（401 检查 sk 是否有效）"
        except Exception as exc:  # noqa: BLE001 - 探测要吞掉所有异常并如实上报
            result.detail = (
                f"{type(exc).__name__}: {exc}（检查网络能否访问 {settings.ocr_base_url}）"
            )

    with _HEALTH_LOCK:
        _HEALTH = result
    return result


def available(force: bool = False) -> bool:
    """平台 OCR 是否可用（供自检脚本与 `/health` 探针使用）。

    注意：本地 OCR 兜底已于 2026-09-18 移除，本函数不再用于"走平台还是走本地"的选路。
    """
    return health(force=force).ok


# ── 识别 ─────────────────────────────────────────────────────────


def _post(payload: dict, timeout: int | None = None) -> dict:
    url = settings.ocr_base_url.rstrip("/") + "/ocr"
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    if settings.ocr_api_key:
        req.add_header("Authorization", "Bearer " + settings.ocr_api_key)
    try:
        with urllib.request.urlopen(req, timeout=timeout or settings.ocr_timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:300]
        raise PlatformOcrError(f"HTTP {exc.code}: {body}") from None
    except Exception as exc:  # noqa: BLE001
        raise PlatformOcrError(f"{type(exc).__name__}: {exc}") from None


def _pages_from_response(d: dict, n: int) -> list[PlatformPage]:
    """把响应拆成 n 页。

    **统一从 `results` 取**：批量请求的 blocks 只在 results 里，单页请求虽有顶层 blocks、
    但也附带 results —— 走同一条路少一个分支，少一个 bug。
    """
    results = d.get("results") or []
    if not results and n == 1 and (d.get("markdown") or d.get("blocks")):
        # 少数实现可能不返回 results，退回顶层字段
        results = [
            {
                "markdown": d.get("markdown", ""),
                "blocks": d.get("blocks") or [],
                "width": d.get("width"),
                "height": d.get("height"),
            }
        ]
    out: list[PlatformPage] = []
    for i in range(n):
        r = results[i] if i < len(results) else {}
        out.append(
            PlatformPage(
                markdown=r.get("markdown") or "",
                blocks=r.get("blocks") or [],
                width=int(r.get("width") or 0),
                height=int(r.get("height") or 0),
            )
        )
    return out


def _ocr_group(paths: list[Path], seal: bool, seal_min_pixels: int | None) -> list[PlatformPage]:
    """识别一组页面（**一次请求**，≤16 页）。失败时把错误分发给整组，不中断其它组。"""
    payload: dict = {
        "images": [base64.b64encode(p.read_bytes()).decode() for p in paths],
    }
    if seal:
        payload["seal"] = True
        if seal_min_pixels:
            payload["seal_min_pixels"] = seal_min_pixels
    t0 = time.perf_counter()
    try:
        d = _post(payload)
    except PlatformOcrError as exc:
        msg = str(exc)
        return [PlatformPage(error=msg, elapsed=round(time.perf_counter() - t0, 2)) for _ in paths]
    wall = time.perf_counter() - t0
    pages = _pages_from_response(d, len(paths))
    # 逐页耗时：平台**按批**返回（一次请求多页），拿不到单页耗时，
    # 故按服务端 total_ms 均摊到本批各页；拿不到就退回客户端墙钟。这是估算值。
    server_s = (float(d.get("total_ms") or 0) / 1000) or wall
    per = round(server_s / max(1, len(paths)), 2)
    for p in pages:
        p.elapsed = per
    return pages


def ocr_images(
    paths: list[Path],
    *,
    batch_size: int | None = None,
    concurrency: int | None = None,
    seal: bool | None = None,
    seal_min_pixels: int | None = None,
    on_progress: Callable[[int, int], None] | None = None,
) -> list[PlatformPage]:
    """批量识别图片，**返回顺序与输入严格一致**。

    :param on_progress: `(已完成页数, 总页数)`，每完成一组回调一次，用于上报进度。
    :param concurrency:  同时在飞的请求数。平台是**本项目专用**、可打满（实测并发 12 → 2.45 页/秒）。
    """
    items = list(paths)
    if not items:
        return []
    size = max(1, min(batch_size or settings.ocr_batch_pages, MAX_BATCH_PAGES))
    seal = settings.ocr_seal if seal is None else seal
    pin = seal_min_pixels or settings.ocr_seal_min_pixels
    workers = max(1, min(concurrency or settings.ocr_concurrency, (len(items) + size - 1) // size))

    out: list[PlatformPage] = [PlatformPage() for _ in items]
    done = 0
    lock = threading.Lock()

    def run(start: int) -> None:
        nonlocal done
        group = items[start : start + size]
        pages = _ocr_group(group, seal, pin)
        for k, pg in enumerate(pages):
            out[start + k] = pg
        with lock:
            done += len(group)
            if on_progress:
                on_progress(done, len(items))

    starts = list(range(0, len(items), size))
    if workers == 1:
        for s in starts:
            run(s)
    else:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            list(pool.map(run, starts))
    return out
