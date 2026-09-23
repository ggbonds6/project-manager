package com.pmgt.module.ai.query;

/**
 * 受控查询的拒绝异常——<b>必须带真实 HTTP 状态码</b>。
 *
 * <h2>为什么不复用 {@code BizException}（以及它 HTTP 200 的既有约定）</h2>
 *
 * <p>主系统对浏览器前端的既有约定是「HTTP 200 + 信封 {@code code}」（见
 * {@code GlobalExceptionHandler}：{@code R.fail(403, ...)} 仍是 200）。那套约定对前端够用，
 * 因为前端每次都会解包信封。
 *
 * <p>但受控查询的调用方是 <b>AI 服务</b>，它按 HTTP 状态分流（契约 §11.6 写的是
 * 「范围外 → 403」「filters 非法 → 400」），并且 <b>403 与 400 的处理方式完全不同</b>：
 * 403 是"授权范围问题，不要改参数重试"，400 是"参数写错了，按提示改一次"。
 * 若都包成 HTTP 200，调用方只能看到 {@code code != 0} 这一种情况，会把越权
 * 误判成"参数不对、可以再试"，还会把"没有权限"说成"系统里没有"——
 * 这正是 §11 反复要求避免的那类错误。
 *
 * <p>所以：本异常由 {@code GlobalExceptionHandler} 单独处理，<b>原样设置 HTTP 状态码</b>，
 * 同时响应体仍是仓库统一的 {@code {code, message, data}} 信封（状态码与 code 一致），
 * 调用方读哪一边都拿得到同一句话。它<b>不</b>继承 {@code BizException}，
 * 以免被既有的「BizException → 200」处理器接走（Spring 选最具体处理器，
 * 但显式不继承更不容易被后人误改）。
 */
public class AiQueryException extends RuntimeException {

    /** 缺少/无效作用域令牌。 */
    public static final int UNAUTHORIZED = 401;
    /** filters 非法（字段不在白名单 / 类型不对 / 缺少必填）。 */
    public static final int BAD_REQUEST = 400;
    /** 越权：请求里的 projectId 不在 scope_token 的 projects 内。 */
    public static final int FORBIDDEN = 403;
    /** 查询本身执行失败（数据库/内部错误）。 */
    public static final int INTERNAL = 500;

    private final int status;

    public AiQueryException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** 401：令牌缺失、过期、签名不对、或根本不是作用域令牌。 */
    public static AiQueryException unauthorized(String message) {
        return new AiQueryException(UNAUTHORIZED, message);
    }

    /** 400：filters 非法；message 必须写清该 entity 支持哪些字段（§11.6「错误要教会模型」）。 */
    public static AiQueryException badRequest(String message) {
        return new AiQueryException(BAD_REQUEST, message);
    }

    /** 403：范围外；文案按 §11.6 定死，且绝不携带任何数据。 */
    public static AiQueryException forbidden(String message) {
        return new AiQueryException(FORBIDDEN, message);
    }

    /**
     * 500：查询执行失败（数据库异常等）。
     *
     * <p>必须是 5xx 而不是"200 + code≠0"：调用方要把"这次没查到（调用失败）"与
     * "系统里就没有这条数据"分开，也不能把内部错误当成"参数不对、改改 filters 再试"。
     */
    public static AiQueryException internal(String message) {
        return new AiQueryException(INTERNAL, message);
    }
}
