package com.pmgt.module.ai.query;

import lombok.Data;

import java.util.Map;

/**
 * 受控查询请求体（§11.3）：{@code {"filters": {...}, "limit": 20}}。
 *
 * <p>{@code filters} 用 {@code Map} 承接（而不是为每个 entity 写 DTO）是<b>刻意的</b>：
 * 字段白名单必须按 entity 分别校验，并且非法字段要能<b>原样回显</b>给模型看
 * （"不支持查询字段「sql」"），用 Map 才能把"模型到底传了什么"完整拿到。
 * 若用强类型 DTO + {@code @JsonIgnoreProperties(ignoreUnknown=true)}，
 * 非法字段会被静默丢掉——那正是契约禁止的"悄悄接受"。
 */
@Data
public class AiQueryRequest {

    /**
     * 结构化过滤条件；缺省/null 等价于空条件 {@code {}}。
     *
     * <p>类型刻意是 {@code Map<String, Object>}：{@code filters} 传成字符串/数组时会绑定失败，
     * 由 {@code GlobalExceptionHandler} 以 HTTP 400 + 可照抄的示例拒绝（而不是被忽略）。
     */
    private Map<String, Object> filters;

    /**
     * 返回行数上限（可选，默认 20、上限 100，§11.3）。
     *
     * <p>越界不报错而是夹到合法区间：模型多要几行不是"非法请求"，
     * 而让整次问答因为一个 limit 失败，代价远大于少给几行。
     */
    private Integer limit;
}
