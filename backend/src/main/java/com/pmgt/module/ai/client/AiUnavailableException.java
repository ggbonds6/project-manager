package com.pmgt.module.ai.client;

import com.pmgt.common.exception.BizException;

/**
 * AI 能力服务不可用（连接失败 / 超时 / 5xx / 返回体不可解析）。
 *
 * <p>为什么单独建一个类型而不是直接抛 {@link BizException}：
 * 「AI 服务不可用」和「业务校验不通过」在接口层的处置完全不同——
 * 前者必须<b>显式</b>告诉用户「服务不可用」（方案 §6 的红线：不许降级成「未找到」，
 * 否则用户会以为文档里真的没有答案），后者是普通 400/403。
 * 有了独立类型，调用方与服务层可以据此决定文案与码值，且不会被通用异常兜底吞掉。
 */
public class AiUnavailableException extends BizException {

    /**
     * 用 503（服务不可用）作为码值，与仓库既有约定（400/401/403/404/500）语义一致：
     * 前端可据此把提示语与「参数错误」区分开。
     */
    public static final int CODE = 503;

    private final String reason;

    public AiUnavailableException(String reason) {
        // message 直接给用户看：明确写「AI 服务不可用」，不能含糊成「未找到」
        super(CODE, "AI 服务不可用：" + reason);
        this.reason = reason;
    }

    public AiUnavailableException(String reason, Throwable cause) {
        super(CODE, "AI 服务不可用：" + reason);
        this.reason = reason;
        initCause(cause);
    }

    public String getReason() {
        return reason;
    }
}
