package com.pmgt.ai.module.ocr;

import com.pmgt.ai.common.util.ProgressFn;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfReader} 的行为测试（**离线**：PDF 现造，不依赖 work/samples 里的样本）。
 *
 * <p>覆盖三件容易出错的事：
 * <ol>
 *   <li><b>页序</b>：逐页文本与渲染文件名必须与页序严格对应（检索的来源标注靠它）；</li>
 *   <li><b>不抛异常</b>：损坏/非 PDF 走 {@code PdfInfo.error} 分流；</li>
 *   <li><b>重渲染要截断</b>：这是迁移中新踩出来的坑——
 *       {@code ImageIO.createImageOutputStream(File)} 不截断已存在的文件，
 *       往同一个 {@code *_pages/} 目录重跑一次会残留旧字节（后缀 .jpg、内容损坏）。
 *       顶层探针的表现是"4 个不同样本渲染出来体积完全相同"，这里用"第二次渲染更小"钉死它。</li>
 * </ol>
 */
class PdfReaderTest {

    private final PdfReader reader = new PdfReader();

    @Test
    @DisplayName("页数/字符数/逐页文本：页码与页序严格对应，全篇 = 逐页用 \\n 拼接")
    void readAndExtractKeepPageOrder(@TempDir Path dir) throws IOException {
        // 每页给足 50 字符以上（scannedCharThreshold 默认 50），否则测试样本自己会被判成扫描件。
        Path pdf = textPdf(dir, "two-pages.pdf",
                "ALPHA-PAGE-ONE " + "0123456789".repeat(6),
                "BRAVO-PAGE-TWO " + "0123456789".repeat(6));

        PdfInfo info = reader.read(pdf);
        assertThat(info.error()).isNull();
        assertThat(info.pageCount()).isEqualTo(2);
        assertThat(info.textChars()).isPositive();
        assertThat(info.kind(50)).isEqualTo("text_pdf");
        assertThat(info.isScanned(50)).isFalse();

        List<String> pages = reader.extractPageTexts(pdf);
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).contains("ALPHA-PAGE-ONE").doesNotContain("BRAVO-PAGE-TWO");
        assertThat(pages.get(1)).contains("BRAVO-PAGE-TWO").doesNotContain("ALPHA-PAGE-ONE");

