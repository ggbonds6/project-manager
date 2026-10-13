package com.pmgt.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 回写 {@code attachment} 的 AI 索引列（{@code ai_index_status} / {@code ai_doc_id} / {@code ai_indexed_at}）。
 *
 * <h2>为什么把它从 {@code AiTaskService} 里抽出来</h2>
 * <p>同一件事现在有<b>两个</b>写入者：既有的对账路径（{@code AiTaskService.applyRemote}）
 * 与新的流水线终态回调（{@code AiPipelineReportService.finish}）。
 * 如果第二处自己再写一遍 SQL，两份口径迟早会漂移——例如某一边忘了把
 * {@code ai_doc_id} 置回 NULL，附件页就会显示"已可检索"但点进去没有文档。
 * 抽成一个类 = 一份实现、两处调用，行为由构造保证一致。
 *
 * <p>本类是<b>纯搬运</b>：逻辑与抽取前逐行一致（含"用 wrapper 显式置 NULL"那条注释里的坑）。
 */
@Component
public class AiAttachmentIndexWriter {

    private final AttachmentMapper attachmentMapper;

    public AiAttachmentIndexWriter(AttachmentMapper attachmentMapper) {
        this.attachmentMapper = attachmentMapper;
    }

    /**
     * 写附件索引状态。
     *
     * @param attachmentId 附件 id（null 直接忽略——任务行可能没有附件，历史数据里出现过）
     * @param status       {@link AiScopeResolver} 的四个状态之一
     * @param docId        AI 文档 id；null 表示要「清空」这一列
     * @param indexedAt    入库时刻；null 表示要「清空」这一列
     */
    public void write(Long attachmentId, String status, String docId, LocalDateTime indexedAt) {
        if (attachmentId == null) {
            return;
        }
        Attachment update = new Attachment();
        update.setId(attachmentId);
        update.setAiIndexStatus(status);
        update.setAiDocId(docId);
        update.setAiIndexedAt(indexedAt);
        // updateById 忽略 null：doc_id / indexed_at 需要能"清空"，
        // 所以当它们为 null 时用 wrapper 显式置 NULL
        attachmentMapper.updateById(update);
        if (docId == null) {
            attachmentMapper.update(null,
                    new LambdaUpdateWrapper<Attachment>()
                            .eq(Attachment::getId, attachmentId)
                            .set(Attachment::getAiDocId, null));
        }
        if (indexedAt == null) {
            attachmentMapper.update(null,
                    new LambdaUpdateWrapper<Attachment>()
                            .eq(Attachment::getId, attachmentId)
                            .set(Attachment::getAiIndexedAt, null));
        }
    }
}
