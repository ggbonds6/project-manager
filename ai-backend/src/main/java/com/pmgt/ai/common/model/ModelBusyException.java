package com.pmgt.ai.common.model;

/**
 * 某个模型的在飞槽位已满、等不到 permit（P0-5 的"明确 503，而不是排队到超时"）。
 *
 * <p>为什么要有它：没有它，超出容量的请求会一直挂在 Tomcat 线程上直到用户 180s 超时，
 * 而**用户已经放弃、槽位却还占着**（《AI模型调用与任务队列评估》§3.7 第 4 条）。
 * 有了它，调用方立刻拿到"当前繁忙"，把稀缺槽位留给真正能跑完的请求。
 *
 * <p>{@code Web} 层把它映射成 <b>503 + "模型繁忙"</b>（见 {@code GlobalExceptionHandler}）。
 */
public class ModelBusyException extends RuntimeException {

    private final String model;

    public ModelBusyException(String model, String message) {
        super(message);
        this.model = model;
    }

    /** 哪个模型繁忙（{@code chat} / {@code ocr} / {@code embed} / {@code rerank}），供日志与响应使用。 */
    public String getModel() {
        return model;
    }
}
