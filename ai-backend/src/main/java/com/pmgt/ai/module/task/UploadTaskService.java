package com.pmgt.ai.module.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.gate.GateChannel;
import com.pmgt.ai.common.gate.GateResult;
import com.pmgt.ai.common.gate.PipelineGate;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.ocr.PdfInfo;
import com.pmgt.ai.module.ocr.PdfReader;
import com.pmgt.ai.module.pipeline.ArtifactType;
import com.pmgt.ai.module.pipeline.PipelineReporter;
import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.pipeline.RunContext;
import com.pmgt.ai.module.pipeline.StageKey;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上传解析任务：登记 → 后台解析 → 前端轮询。
 *
 * <p>三条与 Python 版一致的行为（都是踩过坑才定的）：
 * <ol>
 *   <li><b>先返回、后解析</b>：同步解析必超时（axios 30s + nginx 60s + 存储客户端 60s 三层叠加）；</li>
 *   <li><b>进度按阶段加权</b>：识别阶段占 0.20~0.97，避免长任务"看起来卡死"；</li>
 *   <li><b>重启把残留任务标失败</b>：服务重启时 QUEUED/PARSING 的任务不可能再有结果，
 *       留在库里只会让前端一直转圈，所以启动即标 FAILED 并写明原因。</li>
 * </ol>
 *
 * <p>落盘只保留最近 {@value #MAX_TASKS_KEPT} 条（内存里全留），避免 JSON 无限增长。
 *
 * <h2>本批新增：L1 准入 + 五阶段上报（设计 §4/§5）</h2>
 * <ul>
 *   <li><b>准入</b>：{@link #submit} 先向 L1 {@link PipelineGate} 要一个 {@code BATCH.PARSE} permit
 *       （<b>只试一次，不等</b>——HTTP 接口必须马上给结论，不能把 Tomcat 线程挂在队列上）。
 *       拿不到就<b>诚实失败</b>：429 + 深度/上限/原因，任务标 FAILED、临时文件清掉
 *       （"0 条永久 QUEUED、0 个 work/tmp 残留"的既有纪律不变）。
 *       permit <b>由任务线程在整个任务期间持有</b>（硬规则 H1），任务结束/取消/失败时在
 *       {@code finally} 里归还；取消是协作式的（阶段边界生效），但 permit 的归还<b>立即发生</b>。</li>
 *   <li><b>上报</b>：{@code PARSE / CLEAN(SKIPPED) / CHUNK / EMBED / INDEX} 五个阶段边界回调主系统
 *       （{@link PipelineReporter}）。上报<b>best-effort</b>：失败只 WARN + 计数，绝不影响解析结果；
 *       <b>只有终态</b>会重试。</li>
 * </ul>
 */
@Slf4j
@Service
public class UploadTaskService {

    public static final int MAX_TASKS_KEPT = 100;

    private final AiSettings settings;
    private final ObjectMapper mapper;
    private final DocumentReader documentReader;
    private final PdfReader pdfReader;
    private final DocStore docStore;
    private final DocIndexer docIndexer;
    private final ThreadPoolTaskExecutor uploadTaskExecutor;
    /** L1 业务闸门（可为 null = 测试里的"只有 L2"装配；生产恒为非空）。 */
    private final PipelineGate gate;
    /** 阶段上报（可为 null = 测试里不接上报）。 */
    private final PipelineReporter reporter;

    private final Map<String, UploadTask> tasks = new ConcurrentHashMap<>();

    /**
     * 任务的 L1 permit（任务开始时放入、结束/取消/失败时在 {@code finally} 归还）。
     *
     * <p>为什么不在提交后立刻 release：permit 必须覆盖<b>整个任务期间</b>（H1）——
     * 若提交完就还，L1 就退化成"一个瞬时开关"，批量并发会重新变成无界。
     */
    private final Map<String, GateResult> permits = new ConcurrentHashMap<>();

    /** 每个任务的上报上下文（取消时需要它补一条 CANCELLED 并交还 MDC）。 */
    private final Map<String, RunContext> runContexts = new ConcurrentHashMap<>();

    /** 队列满被拒的次数（P0-4：拒绝要可见，不能只体现在日志里）。 */
    private final Counter rejected;

    public UploadTaskService(AiSettings settings, ObjectMapper mapper, DocumentReader documentReader,
                             PdfReader pdfReader, DocStore docStore, DocIndexer docIndexer,
                             ThreadPoolTaskExecutor uploadTaskExecutor, MeterRegistry registry) {
        this(settings, mapper, documentReader, pdfReader, docStore, docIndexer, uploadTaskExecutor, registry,
                null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UploadTaskService(AiSettings settings, ObjectMapper mapper, DocumentReader documentReader,
                             PdfReader pdfReader, DocStore docStore, DocIndexer docIndexer,
                             ThreadPoolTaskExecutor uploadTaskExecutor, MeterRegistry registry,
                             PipelineGate gate, PipelineReporter reporter) {
        this.settings = settings;
        this.mapper = mapper;
        this.documentReader = documentReader;
        this.pdfReader = pdfReader;
        this.docStore = docStore;
        this.docIndexer = docIndexer;
        this.uploadTaskExecutor = uploadTaskExecutor;
        this.gate = gate;
        this.reporter = reporter;
        MeterRegistry meters = registry == null ? new SimpleMeterRegistry() : registry;
        this.rejected = Counter.builder("ai.upload.rejected")
                .description("因解析队列已满被拒绝的上传任务数（诚实失败：任务标 FAILED 并清临时文件）")
                .register(meters);
        // 队列深度 / 在途数（P0-6）：运维不用看日志就能回答"解析为什么排队"
        Gauge.builder("ai.upload.queue.depth", uploadTaskExecutor,
                        executor -> executor.getThreadPoolExecutor().getQueue().size())
                .description("解析任务排队数").register(meters);
        Gauge.builder("ai.upload.inflight", uploadTaskExecutor,
                        executor -> executor.getThreadPoolExecutor().getActiveCount())
                .description("解析任务在途数").register(meters);
    }

    private Path storeFile() {
        return settings.getWorkDir().resolve("tasks.json");
    }

    // ── 启动：加载历史 + 标注中断任务 ────────────────────────────────

    @PostConstruct
    void load() {
        try {
            Files.createDirectories(settings.getWorkDir());
        } catch (IOException e) {
            log.warn("创建 work 目录失败：{}", e.getMessage());
        }
        Path file = storeFile();
        if (Files.isRegularFile(file)) {
            try {
                UploadTask[] loaded = mapper.readValue(Files.readString(file), UploadTask[].class);
                for (UploadTask task : loaded) {
                    tasks.put(task.getTaskId(), task);
                }
            } catch (Exception e) {
                log.warn("任务记录读取失败（忽略并重建）：{}", e.getMessage());
            }
        }
        markInterrupted();
    }

    /** 重启后 QUEUED/PARSING 的任务不可能再有结果 → 标失败（比让前端一直等更诚实）。 */
    private void markInterrupted() {
        boolean changed = false;
        for (UploadTask task : tasks.values()) {
            if ("QUEUED".equals(task.getStatus()) || "PARSING".equals(task.getStatus())) {
                task.setStatus("FAILED");
                task.setError("服务重启，任务中断（请重新上传）");
                task.setFinishedAt(UploadTask.timestamp());
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    // ── 对外 ───────────────────────────────────────────────────────

    public UploadTask submit(Path tmpPath, String filename, long sizeBytes, Integer dpi) {
        return submit(tmpPath, filename, sizeBytes, dpi, null, null);
    }

    /**
     * 登记任务并立即返回；解析在后台线程里跑（由 AsyncConfig 的线程池承载）。
     *
     * <h2>两层准入，缺一不可</h2>
     * <ol>
     *   <li><b>L1 业务闸门</b>（本方法内）：{@code BATCH.PARSE} 通道的 permit。
     *       <b>只试一次（waitMs=0）</b>：上传是 HTTP 接口，必须立刻给结论，
     *       不能像批量内部那样挂在队列上等 60s（那会把 Tomcat 线程一起挂住）。
     *       拿不到 ⇒ 429 + 深度/上限/原因 + 任务标 FAILED + 清临时文件
     *       （<b>诚实失败</b>：绝不出现"接受了但永远不跑"）。</li>
     *   <li><b>线程池队列</b>（{@code uploadTaskExecutor}，现状 4 线程 + 队列 100）：
     *       保底。L1 的队列上限与它对齐（默认 100），所以正常路径上不会再被它拒；
     *       真被拒（例如配置不一致）也走同一套诚实失败。</li>
     * </ol>
     *
     * <p>拿到的 L1 permit <b>不在这里归还</b>：它由任务线程在整个任务期间持有（H1），
     * 在 {@link #run} 的 {@code finally} 里归还。
     *
     * @param projectId 项目 id（按项目公平调度的维度；可空）
     * @param taskKey   主系统给的幂等键（可空；本批由 AI 侧按 {@code sha1(内容)+pipeline_rev} 自算）
     */
    public UploadTask submit(Path tmpPath, String filename, long sizeBytes, Integer dpi, String projectId,
                             String taskKey) {
        UploadTask task = new UploadTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        task.setFilename(filename);
        task.setSizeBytes(sizeBytes);
        task.setDpi(dpi);
        task.setTmpPath(tmpPath.toString());
        tasks.put(task.getTaskId(), task);
        persist();

        // ── L1 预检（提交线程只判断，不占许可；真正的 permit 由任务线程持有）──
        // 为什么不在提交线程拿 permit：L1 permit 必须由**持牌干活的那个线程**持有，
        // 否则 H1 守卫（ModelRegistry）在任务线程里看不到 L1 permit、会误报违规；
        // 且"提交线程拿牌、任务线程干活"等于 permit 与执行者分离，取消/超时都说不清谁归还。
        GateResult admission = null;
        if (gate != null) {
            admission = gate.probe(GateChannel.BATCH_PARSE, projectId);
            if (!admission.admitted()) {
                return rejectTask(task, tmpPath, filename, admission.describe(), admission);
            }
        }

        try {
            // 显式提交到线程池（不用 @Async：类内自调用走不到代理，会变成同步执行）
            uploadTaskExecutor.execute(() -> run(task.getTaskId(), dpi, projectId, taskKey));
        } catch (TaskRejectedException e) {
            String reason = "解析队列已满（当前排队 " + queueDepth() + "/" + queueCapacity()
                    + "），任务未被执行，请稍后重试（或等待在途解析完成）。文件已清理，不会留下半成品。";
            return rejectTask(task, tmpPath, filename, reason, null);
        }
        return task;
    }

    /** 诚实失败：429 + 任务标 FAILED + 清临时文件 + 归还 permit（绝不能留永久 QUEUED）。 */
    private UploadTask rejectTask(UploadTask task, Path tmpPath, String filename, String reason,
                                 GateResult admission) {
        rejected.increment();
        if (admission == null) {
            log.warn("解析队列已满，任务 {}（{}）被拒绝：{}", task.getTaskId(), filename, reason);
        } else {
            log.warn("L1 拒绝解析任务 {}（{}）：{}", task.getTaskId(), filename, reason);
        }
        task.setStatus("FAILED");
        task.setFinishedAt(UploadTask.timestamp());
        task.setStageKey("receive");
        task.setStage("已拒绝");
        task.setError(reason);
        task.setTmpPath("");
        deleteQuietly(tmpPath);
        persist();
        throw ApiException.queueFull(reason);
    }

    /** 当前排队数（{@code ai.upload.queue.depth} 的同一个口径）。 */
    public int queueDepth() {
        try {
            return uploadTaskExecutor.getThreadPoolExecutor().getQueue().size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 队列容量（{@code AsyncConfig} 里配的 100）。 */
    public int queueCapacity() {
        try {
            return uploadTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity() + queueDepth();
        } catch (Exception e) {
            return -1;
        }
    }

    public List<Map<String, Object>> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(UploadTask::getCreatedAt).reversed())
                .map(UploadTask::toBrief)
                .toList();
    }

    public UploadTask get(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 取消：只有未结束的任务可取消；标记后由进度回调抛异常中断解析。
     *
     * <p><b>协作式</b>（设计 §5.2 抢占表最后一行）：取消位在<b>阶段/页边界</b>生效；
     * 但 permit 与指标<b>立即回落</b>——取消时立刻把 L1 permit 还给闸门，
     * 并按已完成的阶段补一条 {@code CANCELLED} 阶段行与终态 {@code CANCELLED}。
     * 正在跑的模型调用（L2）会自然结束（平台侧不支持我们的取消协议），
     * 它的 permit 由该调用的 {@code finally} 归还。
     */
    public boolean cancel(String taskId) {
        UploadTask task = tasks.get(taskId);
        if (task == null || isFinished(task)) {
            return false;
        }
        task.setCancelRequested(true);
        releasePermit(taskId);
        RunContext run = runContexts.get(taskId);
        if (run != null) {
            run.stageCancelled(StageKey.PARSE, "用户取消：permit 已立即归还，解析在下一个阶段/页边界停止");
            run.finish("CANCELLED", "已被用户取消");
        }
        return true;
    }

    /** 移除记录：未结束的拒绝（先取消，避免"任务还在跑但记录没了"）。 */
    public boolean remove(String taskId) {
        UploadTask task = tasks.get(taskId);
        if (task == null || !isFinished(task)) {
            return false;
        }
        tasks.remove(taskId);
        releasePermit(taskId);
        RunContext run = runContexts.remove(taskId);
        if (run != null) {
            run.close();
        }
        persist();
        return true;
    }

    private boolean isFinished(UploadTask task) {
        return switch (task.getStatus()) {
            case "DONE", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }

    /** 归还 L1 permit（幂等：{@link GateResult#close()} 自己保证只生效一次）。 */
    private void releasePermit(String taskId) {
        GateResult admission = permits.remove(taskId);
        if (admission != null) {
            admission.close();
        }
    }

    // ── 后台执行 ────────────────────────────────────────────────────

    /** 后台执行（由线程池调用，见 AsyncConfig 里"为什么不用 @Async"的说明）。 */
    void run(String taskId, Integer dpi) {
        run(taskId, dpi, null, null);
    }

    /**
     * 后台执行（带 L1 项目维度与幂等键）。
     *
     * <p>阶段边界（与设计 §3.2 逐条对应）：
     * <pre>
     *   PARSE  RUNNING → DONE/PARTIAL/FAILED     （识别：detect + read）
     *   CLEAN  SKIPPED                            （本批尚无清洗阶段，原因写进 error_msg）
     *   CHUNK  RUNNING → DONE                     （DocIndexer.prepare）
     *   EMBED  RUNNING → DONE/PARTIAL             （DocIndexer.indexPrepared）
     *   INDEX  RUNNING → DONE/PARTIAL/FAILED      （同上）
     *   finish DONE|PARTIAL|FAILED|CANCELLED      （终态以主系统为准）
     * </pre>
     */
    void run(String taskId, Integer dpi, String projectId, String taskKey) {
        UploadTask task = tasks.get(taskId);
        if (task == null || "CANCELLED".equals(task.getStatus())) {
            releasePermit(taskId);
            return;
        }

        // ── L1 准入：**任务线程**在整个任务期间持有 permit（H1；排队发生在任务开始之前 = H2）──
        // 提交线程只做过一次预检（probe）以给出即时结论；这里才是真正的"占牌"，
        // 因此 ModelRegistry 的 H1 守卫在任务线程里能看到 L1 permit（顺序正确）。
        if (gate != null) {
            GateResult admission = gate.admit(GateChannel.BATCH_PARSE, projectId, 0,
                    settings.getLimits().getBatchAcquireMs());
            if (!admission.admitted()) {
                String reason = admission.describe();
                Path tmpFile = Path.of(task.getTmpPath());
                log.warn("任务 {} 未能取得 L1 准入（诚实失败：标 FAILED + 清临时文件，无永久 QUEUED）：{}",
                        taskId, reason);
                task.setStatus("FAILED");
                task.setFinishedAt(UploadTask.timestamp());
                task.setStageKey("receive");
                task.setStage("已拒绝");
                task.setError(reason);
                task.setTmpPath("");
                deleteQuietly(tmpFile);
                persist();
                releasePermit(taskId);
                return;
            }
            permits.put(taskId, admission);
        }

        task.setStatus("PARSING");
        task.setStartedAt(UploadTask.timestamp());
        Path tmp = Path.of(task.getTmpPath());
        long started = System.nanoTime();
        long nestedStarted = started;

        // ── 上报上下文：run_id / pipeline_token 全程贯穿；构造时写日志 MDC ──
        String contentSha1 = sha1Of(tmp);
        RunContext run = reporter == null
                ? null
                : reporter.open(null, null, contentSha1, projectId);
        if (run != null) {
            runContexts.put(taskId, run);
            if (taskKey != null) {
                log.debug("任务 {} 由主系统给出幂等键 {}（AI 侧自算为 {}）", taskId, taskKey, run.taskKey());
            }
            run.stageStarted(StageKey.PARSE, "file=" + task.getFilename() + " dpi=" + dpi
                    + " sha1=" + (contentSha1 == null ? "" : contentSha1)
                    + " pipeline_rev=" + PipelineRev.pipelineRev());
        }
        String terminalStatus = "FAILED";
        String terminalError = null;

        ProgressFn onProgress = (stage, done, total) -> {
            if (task.isCancelRequested()) {
                throw new TaskCancelledException("用户取消");
            }
            task.setStageKey(stage);
            task.setStage(UploadTask.STAGE_LABEL.getOrDefault(stage, stage));
            task.setStageDone(done);
            task.setStageTotal(total);
            persist();
        };

        try {
            // ── PARSE：判定文件类型 + 解析（识别）──
            onProgress.on("detect", 0, 1);
            PdfInfo info = pdfReader.read(tmp);
            if (info.error() != null && tmp.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                throw new IllegalStateException(info.error());
            }
            onProgress.on("detect", 1, 1);

            DocumentText doc = documentReader.read(tmp, dpi, false, onProgress);
            if (doc.getError() != null && !doc.getError().isBlank()) {
                throw new IllegalStateException(doc.getError());
            }
            if (doc.text().isBlank()) {
                // 本地兜底已移除：没有内容就是没有内容，如实失败并给出可操作的下一步
                throw new IllegalStateException(
                        "未从文件中提取到任何文本。若为扫描件，可能是清晰度过低；"
                                + "可提高平台 OCR 渲染 DPI（OCR_PLATFORM_DPI）后重试，或确认该页本身是空白页。");
            }

            long parseElapsed = (System.nanoTime() - nestedStarted) / 1_000_000;
            int failedPages = countFailedPages(doc);            if (run != null) {
                run.stageDone(StageKey.PARSE, doc.pageCount(), failedPages,
                        parseCounters(doc, task, failedPages), "pages=" + doc.pageCount()
                                + "; text_chars=" + doc.text().length()
                                + "; provider=" + (doc.getProvider() == null ? "" : doc.getProvider()));
                run.artifact(ArtifactType.PAGE_DIGEST, pageDigestJson(doc, task, failedPages), null,
                        doc.text().length(), PipelineRev.sha1Hex(doc.text()),
                        doc.pageCount(), "页级摘要（页数/空页/逐页字符数与来源）；全文留在 work/docs/<docId>.json");
                // ── CLEAN：本批尚无清洗阶段 ⇒ 如实上报 SKIPPED（原因见 PipelineReporter）──
                run.stageSkipped(StageKey.CLEAN, PipelineReporter.cleanSkippedReason());
                run.artifact(ArtifactType.CLEAN_DIFF, null, null, null, null, 0,
                        "本批无清洗阶段（clean_rev=" + PipelineRev.current().cleanRev()
                                + "）：无前后对照可写，如实留空而不是伪造数据");
            }

            task.setStageKey("store");
            task.setStage(UploadTask.STAGE_LABEL.get("store"));
            task.setProvider(doc.getProvider() != null ? doc.getProvider() : doc.getEngine());
            StoredDoc saved = docStore.save(
                    task.getFilename(),
                    doc,
                    task.getSizeBytes() > 0 ? task.getSizeBytes() : fileSize(tmp));
            task.setDocId(saved.getDocId());
            if (run != null) {
                run.event("RUNNING", "RUNNING", "doc_id=" + saved.getDocId() + " staged=PARSE", "ai-backend");
            }

            // ── P0-3：向量化前移进解析任务（CHUNK → EMBED → INDEX 三个阶段，边界都上报）──
            // 切片完就在入库阶段算完向量并写进后端（local=向量缓存 / opensearch=_bulk），
            // 不留到"第一次检索时"在问答线程里惰性补算——那正是"第一次提问超时"的根因。
            // ⚠️ opensearch 后端写索引失败会在这里抛错 → 任务 FAILED（**不静默降级**）。
            task.setStageKey("embed");
            task.setStage(UploadTask.STAGE_LABEL.get("embed"));
            nestedStarted = System.nanoTime();
            DocIndexer.Prepared prepared = docIndexer.prepare(saved, run);
            DocIndexer.IndexOutcome indexed = docIndexer.indexPrepared(saved, prepared, false, onProgress, run);
            List<String> notes = new ArrayList<>(doc.getNotes() == null ? List.of() : doc.getNotes());
            notes.add("向量化/入库：" + indexed.describe());
            task.setNotes(notes);
            log.info("任务 {} 向量化入库完成：{}", taskId, indexed.describe());

            task.setSummary(doc.summary());
            task.setStages(doc.getStages());
            task.setChecks(doc.getChecks());
            task.setPages(doc.getPages().stream().map(p -> (Map<String, Object>) p.quality()).toList());
            task.setStatus("DONE");
            task.setStageKey("done");
            task.setStage(UploadTask.STAGE_LABEL.get("done"));
            task.setStageDone(doc.pageCount());
            task.setStageTotal(doc.pageCount());
            terminalStatus = failedPages > 0 || indexed.failed() > 0 ? "PARTIAL" : "DONE";
            terminalError = failedPages > 0 ? (failedPages + " 页识别失败，已按占位页入库（需人工复核）") : null;
        } catch (TaskCancelledException e) {
            task.setStatus("CANCELLED");
            task.setStage("已取消");
            task.setError("已被用户取消");
            terminalStatus = "CANCELLED";
            terminalError = "已被用户取消（协作式取消：在阶段/页边界生效；L1 permit 已立即归还）";
            if (run != null) {
                run.stageCancelled(StageKey.PARSE, terminalError);
            }
        } catch (Exception e) {
            // 任何异常都要落到任务状态上，别让前端一直转圈
            task.setStatus("FAILED");
            task.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("任务 {} 解析失败：{}", taskId, e.getMessage());
            terminalStatus = "FAILED";
            terminalError = task.getError();
            if (run != null) {
                // 阶段级失败由 DocIndexer 负责（它知道是 EMBED 还是 INDEX）；这里兜底 PARSE
                run.stageFailed(StageKey.PARSE, terminalError);
            }
        } finally {
            task.setFinishedAt(UploadTask.timestamp());
            task.setTmpPath("");
            deleteQuietly(tmp);
            task.setElapsedSeconds((System.nanoTime() - started) / 1_000_000_000.0);
            persist();
            if (run != null) {
                run.event("RUNNING", terminalStatus, "task=" + taskId + " elapsed_ms="
                        + (System.nanoTime() - started) / 1_000_000, "ai-backend");
                run.finish(terminalStatus, terminalError);
                runContexts.remove(taskId);
                run.close();
            }
            // ⚠️ permit 必须在**所有路径**上归还（成功/失败/取消/异常），否则就是 permit 泄漏
            releasePermit(taskId);
        }
    }

    // ── 上报用的计数与摘要（都由既有事实派生，不新增状态）────────────────

    private int countFailedPages(DocumentText doc) {
        int failed = 0;
        for (var page : doc.getPages()) {
            String error = page.getError();
            if (error != null && !error.isBlank()) {
                failed++;
            }
        }
        return failed;
    }

    private Map<String, Object> parseCounters(DocumentText doc, UploadTask task, int failedPages) {
        int emptyPages = 0;
        for (var page : doc.getPages()) {
            if (page.getText() == null || page.getText().isBlank()) {
                emptyPages++;
            }
        }
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("pages", doc.pageCount());
        counters.put("text_chars", doc.text().length());
        counters.put("empty_pages", emptyPages);
        counters.put("failed_pages", failedPages);
        counters.put("provider", doc.getProvider() == null ? "" : doc.getProvider());
        counters.put("engine", doc.getEngine() == null ? "" : doc.getEngine());
        counters.put("dpi", task.getDpi() == null ? 0 : task.getDpi());
        counters.put("pipeline_rev", PipelineRev.pipelineRev());
        counters.put("parse_rev", PipelineRev.current().parseRev());
        return counters;
    }

    private String pageDigestJson(DocumentText doc, UploadTask task, int failedPages) {
        StringBuilder sb = new StringBuilder("{\"pages\":").append(doc.pageCount())
                .append(",\"failed_pages\":").append(failedPages)
                .append(",\"text_chars\":").append(doc.text().length())
                .append(",\"per_page\":[");
        int limit = Math.min(30, doc.getPages().size());
        for (int i = 0; i < limit; i++) {
            var page = doc.getPages().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"page_no\":").append(page.getPageNo())
                    .append(",\"chars\":").append(page.getText() == null ? 0 : page.getText().length())
                    .append(",\"source\":\"").append(page.getSource() == null ? "" : page.getSource())
                    .append("\"}");
        }
        sb.append("]}");
        return sb.length() <= 4000 ? sb.toString() : sb.substring(0, 4000);
    }

    /** 原始内容指纹（{@code task_key} 的输入；文件已被删/不可读时返回 {@code null}）。 */
    private static String sha1Of(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 临时文件删不掉不影响业务（下次会覆盖）；但不能因此把任务判成失败
            log.debug("临时文件删除失败：{}", path);
        }
    }

    /** 落盘：只留最近 MAX_TASKS_KEPT 条（按创建时间倒序），避免 JSON 无限增长。 */
    private synchronized void persist() {
        try {
            List<UploadTask> recent = new ArrayList<>(tasks.values());
            recent.sort(Comparator.comparing(UploadTask::getCreatedAt).reversed());
            List<UploadTask> keep = recent.subList(0, Math.min(MAX_TASKS_KEPT, recent.size()));
            Files.createDirectories(settings.getWorkDir());
            Files.writeString(storeFile(), mapper.writeValueAsString(keep));
        } catch (Exception e) {
            log.warn("任务记录落盘失败：{}", e.getMessage());
        }
    }

    /** 用户取消时在进度回调里抛出，用来中断解析（与 Python 的 TaskCancelled 同义）。 */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String message) {
            super(message);
        }
    }

    /** 供 controller 组装响应时复用（保持与 Python 的字段名一致）。 */
    public Map<String, Object> detailOf(UploadTask task) {
        return new LinkedHashMap<>(task.toDetail());
    }

    /** L1 当前是否持有该任务的 permit（自检用；正常应恒为 false）。 */
    public boolean holdsPermit(String taskId) {
        return permits.containsKey(taskId);
    }
}
