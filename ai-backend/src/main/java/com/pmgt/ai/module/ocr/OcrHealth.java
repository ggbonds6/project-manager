package com.pmgt.ai.module.ocr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台 OCR 的连通性探测结果（{@code GET {gateway}/ocr/health}）。
 *
 * <p>字段集与 Python 版 {@code platform_ocr.OcrHealth} 里被 {@code api.py} 读取的五个**完全一致**：
 * {@code ok} / {@code workers} / {@code idle} / {@code options} / {@code detail}——
 * {@code /health?with_ocr=true} 的 {@code ocr} 块就是这五个字段，主系统/前端按老契约读，
 * 换语言不该让调用方改代码。
 *
 * <p>Python 版的 {@code OcrHealth} 还有一个内部用的 {@code ts}（探测时间戳）。
 * 它**不进这个值对象**：那只是 TTL 缓存的实现细节，不是对外字段
 * （缓存时间戳由 {@link PlatformOcrClient} 自己持有，不会漏到 HTTP 响应里）。
 *
 * @param ok      平台是否可用（Python：{@code status == "ok"}）
 * @param workers 常驻工作进程数
 * @param idle    空闲工作进程数（为 0 说明请求在排队）
 * @param options 服务端当前支持的可调选项清单（seal / ocr_min_pixels / …）
 * @param detail  人类可读的一句话；失败时直接写"下一步该查什么"
 */
public record OcrHealth(boolean ok, int workers, int idle, List<String> options, String detail) {

    public OcrHealth {
        options = options == null ? List.of() : List.copyOf(options);
        detail = detail == null ? "" : detail;
    }

    /** 探测失败的统一构造：字段全 0/空，只留一句话。 */
    public static OcrHealth down(String detail) {
        return new OcrHealth(false, 0, 0, List.of(), detail);
    }

    /** {@code /health} 里 {@code ocr} 字段的形状（与 {@code api.py} 逐字段对齐）。 */
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("workers", workers);
        out.put("idle", idle);
        out.put("options", options);
        out.put("detail", detail);
        return out;
    }
}
