package com.pmgt.ai.module.ocr;

import com.pmgt.ai.common.util.ProgressFn;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF 读取与渲染（本模块对应 Python 的 {@code pm_ai/pdf_utils.py}，PyMuPDF → PDFBox 3.0.3）。
 *
 * <p>Python 侧只用到了 PyMuPDF 的 4 个能力（开文档、页数、逐页文本、按 DPI 渲染），
 * 这里逐个对应过去，**不引入其它 PDF 库**：
 * <table border="1">
 *   <caption>能力对照</caption>
 *   <tr><th>Python（PyMuPDF）</th><th>Java（PDFBox 3）</th></tr>
 *   <tr><td>{@code fitz.open} + {@code doc.page_count} + {@code len(page.get_text())}</td>
 *       <td>{@link Loader#loadPDF(java.io.File)} + {@link PDDocument#getNumberOfPages()} + {@link PDFTextStripper}</td></tr>
 *   <tr><td>{@code "\n".join(page.get_text() for page in doc)}</td>
 *       <td>{@link #extractText(Path)}（= 逐页文本用 {@code "\n"} 拼接，与 Python 完全同构）</td></tr>
 *   <tr><td>逐页 {@code page.get_text()}</td>
 *       <td>{@link #extractPageTexts(Path)}（{@code setStartPage/setEndPage} 逐页取）</td></tr>
 *   <tr><td>{@code page.get_pixmap(dpi=...).tobytes("jpeg", jpg_quality=q)}</td>
 *       <td>{@link #renderPages(Path, int, Path, String, int, ProgressFn)}（{@code renderImageWithDPI} + ImageIO）</td></tr>
 * </table>
 *
 * <h2>必须保留的实测结论（Python 注释里的原始记录）</h2>
 *
 * <p><b>① 发给平台 OCR 的图是 {@code 150 DPI + JPEG q85}。</b>
 * 实测 120~300 DPI 的识别结果**完全一致**（PaddleOCR-VL 内部会把图下采样到 ≈100 万像素），
 * 但 300 DPI PNG 每页 **6.7MB**、150 DPI JPEG 每页 **396KB**——**差 17 倍**，
 * 白等的是上传时间。所以默认参数在 {@code AiSettings.Ocr}（platformDpi / platformFormat / platformQuality），
 * 本类只负责按调用方给的 dpi/format/quality 如实渲染，**不替调用方改参数**。
 *
 * <p><b>② PyMuPDF 的 {@code get_text()} 与 PDFBox 的抽取结果可能有细微差异</b>
 * （空格、换行、连字/ligature 的展开方式不同：PDFBox 走字体的 ToUnicode/CMap，
 * PyMuPDF 另有自己的启发式）。这件事**有实际后果**：扫描件判定靠"平均每页字符数 &lt; {@code scannedCharThreshold}"
 * （默认 50），文本层稀疏的电子版 PDF 可能因为几处空白差异在两边落到不同分支。
 * 因此：迁移后要用同一批真实样本对比两侧的 {@code textChars}，阈值按 Java 侧的实测值重新标定，
 * **不要假设两边逐字节相同**。对比办法：
 * <pre>{@code
 * // Java 侧：打印逐页字符数与首 120 字
 * System.out.println(new PdfReader().debugSample(Path.of("样本.pdf")));
 *
 * // Python 侧（同一文件，逐页比对；差异应只出现在空白/连字这类排版细节上）
 * // python -c "import fitz,sys; d=fitz.open(sys.argv[1]); \
 * //   [print(f'--- page {i+1}: chars={len(p.get_text())}\n{p.get_text()[:120]}') for i,p in enumerate(d)]" 样本.pdf
 * }</pre>
 * {@link #read(Path)} 返回的 {@code textChars} 与 {@link #debugSample(Path)} 的合计值一致，就是为了让这件事可人工核对。
 *
 * <p><b>③ 加密/损坏/非 PDF 一律不抛异常</b>（{@link #read(Path)}）：Python 版把 {@code fitz.open} 的异常
 * 收进 {@code PdfInfo.error}，因为调用方靠 {@code error} 字段分流（而不是靠 try/catch），
 * 换语言后这条契约必须保持。加密 PDF 在 PDFBox 下会抛 {@link InvalidPasswordException}（空口令也打不开），
 * 与 PyMuPDF 需要口令的行为一致；错误信息里会额外给一句人话提示。
 *
 * <p><b>④ 重渲染同一个目录时必须先删旧文件</b>（迁移中新踩出来的，见 {@link #renderPages} 与
 * {@code writeImage} 的注释）：{@code ImageIO.createImageOutputStream(File)} **不截断**已存在的文件，
 * 新图比旧图小时会在尾部残留旧字节 —— 后缀是 .jpg、内容已损坏。Python 的 {@code pix.save()} 是覆盖写，
 * 所以这条差异只有 Java 侧才有，必须靠 {@code Files.deleteIfExists} 补上。
 */
@Component
public class PdfReader {

    private static final Logger log = LoggerFactory.getLogger(PdfReader.class);

    /** 文件名格式：{@code page_0001.jpg}（固定 4 位，页序即列表序，检索的来源标注依赖它）。 */
    private static final String PAGE_FILE_FORMAT = "page_%04d.%s";

    /**
     * 读取 PDF 基本信息（页数 + 文本层字符数）。
     *
     * <p>口径与 Python {@code read_pdf} 完全一致：{@code textChars} = **逐页**文本长度之和
     * （不是整篇一次性抽取的长度——那样会多算每页之间的分隔符）。
     * 损坏 / 加密 / 非 PDF 时返回 {@code PdfInfo.error(path, msg)}，**不抛异常**。
     */
    public PdfInfo read(Path path) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            int pages = doc.getNumberOfPages();
            int chars = 0;
            for (int i = 1; i <= pages; i++) {
                chars += stripPage(doc, i).length();
            }
            return new PdfInfo(path, pages, chars, null);
        } catch (Exception e) {
            // 调用方靠 error 字段分流（text_pdf / scanned / error），所以这里必须吞掉所有异常并如实上报。
            log.debug("PDF 读取失败：{}（{}）", path, e.toString());
            return PdfInfo.error(path, errorMessage(e));
        }
    }

    /**
     * 提取 PDF 全文本（扫描件会返回空或极少内容）。
     *
     * <p>Python 是 {@code "\n".join(page.get_text() for page in doc)}，这里同构实现，
     * 顺带保证"逐页 = 全篇"两边一致（{@link #extractPageTexts(Path)} 是同一套取文本逻辑）。
     *
     * <p>与 Python 一样：**失败直接抛**（调用方在抽取前已用 {@link #read(Path)} 探过路）。
     */
    public String extractText(Path path) {
        return String.join("\n", extractPageTexts(path));
    }

    /**
     * **逐页**提取文本，返回每页文本的列表（index 0 = 第 1 页）。
     *
     * <p>为什么必须逐页：模型要标注"信息来自第几页"（{@code [P2]}），就必须让它看到分页边界；
     * 给一整坨扁平文本，模型无从判断页码，只能编造或放弃标注。
     *
     * <p>实现用 {@link PDFTextStripper} 的 {@code setStartPage/setEndPage} 逐页取，
     * 每页一个独立的 stripper 实例——**页码与页序严格一一对应**，不共享可变状态，也不做任何排序/合并。
     */
    public List<String> extractPageTexts(Path path) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            int pages = doc.getNumberOfPages();
            List<String> out = new ArrayList<>(pages);
            for (int i = 1; i <= pages; i++) {
                out.add(stripPage(doc, i));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 文本抽取失败：" + errorMessage(e), e);
        }
    }

    /**
     * 把 PDF 每页渲染成图片，返回图片路径列表（**顺序 = 页序**）。
     *
     * <p>平台 OCR（PaddleOCR-VL）用 {@code 150 DPI + jpeg/q85}，理由见类注释①。
     * 文件名固定为 {@code page_0001.jpg}，进度阶段名 {@code render}（与 Python 的
     * {@code on_progress(已渲染页数, 总页数)} 对齐），上传任务的状态标签按它查表。
     *
     * @param path     PDF 路径
     * @param dpi      渲染 DPI（平台路径传 {@code ai.ocr.platform-dpi}，默认 150）
     * @param outDir   输出目录；传 {@code null} 时写到 {@code <PDF同级>/<PDF名>_pages/}（与 Python 相同）
     * @param fmt      {@code jpeg}/{@code jpg}/{@code png}（其它值按 png 处理，与 Python 相同）
     * @param quality  JPEG 压缩质量 1~100（越界按平台默认 85 处理；只对 jpeg 生效，png 忽略，与 Python 相同）
     * @param progress 进度回调，可为 {@code null}
     */
    public List<Path> renderPages(Path path, int dpi, Path outDir, String fmt, int quality, ProgressFn progress)
            throws IOException {
        Path target = outDir != null ? outDir : path.resolveSibling(stem(path) + "_pages");
        Files.createDirectories(target);

        String ext = isJpeg(fmt) ? "jpg" : "png";
        ProgressFn onProgress = progress == null ? ProgressFn.NONE : progress;
        List<Path> out = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            int total = doc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < total; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                // 固定 Locale.ROOT：默认区域设置下 %d 可能给出本地化数字，文件名必须稳定。
                Path file = target.resolve(String.format(Locale.ROOT, PAGE_FILE_FORMAT, i + 1, ext));
                writeImage(image, file, ext, quality);
                image.flush();
                out.add(file);
                onProgress.on("render", i + 1, total);
            }
        }
        return out;
    }

    /**
     * 人工对比用的可读快照：逐页字符数 + 首 120 字预览 + 合计。
     *
     * <p>专为类注释②那件事准备——PyMuPDF 与 PDFBox 的文本抽取存在细微差异（空格/换行/连字），
     * 这会影响 {@code scannedCharThreshold} 的判定。把两侧同一份样本的输出并排看一眼，
     * 就知道阈值要不要按 Java 侧重新标定。
     */
    public String debugSample(Path path) {
        StringBuilder sb = new StringBuilder();
        try {
            List<String> pages = extractPageTexts(path);
            int chars = 0;
            for (int i = 0; i < pages.size(); i++) {
                String text = pages.get(i);
                chars += text.length();
                sb.append(String.format(Locale.ROOT, "--- page %d: chars=%d%n", i + 1, text.length()));
                sb.append(text.length() > 120 ? text.substring(0, 120) : text).append('\n');
            }
            double perPage = pages.isEmpty() ? 0.0 : (double) chars / pages.size();
            sb.append(String.format(Locale.ROOT, "pages=%d textChars=%d charsPerPage=%.1f%n", pages.size(), chars, perPage));
        } catch (RuntimeException e) {
            sb.append("ERROR ").append(errorMessage(e)).append('\n');
        }
        return sb.toString();
    }

    // ── 内部实现 ────────────────────────────────────────────────────

    /** 取单页文本：1 基页码，与 Python 的 {@code page.get_text()} 一一对应。 */
    private static String stripPage(PDDocument doc, int pageNo) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        return stripper.getText(doc);
    }

    private static void writeImage(BufferedImage image, Path file, String ext, int quality) throws IOException {
        // ⚠️ 实测踩过的坑：{@code ImageIO.createImageOutputStream(File)} **不会截断已存在的文件**。
        // 往同一个渲染目录重跑一次（重试上传、上一次留下的 xxx_pages/）时，如果新图比旧图小，
        // 文件尾部会残留旧数据的字节 —— 后缀是 .jpg 但内容已损坏，而平台只会回一句"图片读不了"。
        // 表现：把 4 个不同样本都渲染到同一个路径，体积**四次完全相同**（都是第一个较大文件的长度）。
        // Python 的 {@code pix.save()} / {@code write_bytes()} 是覆盖写（内部截断），所以这里必须自己删。
        Files.deleteIfExists(file);

        if (!"jpg".equals(ext)) {
            // ImageIO.write(..., File) 内部自己会先 delete，PNG 这条路本来就没有残留问题。
            if (!ImageIO.write(image, "png", file.toFile())) {
                throw new IOException("没有可用的 PNG 编码器，无法写出 " + file);
            }
            return;
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            // JPEG 质量必须显式设置：ImageIO 默认约 0.75，比 Python 侧的 q85 更糊，
            // 而这里发的图直接决定平台 OCR 的识别质量，不能让它走默认值。
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(clampQuality(quality) / 100f);
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(file.toFile())) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            }
        } finally {
            writer.dispose();
        }
    }

    /** 质量只接受 1~100；越界（含 0/未配置）一律退回平台默认的 85。 */
    private static int clampQuality(int quality) {
        return quality >= 1 && quality <= 100 ? quality : 85;
    }

    private static boolean isJpeg(String fmt) {
        String f = fmt == null ? "" : fmt.trim().toLowerCase();
        return "jpg".equals(f) || "jpeg".equals(f);
    }

    /** Python 的 {@code Path.stem}：去掉最后一段扩展名（{@code a.b.pdf} → {@code a.b}）。 */
    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 错误信息保留异常类名（Python 的 {@code str(exc)} 也带类型），加密件额外给人话提示。 */
    private static String errorMessage(Throwable e) {
        String base = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (e instanceof InvalidPasswordException) {
            return base + "（PDF 已加密：无口令打不开，需要先解密再解析）";
        }
        return base;
    }
}
