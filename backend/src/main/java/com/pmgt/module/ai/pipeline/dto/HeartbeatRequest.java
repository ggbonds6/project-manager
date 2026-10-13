package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** {@code POST /api/ai/pipeline/runs/{run_id}/heartbeat} 的请求体（续约）。 */
@Data
public class HeartbeatRequest {

    /**
     * 领取者标识（<b>可选</b>）。
     *
     * <p>带了就必须与领取时一致，否则视为"不是你领的"（409）——这是给"将来 AI 多实例"
     * 准备的防串写；不带则只校验"这一行仍是 RUNNING 且租约未过期"。
     * AI 侧当前对心跳发的是空 body（它用服务间令牌证明身份），所以这里不能强制要求。
     */
    @JsonProperty("claimed_by")
    @JsonAlias("claimedBy")
    private String claimedBy;

    /** 想续到多久之后（秒）；留空用主系统配置值。 */
    @JsonProperty("lease_seconds")
    @JsonAlias("leaseSeconds")
    private Integer leaseSeconds;
}
