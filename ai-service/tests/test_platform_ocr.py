"""`platform_ocr.py` 响应解析的回归测试（**纯离线**：只喂 dict，不发任何请求）。

固化的就是注释里那两条"实测结论"：
① `blocks` 的位置随请求形式变化——批量请求只在 `results[i]` 里；
② 平台**不返回置信度**，代码不许自己编一个出来。

因为解析是纯函数，这里直接测 `_pages_from_response(d, n)`，不碰网络、不起线程。
"""

from __future__ import annotations

from dataclasses import fields
from pathlib import Path

import pytest

from pm_ai import document, platform_ocr
from pm_ai.platform_ocr import MAX_BATCH_PAGES, PlatformPage, _pages_from_response


def test_batch_response_reads_blocks_from_results() -> None:
    """挡的是：批量请求时"块全丢"。

    批量（`{"images": [...]}`）响应的顶层**没有** blocks，页级内容在 `results[i]`。
    这里故意同时塞一个顶层 blocks 当诱饵：解析必须只认 results，
    否则一旦服务端两种形式都返回，就会拿到错位/重复的块（出处定位跟着错）。
    """
    d = {
        "markdown": "顶层诱饵，不该被用到",
        "blocks": [{"label": "decoy", "content": "顶层诱饵"}],
        "results": [
            {
                "markdown": "第一页",
                "blocks": [{"label": "text", "content": "甲"}],
                "width": 1200,
                "height": 1600,
            },
            {"markdown": "第二页", "blocks": [{"label": "table", "content": "乙"}]},
        ],
    }
    pages = _pages_from_response(d, 2)
    assert [p.markdown for p in pages] == ["第一页", "第二页"]
    assert pages[0].blocks == [{"label": "text", "content": "甲"}]
    assert pages[1].blocks == [{"label": "table", "content": "乙"}]
    assert (pages[0].width, pages[0].height) == (1200, 1600)
    assert pages[0].ok is True and pages[0].error == ""


def test_single_response_falls_back_to_top_level() -> None:
    """挡的是：单张请求（`{"image": ...}`）没走通。

    单页响应的 blocks 在**顶层**且没有 `results`，实现里有一条 `n == 1` 的顶层兜底。
    顺带钉住印章信号：`label == "seal"` 的块默认就返回（内容为空），
    数量本身就是"这页有章"的免费探测——别把它当噪声丢掉。
    """
    d = {
        "markdown": "单页文本",
        "blocks": [{"label": "seal", "content": ""}],
        "width": 800,
        "height": 600,
    }
    pages = _pages_from_response(d, 1)
    assert len(pages) == 1
    assert pages[0].markdown == "单页文本"
    assert pages[0].blocks == [{"label": "seal", "content": ""}]
    assert pages[0].seal_count == 1
    assert (pages[0].width, pages[0].height) == (800, 600)


def test_top_level_fallback_only_applies_to_single_page() -> None:
    """挡的是：顶层兜底被"顺手"放大到批量。

    兜底条件里带 `n == 1`：批量（n > 1）缺 results 时必须如实返回空页，
    不能把顶层那一段文本复制到每一页——那是凭空造出重复内容，比空着更危险。
    """
    pages = _pages_from_response({"markdown": "顶层", "blocks": [{"label": "text"}]}, 2)
    assert [p.markdown for p in pages] == ["", ""]
    assert pages[0].blocks == []


def test_page_count_mismatch_fills_empty_pages_without_raising() -> None:
    """挡的是：页数对不上时抛异常或张冠李戴。

    实现的口径：按请求的 n 输出，缺的页补空 `PlatformPage`（`markdown=""`、`blocks=[]`、
    宽高 0、`ok is True`——空页不等于"这页识别失败"）；多出来的页直接丢掉。
    页号靠列表下标对齐，所以顺序绝不能乱。
    """
    pages = _pages_from_response({"results": [{"markdown": "只有一页"}]}, 3)
    assert [p.markdown for p in pages] == ["只有一页", "", ""]
    assert pages[2].blocks == [] and pages[2].width == 0 and pages[2].height == 0
    assert pages[2].ok is True

    # 响应比请求多：多余的页丢掉（返回的页数必须等于请求的页数）
    assert len(_pages_from_response({"results": [{"markdown": "a"}, {"markdown": "b"}]}, 1)) == 1

    # 完全空响应 / 字段缺失：不炸，如实给空页
    assert [p.markdown for p in _pages_from_response({}, 2)] == ["", ""]
    assert _pages_from_response({"results": [{}]}, 1)[0].markdown == ""


def test_platform_page_never_carries_confidence() -> None:
    """挡的是：给平台结果"编一个置信度"。

    注释写明"平台**不返回**置信度"，而历史上出过"OCR 平均置信度 0.97 的文件里金额被认错"
    的教训——编一个 0.9x 出来，等于把人骗去相信一个不存在的信号。
    实现的做法是 `PlatformPage` 连这个字段都没有，上层只能填 None。
    """
    assert "confidence" not in {f.name for f in fields(PlatformPage)}


def test_platform_pages_mapping_leaves_confidence_none(monkeypatch) -> None:
    """挡的是：平台页映射成 `PageText` 时把 confidence 填成 0.0 / 1.0 / 平均值。

    这里把 `ocr_images` 换成假实现（不发请求），只验证映射本身。
    如果 document.py 重构后不再有这个函数，就跳过——"无置信度"已由上一条守住。
    """
    mapper = getattr(document, "_platform_pages", None)
    if mapper is None:
        pytest.skip("document.py 已重构、没有 _platform_pages；无置信度由字段级测试守住")

    def fake_ocr_images(images, **kwargs):
        return [
            PlatformPage(markdown="页一", blocks=[{"label": "table"}], elapsed=1.2),
            PlatformPage(error="HTTP 500: 平台不可用"),
        ]

    monkeypatch.setattr(platform_ocr, "ocr_images", fake_ocr_images)
    pages = mapper([Path("p1.jpg"), Path("p2.jpg")], None)
    assert [p.page_no for p in pages] == [1, 2]
    assert pages[0].text == "页一"
    assert pages[0].source == "platform"
    assert pages[0].confidence is None
    assert pages[0].blocks == [{"label": "table"}]
    assert pages[1].error == "HTTP 500: 平台不可用"
    assert pages[1].confidence is None


def test_ocr_images_empty_input_does_not_call_platform() -> None:
    """挡的是：空输入也去发一次请求。

    空列表必须在组装 payload 之前就返回（`ocr_images([])` 全程不碰 settings/网络），
    顺带钉住手册上限：单次请求 ≤16 页。
    """
    assert platform_ocr.ocr_images([]) == []
    assert MAX_BATCH_PAGES == 16
