package com.pmgt.module.attach;

/**
 * 附件可用性探测接口的<b>参数错误</b>——返回真实 HTTP 400。
 *
 * <h2>为什么不复用 {@code BizException}</h2>
 *
 * <p>主系统对浏览器前端的既有约定是「HTTP 200 + 信封 {@code code}」（见
 * {@code GlobalExceptionHandler}）。这里刻意偏离一次，理由只有一条：
 * 该接口是**探测**语义，前端在探测失败时必须能区分「我参数写错了（id 为空/超上限/不是数字）」
 * 与「服务端/存储出了故障」，前者不该被当成"附件列表有问题"去重试。
 * 真实 400 + 统一信封响应体（{@code {code,message,data}}）是最省事的区分方式，
 * 与 {@code AiQueryException}/{@code AiPipelineException} 同一取舍（那两处的调用方是 AI 服务）。
 *
 * <p>除参数错误外，本接口**不抛**这个异常：单条附件探测失败一律落在
 * {@code available=false} 上（见 {@code AttachmentAvailabilityService}），
 * 不能因为一条附件读不到就让整个列表的探测失败。
 */
public class AttachmentAvailabilityException extends RuntimeException {

    /** 参数错误（ids 为空 / 非数字 / 超过单次上限）。 */
    public static final int BAD_REQUEST = 400;

    public AttachmentAvailabilityException(String message) {
        super(message);
    }
}
