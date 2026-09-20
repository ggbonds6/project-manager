package com.pmgt.ai.module.doc;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.module.check.Checks;
import com.pmgt.ai.module.ocr.PdfInfo;
import com.pmgt.ai.module.ocr.PdfReader;
import com.pmgt.ai.module.ocr.PlatformOcrClient;
import com.pmgt.ai.module.ocr.PlatformPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文档 → 结构化文本（<b>带页码、页内区域、识别来源、质量信号</b>）。
 *
 * <p>{@code ai-service/src/pm_ai/document.py} 的 Java 移植，流程与口径逐条对齐：
 * <ol>
 *   <li><b>文本型 PDF</b>：逐页取文本层（{@code source="text-layer"}，最快最准、不消耗 OCR 算力）
 *       ——<b>但仍然要跑确定性校验</b>（校验层才是"可信"的那一层，跟走不走 OCR 无关）；</li>
 *   <li><b>扫描件 / {@code forceOcr}</b>：渲染成图 → <b>平台 OCR</b>（PaddleOCR-VL，
 *       {@code source="platform"}）；</li>
 *   <li><b>图片</b>（png/jpg/jpeg/bmp/tif/tiff/webp）：直接走平台 OCR，{@code kind="image"}；</li>
 *   <li><b>其它格式</b>：{@code kind="error"} + 文案（对齐 Python）。</li>
 * </ol>
 *
 * <h2>为什么按页切分并带页码</h2>
 * <p>提示词要求"每条信息标注来源页码"。如果喂给模型的是一坨扁平文本，模型根本不知道某句话在第几页
 * ——只能编造页码，或者干脆不标。<b>按页切分并显式写「第 N 页」</b>，来源标注才可能真实可信；
 * 这也是"可核验"的基础：审计场景里一条无法回溯到原文的结论等于没有价值。
 *
 * <h2>置信度：三层，<b>不要混成一个数字</b></h2>
 * <table border="1">
 *   <caption>三层置信度</caption>
 *   <tr><th>层</th><th>来源</th><th>性质</th></tr>
 *   <tr><td>识别层</td><td><b>已消失</b>：本地引擎逐页分，平台<b>不返回</b>置信度</td><td>机器分，实测<b>不可靠</b></td></tr>
 *   <tr><td>校验层</td><td>{@link Checks} 的确定性检查（大小写金额互校等）</td><td><b>可复现</b>，这是最硬的一层</td></tr>
 *   <tr><td>理解层</td><td>模型自评 0–1</td><td>主观，只用来排序复核优先级</td></tr>
 * </table>
 * <p>⚠️ 实测教训（保留，因为它解释了为什么"没有识别置信度"不算损失）：
 * 本地引擎曾报出 OCR 平均置信度 0.97，同一份文件里的金额仍被识别错（千分位逗号→小数点）。
 * <b>识别置信度高 ≠ 内容正确</b>。所以平台不返回置信度并不可惜，{@code confidence} 只是为保持
 * API 形状而留着（恒为 {@code null}）。
 *
 * <h2>本地 OCR 兜底已移除（2026-09-18，行为不回头）</h2>
 * <p>本地引擎识别出来的金额/编号不可用，"悄悄换一个差引擎"比"明确失败"更危险——使用者会以为结果
 * 来自平台，从而不再复核。故<b>失败页不给兜底</b>：保留 {@code error}，并在 {@code notes} 里显式提示
 * 人工复核，<b>不降级到别的引擎</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentReader {

    /** 直接走平台 OCR 的图片后缀（Python 侧 {@code pdf_utils.IMAGE_EXTS} 带点，这里照抄，别去掉点）。 */
    private static final Set<String> IMAGE_EXTS =
            Set.of(".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp");

    /** 阶段名（{@code render}/{@code ocr}/{@code check}）与 Python 一致：上传任务的进度权重按它查表。 */
    private static final String STAGE_RENDER = "render";
    private static final String STAGE_OCR = "ocr";
    private static final String STAGE_CHECK = "check";

    private static final String ERR_PDF = "PDF 解析失败：";
    private static final String ERR_UNSUPPORTED = "不支持的格式：";

    private final PdfReader pdfReader;
    private final PlatformOcrClient platformOcrClient;
    private final AiSettings settings;

    /**
     * 读文档 → 结构化文本，自动区分文本型 / 扫描件。
     *
     * <p>本签名被 controller 与上传任务层依赖，<b>不要改</b>。
     *
     * @param path       待解析文件
     * @param dpi        仅用于排障 / 印章"两遍法"——覆盖平台默认渲染 DPI（默认 150）。
     *                   ⚠️ <b>提高 DPI 不会改善正文识别</b>（VL 模型内部下采样到约 100 万像素，
     *                   实测 120~300 DPI 结果完全一致），只在上传体积和耗时上付出代价；
     *                   它真正的用途是低 DPI 下印章文字会被"编造"（实测读出过完全不相干的银行名），
     *                   需要读章时用高 DPI 原图重跑该页。{@code null} 或 0 表示用配置默认值。
     * @param forceOcr   文本型 PDF 也强制走 OCR（用于对比文本层与 OCR 的差异）
     * @param onProgress 进度回调，阶段名 {@code render}/{@code ocr}/{@code check}；可为 {@code null}
     */
    public DocumentText read(Path path, Integer dpi, boolean forceOcr, ProgressFn onProgress) {
        ProgressFn progress = onProgress == null ? ProgressFn.NONE : onProgress;
        String suffix = suffix(path);   // 小写、带点，对齐 Python 的 Path.suffix.lower()

        if (".pdf".equals(suffix)) {
            PdfInfo info = pdfInfo(path);
            if (info.error() != null && !info.error().isBlank()) {
                return errorDocument(ERR_PDF + info.error());
            }
            // 分流阈值：平均每页字符数 < settings.ocr.scannedCharThreshold（默认 50 字/页）即视为扫描件
            if (!info.isScanned(settings.getOcr().getScannedCharThreshold()) && !forceOcr) {
                return textLayerDocument(path, progress);
            }
            return ocrPdf(path, dpi, progress);
        }
        if (IMAGE_EXTS.contains(suffix)) {
            return ocrImage(path, progress);
        }
        return errorDocument(ERR_UNSUPPORTED + suffix);
    }

    // ── 文本型 PDF：取文本层，但<b>照样跑确定性校验</b> ────────────────

    private DocumentText textLayerDocument(Path path, ProgressFn progress) {
        long t0 = System.nanoTime();
        List<String> texts = pageTexts(path);
        List<PageText> pages = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            PageText page = new PageText(i + 1, texts.get(i));
            page.setSource("text-layer");
            pages.add(page);
        }
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        doc.setPages(pages);
        doc.setEngine("text-layer");
        doc.setProvider("text-layer");
        // 校验层与识别方式无关：文本层也必须过一遍，否则"金额认错"在电子版上照样漏
        attachChecks(doc, progress);
        doc.setElapsed(seconds(t0));
        return doc;
    }

    // ── 扫描件：渲染 → 平台 OCR ─────────────────────────────────────

    /**
     * 扫描件：渲染每页 → 平台识别（批量并发），保留页号与质量信号。
     *
     * <p>渲染图必须落到 {@code work/} 下（输入目录在容器里是只读挂载），
     * 且<b>用完必须删除</b>——Python 用 {@code try/finally}，这里同样用 {@code finally}
     * 兜住"渲染/识别中途抛异常"的路径，不留垃圾目录。
     */
    private DocumentText ocrPdf(Path path, Integer dpi, ProgressFn progress) {
        long started = System.nanoTime();
        Path outDir = settings.getWorkDir()
                .resolve("pages")
                .resolve(UUID.randomUUID().toString().replace("-", ""));
        Map<String, Double> stages = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        DocumentText doc = new DocumentText();
        try {
            try {
                Files.createDirectories(outDir);
            } catch (IOException e) {
                throw new IllegalStateException("渲染中间目录创建失败：" + outDir, e);
            }
            // dpi 只影响上传体积/耗时与印章可读性，不改善正文识别（见 read 的 @param dpi）
            int useDpi = dpi != null && dpi > 0 ? dpi : settings.getOcr().getPlatformDpi();
            String fmt = settings.getOcr().getPlatformFormat();

            long t0 = System.nanoTime();
            List<Path> images = renderPages(path, useDpi, outDir, fmt, progress);
            stages.put(STAGE_RENDER, seconds(t0));

            t0 = System.nanoTime();
            List<PageText> pages = recognize(images, progress, notes);
            stages.put(STAGE_OCR, seconds(t0));

            doc.setKind("scanned");
            doc.setPages(pages);
            doc.setEngine("platform");
            doc.setProvider("platform");
            doc.setDpi(useDpi);
            doc.setImageFormat(fmt);
            doc.setStages(stages);
            doc.setNotes(notes);
        } finally {
            // 中间图不留给下一轮（Python: shutil.rmtree(out_dir, ignore_errors=True)）
            deleteQuietly(outDir);
        }

        attachChecks(doc, progress);
        doc.setElapsed(seconds(started));
        return doc;
    }

    /** 单张图片（线上图片附件常见）：不渲染、不判扫描，直接平台识别。 */
    private DocumentText ocrImage(Path path, ProgressFn progress) {
        long started = System.nanoTime();
        List<String> notes = new ArrayList<>();
        List<PageText> pages = recognize(List.of(path), progress, notes);

        DocumentText doc = new DocumentText();
        doc.setKind("image");
        doc.setPages(pages);
        doc.setEngine("platform");
        doc.setProvider("platform");
        doc.setImageFormat(rawSuffix(path));   // Python: path.suffix.lstrip(".")（保持原大小写）
        doc.setNotes(notes);
        attachChecks(doc, progress);
        doc.setElapsed(seconds(started));
        return doc;
    }

    /**
     * 识别全部页面——<b>只走平台 OCR，没有兜底</b>。
     *
     * <p>本地 RapidOCR/PaddleOCR 兜底已于 2026-09-18 移除：本地引擎识别出来的金额/编号不可用，
     * "悄悄换一个差引擎"比"明确失败"更危险——使用者会以为结果来自平台，从而不再复核。
     * 故失败页<b>保留 {@code error}</b>，由上层（任务列表 / 接口返回值 / {@code notes}）显式提示人工复核，
     * <b>不降级到别的引擎</b>。
     */
    private List<PageText> recognize(List<Path> images, ProgressFn progress, List<String> notes) {
        List<PageText> pages = platformPages(images, progress);
        List<Integer> failed = new ArrayList<>();
        for (PageText page : pages) {
            if (page.getError() != null && !page.getError().isBlank()) {
                failed.add(page.getPageNo());
            }
        }
        if (!failed.isEmpty()) {
            // Python 原文："本地 OCR 兜底已于 2026-09-18 移除，这些页**没有内容**…"；
            // 这里去掉日期与 markdown 强调记号（notes 会原样进 JSON/前端），语义不变。
            notes.add(failed.size() + " 页平台识别失败（第 " + joinPageNumbers(failed)
                    + " 页）：本地 OCR 兜底已移除，这些页没有内容，需人工复核或稍后重跑");
        }
        return pages;
    }

    private List<PageText> platformPages(List<Path> images, ProgressFn progress) {
        List<PlatformPage> raw;
        try {
            // 进度阶段名由本层统一映射为 "ocr"（Python: lambda d, t: on_progress("ocr", d, t)）
            raw = platformOcrClient.recognize(images, (stage, done, total) -> progress.on(STAGE_OCR, done, total));
        } catch (Exception e) {
            throw rethrow("平台 OCR 调用失败：" + describe(images), e);
        }
        List<PageText> pages = new ArrayList<>();
        if (raw == null) {
            return pages;
        }
        for (int i = 0; i < raw.size(); i++) {
            PlatformPage r = raw.get(i);
            PageText page = new PageText(i + 1, r.markdown());
            page.setSource("platform");
            page.setBlocks(r.blocks());
            page.setElapsed(r.elapsed());
            page.setError(r.error());
            // confidence 恒为 null：平台不返回置信度，不许编一个出来
            pages.add(page);
        }
        return pages;
    }

    // ── 校验层 ──────────────────────────────────────────────────────

    /** 对逐页文本跑确定性校验，并把 {@code check} 阶段耗时写进 {@code stages}。 */
    private void attachChecks(DocumentText doc, ProgressFn progress) {
        progress.on(STAGE_CHECK, 0, 1);
        long t0 = System.nanoTime();
        doc.setChecks(Checks.checkDocument(doc.getPages().stream().map(PageText::getText).toList()));
        doc.getStages().put(STAGE_CHECK, seconds(t0));
        progress.on(STAGE_CHECK, 1, 1);
    }

    // ── OCR 接缝（把受检异常收口在签名之内） ─────────────────────────

    /**
     * 渲染页面为平台 OCR 要的图。
     *
     * <p><b>参数只跟平台走</b>（{@code settings.ocr.platform*}，默认 150 DPI / JPEG / q85）：
     * 实测 120~300 DPI 识别结果完全一致（VL 模型内部下采样到 ≈100 万像素），
     * 而 300 DPI PNG 每页 6.7MB、150 DPI JPEG 每页 396KB——差 17 倍，白等的是上传时间。
     */
    private List<Path> renderPages(Path path, int dpi, Path outDir, String fmt, ProgressFn progress) {
        try {
            List<Path> images = pdfReader.renderPages(path, dpi, outDir, fmt,
                    settings.getOcr().getPlatformQuality(),
                    // 阶段名固定为 "render"，不依赖调用方传什么
                    (stage, done, total) -> progress.on(STAGE_RENDER, done, total));
            return images == null ? List.of() : images;
        } catch (Exception e) {
            throw rethrow("页面渲染失败：" + path, e);
        }
    }

    private PdfInfo pdfInfo(Path path) {
        try {
            return pdfReader.read(path);
        } catch (Exception e) {
            throw rethrow("PDF 信息读取失败：" + path, e);
        }
    }

    private List<String> pageTexts(Path path) {
        try {
            List<String> texts = pdfReader.extractPageTexts(path);
            return texts == null ? List.of() : texts;
        } catch (Exception e) {
            throw rethrow("PDF 文本层提取失败：" + path, e);
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private static DocumentText errorDocument(String error) {
        DocumentText doc = new DocumentText();
        doc.setKind("error");
        doc.setError(error);
        return doc;
    }

    private static String suffix(Path path) {
        String raw = rawSuffix(path);
        return raw.isEmpty() ? "" : "." + raw.toLowerCase(Locale.ROOT);
    }

    /** Python 的 {@code Path.suffix.lstrip(".")}：不带点、保持原大小写。 */
    private static String rawSuffix(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** 页码列表拼成「1、2、3」（Python: {@code '、'.join(...)}）。 */
    private static String joinPageNumbers(List<Integer> pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(pages.get(i));
        }
        return sb.toString();
    }

    private static String describe(List<Path> images) {
        return images == null ? "0 张" : images.size() + " 张";
    }

    private static double seconds(long startNanos) {
        return round2((System.nanoTime() - startNanos) / 1_000_000_000.0);
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * 保住原始异常类型，只把受检异常（如 {@code IOException}）包成运行时异常。
     *
     * <p>{@link #read} 的签名不许加 {@code throws}（其它人依赖），而 Python 侧遇到渲染/识别异常
     * 是<b>往上抛</b>（由上传任务标 FAILED / 接口 500），所以这里也抛出去，不吞成"看起来成功"。
     */
    private static RuntimeException rethrow(String message, Exception e) {
        if (e instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message + "（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）", e);
    }

    /** 尽力删除中间目录：删不掉只记日志，绝不让清理失败盖住真正的解析结果。 */
    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("渲染中间文件删除失败（忽略）：{} - {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("渲染中间目录清理失败（忽略）：{} - {}", dir, e.getMessage());
        }
    }
}
