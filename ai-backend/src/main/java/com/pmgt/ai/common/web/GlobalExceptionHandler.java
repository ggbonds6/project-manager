package com.pmgt.ai.common.web;

import com.pmgt.ai.common.model.ModelBusyException;
import com.pmgt.ai.module.retrieval.VecClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 统一错误响应：{@code {"detail": "..."}}（对齐 FastAPI 的默认形状）。
 *
 * <p>为什么要单独处理上传超限：Spring 抛的是 {@code MaxUploadSizeExceededException}，
 * 不处理就落到通用 500「系统繁忙」，使用者根本看不出是"文件太大"（Python 版也是踩过这个坑后补的）。
 *
 * <p>另外两条（2026-10-10，P0）：
 * <ul>
 *   <li><b>向量后端不可用 → 503</b>：{@code VEC_BACKEND=opensearch} 而集群连不上/401/5xx 时，
 *       响应里必须<b>看得见"向量后端不可用"</b>，而不是一句含糊的"系统繁忙"（更不能是 200 的降级结果）；</li>
 *   <li><b>模型繁忙 → 503</b>：等不到每模型信号量的 permit 时明确告知"当前繁忙、可重试"，
 *       而不是把请求挂在 Tomcat 线程上直到用户超时。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("detail", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("detail", "文件超过上传上限（500MB），请压缩或拆分后重试"));
    }

    /** 向量设施（OpenSearch / 向量化 / 重排）不可用：503 + 原样带上"哪个环节 + 怎么办"。 */
    @ExceptionHandler(VecClient.VecException.class)
    public ResponseEntity<Map<String, String>> handleVec(VecClient.VecException e) {
        String detail = String.valueOf(e.getMessage());
        if (!detail.startsWith("向量后端不可用") && !detail.startsWith("向量服务")) {
            detail = "向量后端不可用：" + detail;
        }
        log.warn("向量后端不可用：{}", detail);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", detail));
    }

    /** 模型槽位已满：503 + "模型繁忙"，让调用方稍后重试（而不是排队到超时）。 */
    @ExceptionHandler(ModelBusyException.class)
    public ResponseEntity<Map<String, String>> handleBusy(ModelBusyException e) {
        log.warn("模型繁忙：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", e.getMessage(), "model", e.getModel()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        log.error("未处理异常", e);
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("detail", detail));
    }
}