        // Python 是 "\n".join(page.get_text() ...)，这里同构（textChars 也是按逐页长度累加的）
        assertThat(reader.extractText(pdf)).isEqualTo(String.join("\n", pages));
        assertThat(info.textChars()).isEqualTo(pages.stream().mapToInt(String::length).sum());
    }

    @Test
    @DisplayName("没有文本层的页按扫描件判定（阈值 50）")
    void blankPageIsScanned(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("blank.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));   // 有页面、无文本层 = 扫描件的本质
            doc.save(pdf.toFile());
        }

        PdfInfo info = reader.read(pdf);
        assertThat(info.error()).isNull();
        assertThat(info.pageCount()).isEqualTo(1);
        assertThat(info.charsPerPage()).isLessThan(50);
        assertThat(info.kind(50)).isEqualTo("scanned");
    }

    @Test
    @DisplayName("损坏/非 PDF/文件不存在：返回 PdfInfo.error，不抛异常")
    void brokenInputReturnsErrorWithoutThrowing(@TempDir Path dir) throws IOException {
        Path notPdf = dir.resolve("not-a-pdf.pdf");
        Files.writeString(notPdf, "这不是 PDF，只是一段文本", StandardCharsets.UTF_8);

        PdfInfo broken = reader.read(notPdf);
        assertThat(broken.error()).isNotNull().isNotBlank();
        assertThat(broken.pageCount()).isZero();
        assertThat(broken.textChars()).isZero();
        assertThat(broken.kind(50)).isEqualTo("error");
        assertThat(broken.charsPerPage()).isZero();

        PdfInfo missing = reader.read(dir.resolve("根本不存在.pdf"));
        assertThat(missing.error()).isNotNull();
        assertThat(missing.kind(50)).isEqualTo("error");
    }

    @Test
    @DisplayName("渲染：文件名 page_0001.jpg…、顺序=页序、写出来的图能被读回")
    void renderPagesWritesSequentialReadableImages(@TempDir Path dir) throws IOException {
        Path pdf = textPdf(dir, "render.pdf", "PAGE-ONE", "PAGE-TWO");
        Path out = dir.resolve("img");

        List<Path> images = reader.renderPages(pdf, 150, out, "jpeg", 85, ProgressFn.NONE);

        assertThat(images).extracting(p -> p.getFileName().toString())
                .containsExactly("page_0001.jpg", "page_0002.jpg");
        for (Path image : images) {
            assertThat(image).exists();
            BufferedImage read = ImageIO.read(image.toFile());
            assertThat(read).as("渲染出来的图必须能被重新读回：%s", image).isNotNull();
            assertThat(read.getWidth()).isGreaterThan(1000);   // A4 @150dpi ≈ 1240 × 1754
        }
    }

    @Test
    @DisplayName("jpeg 质量参数真的生效（q95 必须比 q40 大），png 走自己的扩展名")
    void jpegQualityAndFormatAreHonoured(@TempDir Path dir) throws IOException {
        Path pdf = textPdf(dir, "quality.pdf", "QUALITY-CHECK-0123456789");

        Path low = reader.renderPages(pdf, 72, dir.resolve("q40"), "jpeg", 40, ProgressFn.NONE).get(0);
        Path high = reader.renderPages(pdf, 72, dir.resolve("q95"), "jpeg", 95, ProgressFn.NONE).get(0);
        assertThat(Files.size(high)).isGreaterThan(Files.size(low));

        Path png = reader.renderPages(pdf, 72, dir.resolve("png"), "png", 85, ProgressFn.NONE).get(0);
        assertThat(png.getFileName().toString()).isEqualTo("page_0001.png");

        // outDir 传 null 时写到 <PDF同级>/<PDF名>_pages/（与 Python 相同）
        List<Path> defaultDir = reader.renderPages(pdf, 72, null, "jpeg", 85, ProgressFn.NONE);
        assertThat(defaultDir.get(0).getParent().getFileName().toString()).isEqualTo("quality_pages");
    }

    @Test
    @DisplayName("重渲染到同一目录必须截断旧文件（否则残留字节 = 损坏的 jpg）")
    void rerenderTruncatesPreviousLongerFile(@TempDir Path dir) throws IOException {
        Path pdf = textPdf(dir, "rerender.pdf", "SAME-FILE-DIFFERENT-SIZE-0123456789");
        Path out = dir.resolve("same-dir");

        Path big = reader.renderPages(pdf, 300, out, "jpeg", 85, ProgressFn.NONE).get(0);
        long bigSize = Files.size(big);

        // 同一个路径再渲染一次，DPI 更小 → 新内容更短。若 ImageIO 不截断，文件长度会停在 bigSize。
        Path small = reader.renderPages(pdf, 30, out, "jpeg", 85, ProgressFn.NONE).get(0);
        long smallSize = Files.size(small);

        assertThat(small).isEqualTo(big);
        assertThat(smallSize).as("新图更小 → 文件必须真的变短（ImageIO 不会自己截断）").isLessThan(bigSize);
        BufferedImage read = ImageIO.read(small.toFile());
        assertThat(read).isNotNull();
        assertThat(read.getWidth()).isLessThan(400);   // A4 @30dpi ≈ 248 宽
    }

    @Test
    @DisplayName("进度回调按 (已渲染, 总数) 逐页上报，阶段名为 render")
    void progressReportsPerPage(@TempDir Path dir) throws IOException {
        Path pdf = textPdf(dir, "progress.pdf", "ONE", "TWO", "THREE");
        List<String> calls = new ArrayList<>();

        reader.renderPages(pdf, 72, dir.resolve("progress"), "jpeg", 85,
                (stage, done, total) -> calls.add(stage + ":" + done + "/" + total));

        assertThat(calls).containsExactly("render:1/3", "render:2/3", "render:3/3");
    }

    // ── 造样本 ──────────────────────────────────────────────────────

    /** 造一个"文本型 PDF"：每页一行 ASCII 文本（标准 14 字体不带中文字形，测试数据只用 ASCII）。 */
    private static Path textPdf(Path dir, String name, String... pageTexts) throws IOException {
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(60, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * 挡的是：换行符被"顺手改回" {@code \r\n}。
     *
     * <p>PDFBox 默认给 {@code \r\n}、PyMuPDF 给 {@code \n}——实测同一份 9 行的电子版 PDF 两边字符数
     * 正好差 9（191 vs 182），空白页则是 {@code "\r\n"} vs {@code ""}（1 vs 0）。
     * 归一之后 Java 与 Python 的输出**逐字节可比**（四份样本实测：182==182、扫描件 0==0），
     * 也避免 {@code \r} 混进喂给模型的提示词与检索切片里。
     */
    @Test
    @DisplayName("换行归一：\\r\\n / \\r → \\n，且 null / 空串安全")
    void normalizesNewlines() {
        assertThat(PdfReader.normalizeNewlines("a\r\nb\r\n")).isEqualTo("a\nb\n");
        assertThat(PdfReader.normalizeNewlines("a\rb")).isEqualTo("a\nb");
        assertThat(PdfReader.normalizeNewlines("a\nb")).isEqualTo("a\nb");
        assertThat(PdfReader.normalizeNewlines("")).isEmpty();
        assertThat(PdfReader.normalizeNewlines(null)).isEmpty();
    }
}
