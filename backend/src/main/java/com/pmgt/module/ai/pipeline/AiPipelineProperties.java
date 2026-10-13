package com.pmgt.module.ai.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 解析流水线（任务流 / 落库 / 可溯源 / 抢占式领取）配置，前缀 {@code pm.ai.pipeline}。
 *
 * <p>与 {@code AiProperties}（前缀 {@code pm.ai}）分开的理由：这一组值只在
 * {@code /api/ai/pipeline/**} 这条<b>服务间写链路</b>上生效（令牌、租约、投影阈值、版本串），
 * 混进 {@code pm.ai} 会让人误以为它们也影响问答/文档库接口。
 *
 * <p>⚠️ {@link #serviceToken} 是<b>入站</b>校验用的密钥：绝不写进仓库、日志或异常文案
 * （过滤器只记"哪条路径被拒"，异常里只说"未配置/不正确"）。
 */
@ConfigurationProperties(prefix = "pm.ai.pipeline")
public class AiPipelineProperties {

    /**
     * 服务间令牌（入站）：AI 服务在 {@code X-AI-Service-Token} 头里携带它。
     *
     * <p>与出站给 AI 服务的 {@code pm.ai.token} 用<b>同一个环境变量</b>
     * （{@code AI_SERVICE_TOKEN}）：一个令牌两个方向，运维只需管一个值。
     * 区别是这一侧真的会校验（历史缺口：AI 服务此前无入站鉴权，见 ITERATION 登记）。
     *
     * <p><b>留空 = 该前缀全部拒绝（fail closed）</b>：宁可在部署时立刻看到 401，
     * 也不要在"忘了配"的环境里留下一个无人鉴权的写接口。
     */
    private String serviceToken = "";

    /**
     * 领取租约（秒），默认 90：AI 侧每 30s 心跳续约，过期即视为"可重新领取"
     * （§4.4.1 的 T7 stale claim recovery）。夹在 [{@value #MIN_LEASE_SECONDS},
     * {@value #MAX_LEASE_SECONDS}] 内——租约过短会让长解析被别的节点抢走，
     * 过长会让中断的任务迟迟回不到队列。
     */
    private int leaseSeconds = 90;

    /**
     * 投影兜底阈值（秒），默认 300：详情读接口发现某阶段仍是 {@code RUNNING}
     * 且超过该时长没有任何上报 ⇒ 只在<b>展示层</b>标 {@code STALE}
     * （§4.4.3 方案 C 的只读形态），<b>绝不改数据库状态</b>。
     */
    private int staleRunningSeconds = 300;

    /**
     * 流水线版本串，形如 {@code parse=1,clean=0,chunk=0}。
     *
     * <p>它是幂等键 {@code task_key = sha1(content_hash + pipeline_rev)} 的一半：
     * 任一段（解析参数 / 清洗规则 / 切片规则）变化 ⇒ 同一份文件应被视为"需要重建"。
     * 主系统不知道 AI 侧实际用的 embedding 模型，故 {@code embed=...} 那一段由 AI 侧
     * 在上报里带（见 {@code /finish} 的 {@code pipeline_rev} 可选字段），主系统只保证
     * "创建任务时记下自己那一半"。
     */
    private String pipelineRev = "parse=1,clean=0,chunk=0";

    /** 清洗规则版本（V15 {@code clean_rev}）：0 = 未经过清洗（存量行与未开启清洗时的语义）。 */
    private int cleanRev = 0;

    /**
     * 交互式任务优先级（默认 10）：用户在界面点「解析 / 重试」属于交互式，
     * 抢占式领取按 {@code priority DESC} 取，故它插在批量任务前面（§5.2）。
     */
    private int interactivePriority = 10;

    /** 批量任务优先级（默认 0）：上传后自动触发的解析（用户在等的是上传，不是解析）。 */
    private int batchPriority = 0;

    /**
     * 产物摘要上限（字符），默认 4000——与 V15 的 {@code content_json VARCHAR(4000 CHAR)}
     * 对齐：超长就截断，并把真实长度写进 {@code char_count}（全文留在 AI 服务 {@code work/}）。
     */
    private int maxArtifactContentChars = 4000;

    public static final int MIN_LEASE_SECONDS = 15;
    public static final int MAX_LEASE_SECONDS = 3600;
    public static final int MIN_STALE_SECONDS = 30;
    public static final int MAX_STALE_SECONDS = 86_400;
    public static final int MAX_ARTIFACT_CHARS_LIMIT = 4000;

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    /** 生效租约（秒）：把配置夹到合法区间，配置写错也不至于让任务永不回收。 */
    public int effectiveLeaseSeconds() {
        if (leaseSeconds < MIN_LEASE_SECONDS) {
            return MIN_LEASE_SECONDS;
        }
        return Math.min(leaseSeconds, MAX_LEASE_SECONDS);
    }

    public int getStaleRunningSeconds() {
        return staleRunningSeconds;
    }

    public void setStaleRunningSeconds(int staleRunningSeconds) {
        this.staleRunningSeconds = staleRunningSeconds;
    }

    /** 生效投影阈值（秒）。 */
    public int effectiveStaleRunningSeconds() {
        if (staleRunningSeconds < MIN_STALE_SECONDS) {
            return MIN_STALE_SECONDS;
        }
        return Math.min(staleRunningSeconds, MAX_STALE_SECONDS);
    }

    public String getPipelineRev() {
        return pipelineRev;
    }

    public void setPipelineRev(String pipelineRev) {
        this.pipelineRev = pipelineRev;
    }

    public int getCleanRev() {
        return cleanRev;
    }

    public void setCleanRev(int cleanRev) {
        this.cleanRev = cleanRev;
    }

    public int getInteractivePriority() {
        return interactivePriority;
    }

    public void setInteractivePriority(int interactivePriority) {
        this.interactivePriority = interactivePriority;
    }

    public int getBatchPriority() {
        return batchPriority;
    }

    public void setBatchPriority(int batchPriority) {
        this.batchPriority = batchPriority;
    }

    public int getMaxArtifactContentChars() {
        return maxArtifactContentChars;
    }

    public void setMaxArtifactContentChars(int maxArtifactContentChars) {
        this.maxArtifactContentChars = maxArtifactContentChars;
    }

    /** 生效的产物摘要上限：只能配小，不能超过 DDL 的 4000。 */
    public int effectiveMaxArtifactContentChars() {
        if (maxArtifactContentChars <= 0) {
            return MAX_ARTIFACT_CHARS_LIMIT;
        }
        return Math.min(maxArtifactContentChars, MAX_ARTIFACT_CHARS_LIMIT);
    }
}
