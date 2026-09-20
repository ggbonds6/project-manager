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
}
