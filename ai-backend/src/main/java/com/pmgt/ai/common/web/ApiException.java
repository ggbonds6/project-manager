package com.pmgt.ai.common.web;

/**
 * 业务异常：HTTP 状态码 + 给调用方看的说明。
 *
 * <p>对齐 Python（FastAPI）的行为：非 2xx 时响应体是 {@code {"detail": "..."}}，
 * 状态码就是 400/404/413 这类具体值——主系统与前端按这个形状解析错误，换语言不该让它们改代码。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, detail);
    }

    public static ApiException tooLarge(String detail) {
        return new ApiException(413, detail);
    }

    /**
     * 队列已满（<b>诚实失败</b>，P0-4）：429 Too Many Requests。
     *
     * <p>为什么不是 500：500 让调用方以为"服务坏了"，而事实是"服务很好、只是排队满了，
     * 稍后重试即可"。文案里必须带当前深度（"队列已满 100/100"），运维一眼能判断该扩容还是该等。
     */
    public static ApiException queueFull(String detail) {
        return new ApiException(429, detail);
    }

    /**
     * 模型/后端繁忙或不可用（P0-5、OpenSearch 降级口径）：503 Service Unavailable。
     *
     * <p>用它而不是 500：① 语义上"暂时不可用、可重试"；② 与"向量后端不可用"的明确报错一致，
     * <b>绝不静默回退</b>（既不是 200 的降级结果，也不是含糊的"系统繁忙"）。
     */
    public static ApiException unavailable(String detail) {
        return new ApiException(503, detail);
    }
}
