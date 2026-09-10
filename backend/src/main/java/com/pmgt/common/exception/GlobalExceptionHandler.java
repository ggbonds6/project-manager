package com.pmgt.common.exception;

import com.pmgt.common.api.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return R.fail(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return R.fail(400, "请求体格式错误");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        return R.fail(404, "资源不存在");
    }

    /** 单文件超过 spring.servlet.multipart.max-file-size */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return R.fail(400, UPLOAD_TOO_LARGE_MSG);
    }

    /** multipart 解析失败（含 Tomcat 的 FileSizeLimitExceededException 包装） */
    @ExceptionHandler(MultipartException.class)
    public R<Void> handleMultipart(MultipartException e) {
        log.warn("multipart 请求解析失败: {}", e.getMessage());
        if (looksLikeSizeExceeded(e)) {
            return R.fail(400, UPLOAD_TOO_LARGE_MSG);
        }
        return R.fail(400, "上传请求解析失败，请重试或换用更小的文件");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        // 兜底：Tomcat 在某些路径下会把超限包成 IllegalStateException，
        // 若不在通用分支里识别，用户只会看到无用的「系统繁忙」
        if (looksLikeSizeExceeded(e)) {
            log.warn("上传文件超过大小限制（通用分支）: {}", e.getMessage());
            return R.fail(400, UPLOAD_TOO_LARGE_MSG);
        }
        log.error("未捕获异常", e);
        return R.fail(500, "系统繁忙，请稍后再试");
    }

    /** 单文件上传上限（与 application.yml 的 max-file-size 保持一致） */
    private static final String MAX_UPLOAD_TEXT = "500MB";

    private static final String UPLOAD_TOO_LARGE_MSG =
            "上传文件过大，单文件上限 " + MAX_UPLOAD_TEXT + "，请压缩或拆分后重试";

    /** 判断异常链里是否是「超过上传大小限制」 */
    private static boolean looksLikeSizeExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MaxUploadSizeExceededException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("exceeds its maximum permitted size")
                    || msg.contains("FileSizeLimitExceeded"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
