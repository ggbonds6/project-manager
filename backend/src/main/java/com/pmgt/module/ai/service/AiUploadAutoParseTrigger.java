package com.pmgt.module.ai.service;

import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.event.AttachmentIndexRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 附件上传成功后的<b>自动解析触发</b>（方案 §3.3：上传成功后自动触发解析，可配置开关）。
 *
 * <h2>三条硬性约束怎么保证</h2>
 * <ol>
 *   <li><b>绝不阻塞上传</b>：本类是 {@link EventListener}，事件在上传线程池线程里发布，
 *       这里立刻转交给独立的单线程 {@code aiParseExecutor}（见 {@code AiAsyncConfig}）；
 *       而且发布时间点是「附件已落库 + 上传任务已置成功 + 已写操作日志」之后，
 *       前端的上传响应早就返回了（v3.2 的两段式上传：先返回 taskId，后台推送完成后才算成功）。
 *       依赖声明成 {@link Executor}（而不是 {@code ExecutorService}）：本类只需要
 *       "交出去执行"这一个能力，收窄依赖既表达了意图，也让单测能直接传一个同步实现。</li>
 *   <li><b>AI 失败绝不影响上传</b>：① 本方法内部 catch 所有异常只记日志；
 *       ② 事件是单向的，异常不可能回传到上传流程；③ 防御性地再 catch 一次
 *       {@link RejectedExecutionException} 等提交期异常。</li>
 *   <li><b>开关关闭时行为与从前完全一致</b>：{@code pm.ai.enabled=false} 或
 *       {@code pm.ai.auto-parse=false} 时，<b>连事件都不发布</b>
 *       （判断放在上传方，见 {@code AttachmentUploadService}），
 *       既不触发也不写任何状态。</li>
 * </ol>
 *
 * <h2>失败后置 FAILED 还是 NOT_PARSED？—— 选 FAILED</h2>
 * 理由：解析<b>确实尝试过</b>，而且失败原因是具体可读的（AI 服务不可达 / 平台 OCR 报错 /
 * 附件文件读不到）。置 {@code NOT_PARSED} 会让附件页显示「未解析」，
 * 用户与管理员的默认判断是「还没轮到」，于是没人去查为什么没解析成——
 * 而插件式 AI 服务挂掉恰恰是最需要被立刻看到的情况。
 * 置 {@code FAILED} + 任务行写明原因，界面上就是「解析失败（原因）+ 重试」，
 * 与 §9 的交互底线（解析失败必须能看到原因并可重试）一致。
 *
 * <p>注意：上传<b>本身</b>的状态不受影响——{@code attachment_upload_task} 仍是 SUCCESS，
 * 附件记录照常存在，只是"能不能被 AI 检索"这一列是 FAILED。
 */
@Component
public class AiUploadAutoParseTrigger {

    private static final Logger log = LoggerFactory.getLogger(AiUploadAutoParseTrigger.class);

    private final AiProperties props;
    private final AiTaskService taskService;
    private final Executor parseExecutor;

    public AiUploadAutoParseTrigger(AiProperties props,
                                    AiTaskService taskService,
                                    @Qualifier("aiParseExecutor") Executor parseExecutor) {
        this.props = props;
        this.taskService = taskService;
        this.parseExecutor = parseExecutor;
    }

    /** 是否应当自动触发（供上传方在发布事件前判断，避免无意义的事件与线程切换）。 */
    public boolean autoParseEnabled() {
        return props.isEnabled() && props.isAutoParse();
    }

    @EventListener
    public void onAttachmentUploaded(AttachmentIndexRequestedEvent event) {
        // 双重保险：上传方已经判断过一次，这里再判断一次（事件也可能被别处发布）
        if (!autoParseEnabled()) {
            return;
        }
        Long attachmentId = event.getAttachmentId();
        if (attachmentId == null) {
            return;
        }
        try {
            parseExecutor.execute(() -> parseQuietly(attachmentId, event.getOperatorUserId()));
        } catch (RejectedExecutionException e) {
            // 队列满且调用方策略拒绝：这属于"这次没触发"，绝不能影响上传
            log.warn("[ai-auto-parse] 触发被拒绝（执行器繁忙），attachmentId={}：{}", attachmentId, e.getMessage());
        } catch (Exception e) {
            log.warn("[ai-auto-parse] 提交触发失败，attachmentId={}：{}", attachmentId, e.getMessage());
        }
    }

    /**
     * 真正触发解析：<b>所有异常都在这里被吞掉</b>并只记日志。
     *
     * <p>为什么连 {@link Error} 之外的一切都要吞：上传已经成功了，
     * 这时候让一个后台线程把异常抛到线程池的未捕获处理器里，除了打日志没有任何作用，
     * 反而可能让「上传成功」这个事实被误读成「上传失败」。
     */
    private void parseQuietly(Long attachmentId, Long operatorUserId) {
        try {
            taskService.autoParse(attachmentId, operatorUserId);
        } catch (Exception e) {
            // 任务行与 attachment.ai_index_status 的失败回写由 AiTaskService 负责，
            // 这里只负责"不让它冒泡"，日志带上 attachmentId 与原因便于排查
            log.warn("[ai-auto-parse] 自动解析触发失败（不影响上传），attachmentId={}，原因：{}",
                    attachmentId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
