package com.pmgt.ai.module.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.ocr.PdfInfo;
import com.pmgt.ai.module.ocr.PdfReader;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final Map<String, UploadTask> tasks = new ConcurrentHashMap<>();

    /** 队列满被拒的次数（P0-4：拒绝要可见，不能只体现在日志里）。 */
    private final Counter rejected;

    public UploadTaskService(AiSettings settings, ObjectMapper mapper, DocumentReader documentReader,
                             PdfReader pdfReader, DocStore docStore, DocIndexer docIndexer,
                             ThreadPoolTaskExecutor uploadTaskExecutor, MeterRegistry registry) {
        this.settings = settings;
        this.mapper = mapper;
        this.documentReader = documentReader;
        this.pdfReader = pdfReader;
        this.docStore = docStore;
        this.docIndexer = docIndexer;
        this.uploadTaskExecutor = uploadTaskExecutor;
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

    /**
     * 登记任务并立即返回；解析在后台线程里跑（由 AsyncConfig 的线程池承载）。
     *
     * <p><b>队列满 = 诚实失败（P0-4）</b>：线程池用默认 AbortPolicy，队列（100）满了会抛
     * {@link TaskRejectedException}。改造前这个异常直接冒到通用 500，而任务记录<b>永远停在 QUEUED</b>、
     * 临时文件残留在 {@code work/tmp/}——用户看到"上传失败"却查不出原因，任务列表里还挂着一个永不结束的任务。
     * 现在：<b>标 FAILED + 写明原因与当前深度 + 删临时文件 + 抛 429</b>，
     * 保证"0 条永久 QUEUED、0 个 work/tmp 残留"。
     */
    public UploadTask submit(Path tmpPath, String filename, long sizeBytes, Integer dpi) {
        UploadTask task = new UploadTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        task.setFilename(filename);
        task.setSizeBytes(sizeBytes);
        task.setDpi(dpi);
        task.setTmpPath(tmpPath.toString());
        tasks.put(task.getTaskId(), task);
        persist();
        try {
            // 显式提交到线程池（不用 @Async：类内自调用走不到代理，会变成同步执行）
            uploadTaskExecutor.execute(() -> run(task.getTaskId(), dpi));
        } catch (TaskRejectedException e) {
            rejected.increment();
            int depth = queueDepth();
            String reason = "解析队列已满（当前排队 " + depth + "/" + queueCapacity()
                    + "），任务未被执行，请稍后重试（或等待在途解析完成）。文件已清理，不会留下半成品。";
            task.setStatus("FAILED");
            task.setFinishedAt(UploadTask.timestamp());
            task.setStageKey("receive");
            task.setStage("已拒绝");
            task.setError(reason);
            task.setTmpPath("");
            deleteQuietly(tmpPath);
            persist();
            log.warn("解析队列已满，任务 {}（{}）被拒绝：{}", task.getTaskId(), filename, reason);
            throw ApiException.queueFull(reason);
        }
        return task;
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

    /** 取消：只有未结束的任务可取消；标记后由进度回调抛异常中断解析。 */
    public boolean cancel(String taskId) {
        UploadTask task = tasks.get(taskId);
        if (task == null || isFinished(task)) {
            return false;
        }
        task.setCancelRequested(true);
        return true;
    }

    /** 移除记录：未结束的拒绝（先取消，避免"任务还在跑但记录没了"）。 */
    public boolean remove(String taskId) {
        UploadTask task = tasks.get(taskId);
        if (task == null || !isFinished(task)) {
            return false;
        }
        tasks.remove(taskId);
        persist();
        return true;
    }

    private boolean isFinished(UploadTask task) {
        return switch (task.getStatus()) {
            case "DONE", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }

    // ── 后台执行 ────────────────────────────────────────────────────

    /** 后台执行（由线程池调用，见 AsyncConfig 里"为什么不用 @Async"的说明）。 */
    void run(String taskId, Integer dpi) {
        UploadTask task = tasks.get(taskId);
        if (task == null || "CANCELLED".equals(task.getStatus())) {
            return;
        }
        task.setStatus("PARSING");
        task.setStartedAt(UploadTask.timestamp());
        Path tmp = Path.of(task.getTmpPath());
        long started = System.nanoTime();

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
            // 判定文件类型（与 Python 的 detect 阶段一致：先看是文本型还是扫描件）
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

            task.setStageKey("store");
            task.setStage(UploadTask.STAGE_LABEL.get("store"));
            task.setProvider(doc.getProvider() != null ? doc.getProvider() : doc.getEngine());
            StoredDoc saved = docStore.save(
                    task.getFilename(),
                    doc,
                    task.getSizeBytes() > 0 ? task.getSizeBytes() : fileSize(tmp));
            task.setDocId(saved.getDocId());

            // ── P0-3：向量化前移进解析任务 ─────────────────────────────
            // 切片完就在入库阶段算完向量并写进后端（local=向量缓存 / opensearch=_bulk），
            // 不留到"第一次检索时"在问答线程里惰性补算——那正是"第一次提问超时"的根因。
            // ⚠️ opensearch 后端写索引失败会在这里抛错 → 任务 FAILED（**不静默降级**）。
            task.setStageKey("embed");
            task.setStage(UploadTask.STAGE_LABEL.get("embed"));
            DocIndexer.IndexOutcome indexed = docIndexer.indexDocument(saved, onProgress);
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
        } catch (TaskCancelledException e) {
            task.setStatus("CANCELLED");
            task.setStage("已取消");
            task.setError("已被用户取消");
        } catch (Exception e) {
            // 任何异常都要落到任务状态上，别让前端一直转圈
            task.setStatus("FAILED");
            task.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("任务 {} 解析失败：{}", taskId, e.getMessage());
        } finally {
            task.setFinishedAt(UploadTask.timestamp());
            task.setTmpPath("");
            deleteQuietly(tmp);
            task.setElapsedSeconds((System.nanoTime() - started) / 1_000_000_000.0);
            persist();
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
}
