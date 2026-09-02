package com.pmgt.common.exception;

import lombok.Getter;

/**
 * 业务异常：message 会原样返回给前端。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
