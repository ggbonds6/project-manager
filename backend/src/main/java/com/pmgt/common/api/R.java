package com.pmgt.common.api;

import lombok.Data;

/**
 * 统一响应包装：code = 0 表示成功，非 0 为业务/系统错误码。
 */
@Data
public class R<T> {

    public static final int CODE_OK = 0;

    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = CODE_OK;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
