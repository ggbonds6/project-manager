package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * {@code POST /api/ai/pipeline/claim} 的请求体。
 *
 * <p>字段名以 <b>snake_case 为主名</b>，同时用 {@link JsonAlias} 接受 camelCase——
 * 这正是 AI 侧 {@code doc_ids}/{@code docIds} 踩过的那个坑（契约写 snake_case、
 * Java 字段是 camelCase，发出来被静默忽略、范围悄悄放宽）：这里两个都收，收不到才算错。
 */
@Data
public class ClaimRequest {

    /** 只要这一类任务（PARSE/RETRY/REINDEX/EMBED/EVAL）；留空 = 不限分类。 */
    @JsonProperty("task_type")
    @JsonAlias("taskType")
    private String taskType;

    /**
     * 本次最多领取几个任务。
     *
     * <p>当前实现只支持 {@code 1}（见 {@code AiPipelineClaimService} 的说明：
     * 响应契约是单个 {@code {run_id, task_id, …}}，领多个就得改成数组、与冻结契约冲突）；
     * 传其它值一律 400 并说明原因，不做"静默夹到 1"。
     */
    @JsonProperty("limit")
    @JsonAlias("limit")
    private Integer limit;

    /** 领取者标识（主机名 + 进程/节点 id）；必填，写进 {@code claimed_by} 与事件流。 */
    @JsonProperty("claimed_by")
    @JsonAlias("claimedBy")
    private String claimedBy;

    /** 租约秒数；留空用主系统配置的 {@code pm.ai.pipeline.lease-seconds}（默认 90）。 */
    @JsonProperty("lease_seconds")
    @JsonAlias("leaseSeconds")
    private Integer leaseSeconds;
}
