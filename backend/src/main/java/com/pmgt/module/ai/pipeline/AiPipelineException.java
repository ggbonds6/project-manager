package com.pmgt.module.ai.pipeline;

/**
 * 流水线回写接口的拒绝异常——<b>必须带真实 HTTP 状态码</b>。
 *
 * <h2>为什么不复用 {@code BizException}</h2>
 *
 * <p>主系统对浏览器前端的既有约定是「HTTP 200 + 信封 {@code code}」（见
 * {@code GlobalExceptionHandler}）。但 {@code /api/ai/pipeline/**} 的调用方是 <b>AI 服务</b>，
 * 它必须能按下述语义分流（契约见 docs/RAG流水线设计与并发管理.md §4.4.3 方案 B）：
 * <ul>
 *   <li><b>400</b>：报文/状态迁移非法——这是"你写错了，按提示改一次"，<b>不要重试</b>；
 *       状态迁移非法的文案里必须带上<b>允许的目标状态</b>，否则 AI 侧只能盲试；</li>
 *   <li><b>401</b>：服务间令牌缺失/不对——别重试，先修配置；</li>
 *   <li><b>403</b>：令牌有效但<b>不属于这次 run</b>（pipeline_token 与 run_id 不匹配）；</li>
 *   <li><b>404</b>：run/task 不存在，或<b>当前没有可领取的任务</b>（空队列不是错误，
 *       但要能被明确区分于"领到了"）；</li>
 *   <li><b>409</b>：租约已失效/状态已被别的写入者改掉——AI 应<b>停下并放回</b>（协作式）；</li>
 *   <li><b>500</b>：内部错误（数据库异常等），可作为"调用失败"与"没有这条数据"区分。</li>
 * </ul>
 *
 * <p>它<b>不</b>继承 {@code BizException}：后者会被既有的「BizException → HTTP 200」处理器接走。
 * 由 {@code GlobalExceptionHandler} 单独处理，响应体仍是仓库统一信封 {@code {code,message,data}}。
 */
public class AiPipelineException extends RuntimeException {

    /** 报文非法 / 非法状态迁移 / 未知枚举值。 */
    public static final int BAD_REQUEST = 400;
    /** 服务间令牌缺失或不正确。 */
    public static final int UNAUTHORIZED = 401;
    /** 令牌有效但不属于该 run。 */
    public static final int FORBIDDEN = 403;
    /** run/task 不存在，或没有可领取的任务。 */
    public static final int NOT_FOUND = 404;
    /** 租约失效 / 并发状态冲突（调用方应停止并放回）。 */
    public static final int CONFLICT = 409;
    /** 内部错误。 */
    public static final int INTERNAL = 500;

    private final int status;

    public AiPipelineException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static AiPipelineException badRequest(String message) {
        return new AiPipelineException(BAD_REQUEST, message);
    }

    public static AiPipelineException unauthorized(String message) {
        return new AiPipelineException(UNAUTHORIZED, message);
    }

    public static AiPipelineException forbidden(String message) {
        return new AiPipelineException(FORBIDDEN, message);
    }

    public static AiPipelineException notFound(String message) {
        return new AiPipelineException(NOT_FOUND, message);
    }

    public static AiPipelineException conflict(String message) {
        return new AiPipelineException(CONFLICT, message);
    }

    public static AiPipelineException internal(String message) {
        return new AiPipelineException(INTERNAL, message);
    }
}
