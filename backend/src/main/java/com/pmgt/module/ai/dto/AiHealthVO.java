package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #1 {@code GET /api/ai/health} 的响应 {@code data}。
 *
 * <p>该接口在主系统 <b>AI 关闭</b>或 <b>AI 服务不可达</b>时也必须正常返回（HTTP 200、code=0），
 * 只是 {@code available=false} 并把原因写进 {@code message}：
 * 「自检」这个动作本身就是要回答「能不能用」，若它自己报错，运维反而看不出问题在哪。
 */
@Data
public class AiHealthVO {

    /** 主系统这一侧判定：开关打开 + 能连上 AI 服务 + AI 服务的 OCR 探针可用。 */
    private boolean available;
    /** 实际调用的基址（便于确认连的是哪个环境）。 */
    private String aiServiceBaseUrl;
    /** AI 服务当前的检索引擎：local / opensearch（拿不到时为 null）。 */
    private String vectorBackend;
    /** 平台（网关）是否可达：取自 AI 服务 /health 的 OCR 探针。 */
    private Boolean platformReachable;
    /** 各模型可用性。 */
    private Models models;
    /** AI 服务侧已入库文档数（不可达时为 null）。 */
    private Integer documentCount;
    /** 主系统侧未结束的解析任务数（不依赖 AI 服务，永远有值）。 */
    private Long pendingTaskCount;
    /** 探测时间 {@code yyyy-MM-dd HH:mm:ss}。 */
    private String checkedAt;
    /** 中文说明：可用时为「正常」，不可用时写明原因（关闭 / 连接失败 / 平台不可达）。 */
    private String message;

    /** §9 的 {@code models:{chat,ocr,embedding,reranker}}。 */
    @Data
    public static class Models {
        private Boolean chat;
        private Boolean ocr;
        private Boolean embedding;
        private Boolean reranker;
    }
}
