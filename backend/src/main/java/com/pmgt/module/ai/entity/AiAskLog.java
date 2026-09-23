package com.pmgt.module.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 问答留痕（V13）。
 *
 * <p>为什么每次问答都要落一行（不是「可选日志」）：
 * <ul>
 *   <li>AI 问答会触及「不该看的项目文档」这类风险，出了问题必须能倒查
 *       <b>谁、何时、问什么、当时被允许的范围是什么</b>——范围（{@code docIds}）尤其关键，
 *       因为它是主系统解析出来的，事后无法复算（附件可能已被删/重新解析）；</li>
 *   <li>「答不出来」的责任划分需要耗时与降级标记：是 AI 服务慢，还是向量检索降级成了关键词，
 *       日志里必须能看出来。</li>
 * </ul>
 *
 * <p>不继承 {@code BaseEntity}：留痕只追加、不更新也不逻辑删除（审计数据不允许被改），
 * 只需要一个提问时间。
 */
@Data
@TableName("ai_ask_log")
public class AiAskLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String userName;
    /** 用户原始问题。 */
    private String question;
    /** 作用域：项目 id（未限定时为空）。 */
    private Long projectId;
    /** 作用域：用户指定的附件 id（英文逗号分隔）。 */
    private String attachmentIds;
    /** 实际发给 AI 服务的 doc_id（英文逗号分隔）——事后复核权限判定的依据。 */
    private String docIds;
    /** 引用条数。 */
    private Integer citedCount;
    private Long elapsedMs;
    /** 是否降级（向量检索不可用等）：1 是 / 0 否。 */
    private Integer degraded;
    /** 降级/异常提示原文。 */
    private String notice;
    /** 答案摘要（前 200 字），仅供日志快速浏览。 */
    private String answerDigest;

    /**
     * 本次问答调用主系统受控查询（{@code /api/ai/query/*}）的次数（V14）。
     *
     * <p>不是从 AI 服务自报的 toolTrace 里数的，而是主系统自己的计数
     * （见 {@code AiQueryUsageTracker}）：审计数据不能依赖被审计方自报，
     * 且 toolTrace 是给前端看的展示数据、字段随时可能变。
     *
     * <p>0 表示这次问答没有用系统数据（P0/P1 的存量行为）。
     */
    private Integer bizQueryCount;

    /**
     * 本次问答查过的 entity 去重清单（英文逗号分隔，V14）。
     *
     * <p>未发生受控查询时为 {@code null}（而不是空串）：日志查询里
     * 「没用过系统数据」与「用了但没记下 entity」必须能分开。
     */
    private String bizEntities;

    /** 提问时间。 */
    private LocalDateTime createTime;
}
