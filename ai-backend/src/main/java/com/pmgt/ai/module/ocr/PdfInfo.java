package com.pmgt.ai.module.ocr;

/**
 * PDF 基本信息（页数 + 文本层字符数）。
 *
 * <p>它是"文本型 PDF 还是扫描件"的判据来源：平均每页字符数低于阈值即视为扫描件。
 * 为什么不用"有没有图片"判断：带文本层的扫描件（OCR 过的）很常见，按字符数判断更稳。
 */
public record PdfInfo(java.nio.file.Path path, int pageCount, int textChars, String error) {

    public static PdfInfo error(java.nio.file.Path path, String error) {
        return new PdfInfo(path, 0, 0, error);
    }

    public double charsPerPage() {
        return pageCount > 0 ? (double) textChars / pageCount : 0.0;
    }

    public boolean isScanned(int threshold) {
        return error == null && pageCount > 0 && charsPerPage() < threshold;
    }

    /** {@code text_pdf} / {@code scanned} / {@code error} */
    public String kind(int threshold) {
        if (error != null) {
            return "error";
        }
        return isScanned(threshold) ? "scanned" : "text_pdf";
    }
}
