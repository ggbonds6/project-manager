package com.pmgt.module.attach.service;

import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.entity.AttachmentUploadTask;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.attach.mapper.AttachmentUploadTaskMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 附件后台上传服务。
 *
 * <p>为什么需要它：对象存储写入是"先收完整个请求再同步转发"的模式，
 * 大文件在 nginx（proxy_read_timeout 默认 60s）与 OBS 客户端 socketTimeout（默认 60s）
 * 上都很容易超时，导致前端看到"上传失败"而后台其实还在写。
 *
 * <p>现在拆成两段：
 * <ol>
 *   <li><b>受理</b>：文件先快速落到本地临时区，登记 {@code attachment_upload_task}
 *       （PENDING），<b>立即返回 taskId</b>——前端不必等待对象存储写完；</li>
 *   <li><b>后台推送</b>：线程池异步把临时文件写入存储，期间用
 *       {@link com.pmgt.common.storage.ProgressCallback} 回写进度，
 *       结束时置 SUCCESS（并落正式 attachment 记录）或 FAILED（记错误原因）。</li>
 * </ol>
 * 前端轮询任务状态即可展示进度；用户关闭页面也不影响后台推送完成。
 */
@Service
public class AttachmentUploadService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentUploadService.class);

    /** 进度写库的步长（百分比），避免高频更新打爆数据库 */
    private static final int PROGRESS_STEP = 5;

    private final AttachmentStorage attachmentStorage;
    private final AttachmentMapper attachmentMapper;
    private final AttachmentUploadTaskMapper taskMapper;
    private final OperationLogService operationLogService;

    /** 本地临时区（推送完成后删除；建议放容器本地盘，不要放附件卷） */
    private final Path tmpDir;

    /** 推送线程池：并发不宜过高，避免多个大文件互抢带宽导致整体更慢 */
    private final ExecutorService pool;

    public AttachmentUploadService(AttachmentStorage attachmentStorage,
                                   AttachmentMapper attachmentMapper,
                                   AttachmentUploadTaskMapper taskMapper,
                                   OperationLogService operationLogService,
                                   @Value("${app.upload-tmp-dir:./upload-tmp}") String tmpDir) {
        this.attachmentStorage = attachmentStorage;
        this.attachmentMapper = attachmentMapper;
        this.taskMapper = taskMapper;
        this.operationLogService = operationLogService;
        this.tmpDir = Paths.get(tmpDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.tmpDir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建上传临时目录: " + this.tmpDir, e);
        }
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "attach-upload-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        // 服务重启时，上一轮未完成的任务不会再有线程接手 → 统一标记失败，避免前端永远转圈
        try {
            markInterruptedAsFailed();
        } catch (Exception e) {
            log.warn("[attach-upload] 清理中断任务失败: {}", e.getMessage());
        }
    }

    /**
     * 受理上传：落临时文件 + 建任务记录，立即返回（不等待对象存储）。
     */
    public AttachmentUploadTask submit(MultipartFile file, Long projectId, String bizType, Long bizId,
                                       String attachType, String fileExt, Long userId) {
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String storedName = UUID.randomUUID().toString().replace("-", "")
                + (fileExt == null || fileExt.isEmpty() ? "" : "." + fileExt);
        YearMonth ym = YearMonth.now();
        String relKey = ym.getYear() + "/" + String.format("%02d", ym.getMonthValue()) + "/" + storedName;

        Path tmp = tmpDir.resolve(UUID.randomUUID().toString().replace("-", "") + ".part");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("暂存上传文件失败: " + e.getMessage(), e);
        }

        AttachmentUploadTask task = new AttachmentUploadTask();
        task.setProjectId(projectId);
        task.setBizType(bizType);
        task.setBizId(bizId);
        task.setAttachType(attachType);
        task.setFileName(original);
        task.setFileSize(file.getSize());
        task.setFileExt(fileExt);
        task.setStoredName(storedName);
        task.setFilePath(relKey);
        task.setTempPath(tmp.toString());
        task.setStatus(AttachmentUploadTask.PENDING);
        task.setProgress(0);
        task.setUploadUserId(userId);
        task.setCreateTime(LocalDateTime.now());
        taskMapper.insert(task);

        final Long taskId = task.getId();
        pool.submit(() -> store(taskId));
        return task;
    }

    /**
     * 后台推送存储（由线程池调用）。
     */
    public void store(Long taskId) {
        AttachmentUploadTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        updateTask(taskId, AttachmentUploadTask.UPLOADING, 0, null, null, null);
        log.info("[attach-upload] 开始推送 task={} file={} size={}",
                taskId, task.getFileName(), task.getFileSize());

        Path tmp = task.getTempPath() == null ? null : Paths.get(task.getTempPath());
        AtomicInteger lastPct = new AtomicInteger(-PROGRESS_STEP);
        try (InputStream in = Files.newInputStream(tmp)) {
            attachmentStorage.save(task.getFilePath(), in, task.getFileSize(), (transferred, total) -> {
                long denom = total > 0 ? total : task.getFileSize();
                if (denom <= 0) {
                    return;
                }
                int pct = (int) Math.min(99, transferred * 100 / denom);
                if (pct >= lastPct.get() + PROGRESS_STEP) {
                    lastPct.set(pct);
                    updateProgress(taskId, pct);
                }
            });

            // 推送成功 → 落正式附件记录，再回写任务
            Attachment att = new Attachment();
            att.setBizType(task.getBizType());
            att.setBizId(task.getBizId());
            att.setAttachType(task.getAttachType());
            att.setFileName(task.getFileName());
            att.setStoredName(task.getStoredName());
            att.setFilePath(task.getFilePath());
            att.setFileSize(task.getFileSize());
            att.setFileExt(task.getFileExt());
            att.setUploadUserId(task.getUploadUserId());
            att.setUploadTime(LocalDateTime.now());
            attachmentMapper.insert(att);

            updateTask(taskId, AttachmentUploadTask.SUCCESS, 100, att.getId(), null, LocalDateTime.now());
            operationLogService.log("PROJECT", task.getProjectId(), "ATTACH_UPLOAD",
                    "上传附件「" + task.getFileName() + "」(" + task.getBizType() + "#" + task.getBizId() + ")");
            log.info("[attach-upload] 推送完成 task={} attachmentId={}", taskId, att.getId());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > 480) {
                msg = msg.substring(0, 480);
            }
            updateTask(taskId, AttachmentUploadTask.FAILED, 0, null, msg, LocalDateTime.now());
            log.error("[attach-upload] 推送失败 task={} file={}: {}", taskId, task.getFileName(), msg);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    /** 服务启动时把上次残留在 PENDING/UPLOADING 的任务标记为失败（没有线程会再接手上传） */
    private void markInterruptedAsFailed() {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AttachmentUploadTask>()
                .in(AttachmentUploadTask::getStatus,
                        AttachmentUploadTask.PENDING, AttachmentUploadTask.UPLOADING);
        var list = taskMapper.selectList(wrapper);
        for (AttachmentUploadTask t : list) {
            t.setStatus(AttachmentUploadTask.FAILED);
            t.setErrorMsg("服务重启导致上传中断，请重新上传");
            t.setFinishTime(LocalDateTime.now());
            taskMapper.updateById(t);
        }
        if (!list.isEmpty()) {
            log.info("[attach-upload] 已将 {} 条中断任务标记为失败", list.size());
        }
    }

    private void updateProgress(Long taskId, int pct) {
        AttachmentUploadTask u = new AttachmentUploadTask();
        u.setId(taskId);
        u.setProgress(pct);
        taskMapper.updateById(u);
    }

    private void updateTask(Long taskId, String status, Integer progress, Long attachmentId,
                            String errorMsg, LocalDateTime finishTime) {
        AttachmentUploadTask u = new AttachmentUploadTask();
        u.setId(taskId);
        u.setStatus(status);
        u.setProgress(progress);
        u.setAttachmentId(attachmentId);
        u.setErrorMsg(errorMsg);
        u.setFinishTime(finishTime);
        taskMapper.updateById(u);
    }
}
