package com.pmgt.ai.module.ocr;

import java.util.List;

/**
 * 平台 OCR 的**单页**结果。
 *
 * <p>⚠️ 两个必须保留的事实（Python 版实测）：
 * <ol>
 *   <li>批量请求时 blocks 在 {@code results[i]} 里、**不在顶层**；单张请求才在顶层；</li>
 *   <li>平台**不返回置信度**，所以这里根本没有 confidence 字段——不许编一个出来。</li>
 * </ol>
 */
public record PlatformPage(String markdown, List<Block> blocks, double elapsed, String error) {

    /** 版面块：{@code label} 用来数表格/印章（印章块内容为空，等于免费的"这页有没有章"探测）。 */
    public record Block(String label, String text, double[] bbox) {
    }

    public boolean ok() {
        return error == null || error.isBlank();
    }

    public long sealCount() {
        return countLabel("seal");
    }

    public long tableCount() {
        return countLabel("table");
    }

    public long countLabel(String label) {
        return blocks == null ? 0 : blocks.stream().filter(b -> label.equalsIgnoreCase(b.label())).count();
    }
}
