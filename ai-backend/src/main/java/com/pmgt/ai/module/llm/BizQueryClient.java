package com.pmgt.ai.module.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 受控查询的**回调客户端**：把 {@code query_business_data} 的一次调用翻译成
 * {@code POST {biz_query.url}/{entity}}（契约见 {@code docs/AI前端与集成方案.md} §11.3 / §11.4）。
 *
 * <h2>边界（§8.2 / §11.1 的既定边界，别越）</h2>
 *
 * <p>AI 服务<b>不直连库、不生成 SQL</b>：这里只发一个受控 HTTP 请求，能不能查、能查谁，
 * 全在主系统（它按 {@code scope_token} 里的 {@code projects} 判定，越界一律 403）。
 * 本类不解析 JWT、不校验权限——**授权是主系统的事**，AI 侧只负责"把话说清楚"。
 *
 * <h2>错误映射（§11.6：错误必须"教会模型"）</h2>
 *
 * <table border="1">
 *   <caption>四种结果，四种说法</caption>
 *   <tr><th>情况</th><th>返回给模型</th><th>为什么</th></tr>
 *   <tr><td>200 + 有行</td><td>{@code rows/unit/caliber/data_time/scope} 原样带出</td>
 *       <td>口径与数据时间是硬要求，模型要能原话转述，数字才可审计</td></tr>
 *   <tr><td>200 + 空行</td><td>{@code rows: [] + note:"该范围内没有匹配数据"}</td>
 *       <td>不是错误：模型必须能区分"没数据"与"调用失败"，前者要如实说"系统里没有"</td></tr>
 *   <tr><td>4xx（含 403）</td><td>{@code {"error": "主系统拒绝（HTTP 4xx）：&lt;对方原话&gt;…"}}</td>
 *       <td>4xx 是"你参数/范围不对"，把对方原话带上，模型才知道怎么改（403 则明确"无权"而非"没有"）</td></tr>
 *   <tr><td>5xx / 连不上 / 超时</td><td>{@code {"error": "…这是调用失败，不代表「系统里没有」"}}</td>
 *       <td><b>绝不能静默变成"未找到"</b>——否则用户会把"系统坏了"当成"确实没有"</td></tr>
 * </table>
 *
 * <p>任何异常都在本类内部被吞成结构化错误：工具执行永不抛异常，
 * 一次查询失败不会中断整轮问答（沿用 {@link Tools#execute} 的既有做法）。
 */
@Component
public class BizQueryClient {

    /** {@code limit} 默认值——与 §11.3 写死的契约一致，不给模型"随便多要"的空间。 */
    public static final int DEFAULT_LIMIT = 20;

    /** {@code limit} 上限——同上，是契约值不是调优值，改它要同步改文档与主系统。 */
    public static final int MAX_LIMIT = 100;

    /** 空结果的说明文案（§11.6 指定原话，前端/评测按它判定"没数据"）。 */
    public static final String EMPTY_NOTE = "该范围内没有匹配数据";

    /** 发生"调用失败"时统一追加的一句：防止模型把失败说成"没有"。 */
    private static final String NOT_FOUND_REMINDER =
            " ——这是调用失败，不代表「系统里没有」；请如实说明系统数据这次没查到，不要用文档内容或常识替代。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 出错时贴进错误消息的响应体上限（够定位，又不把上下文冲爆）。 */
    private static final int ERROR_BODY_CHARS = 300;

    private final AiSettings settings;
    private final HttpClient http;

    public BizQueryClient(AiSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                // 连接超时与"读超时"分开：连不上要快速失败（内网回调，5s 足够），
                // 慢的是主系统那条受控查询，交给请求级 timeoutSeconds。
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 查一次业务数据。
     *
     * @param spec   本次问答的受控查询通道（请求级，不能缓存）
     * @param entity 已由 {@link Tools} 校验过的枚举值（projects/contracts/payments/stats）
     * @param filters 结构化过滤字段（字段清单由主系统定义，见 §11.4）；{@code null} 视为空
     * @param limit   条数上限；{@code null}/非正数取 {@link #DEFAULT_LIMIT}，超过 {@link #MAX_LIMIT} 按上限截断
     * @return 给模型看的结果 Map：正常结果是 {@code rows/unit/caliber/data_time/scope}，
     *         任何失败都是 {@code {"error": ...}}（<b>不抛异常</b>）
     */
    public Map<String, Object> query(
            BizQuerySpec spec, String entity, Map<String, Object> filters, Integer limit) {

        if (spec == null || !spec.usable()) {
            return error("本次问答没有可用的业务数据查询通道（biz_query.url / scope_token 缺失），"
                    + "无法查询系统数据；请如实告知用户系统数据暂时查不到，不要用文档内容或常识顶替。");
        }

        int usedLimit = normalizeLimit(limit);
        String endpoint = endpoint(spec.url(), entity);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(Math.max(1, timeoutSeconds()) * 1000L))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                // scope_token 是唯一授权凭据：只走 Header，绝不进 body/URL/日志
                .header("Authorization", "Bearer " + spec.scopeToken())
                .POST(HttpRequest.BodyPublishers.ofString(payload(filters, usedLimit), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            return error("调用主系统业务查询接口被中断（InterruptedException）" + NOT_FOUND_REMINDER);
        } catch (Exception exc) {
            return error("调用主系统业务查询接口失败（" + exc.getClass().getSimpleName()
                    + (exc.getMessage() == null ? "" : ": " + exc.getMessage()) + "）" + NOT_FOUND_REMINDER);
        }

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        String words = originalWords(body);

        if (status == 403) {
            return error("主系统拒绝本次查询（HTTP 403）：" + words
                    + " ——这是**授权范围**问题（不是「没有数据」）：请如实告知用户该项数据不在本次授权范围内，"
                    + "不要改参数重试，也不要用文档内容或常识顶替。");
        }
        if (status >= 400 && status < 500) {
            return error("主系统拒绝本次查询（HTTP " + status + "）：" + words
                    + " ——请按上面的提示修正 filters（字段以该 entity 支持的为准）后重试一次；"
                    + "仍失败就如实说明，不要编造数据。");
        }
        if (status < 200 || status >= 300) {
            return error("查询主系统失败（HTTP " + status + "）：" + words + NOT_FOUND_REMINDER);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception exc) {
            return error("主系统返回的不是合法 JSON（HTTP 200）：" + abbreviate(body) + NOT_FOUND_REMINDER);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return error("主系统返回了空响应（HTTP 200）" + NOT_FOUND_REMINDER);
        }

        // 统一返回形状是 {"code":0,"data":{...}}；code 非 0 说明主系统自己在业务层拒了
        if (root.has("code") && root.path("code").asInt(0) != 0) {
            return error("主系统查询返回失败（code=" + root.path("code").asInt() + "）：" + originalWords(body)
                    + " ——请按上面的提示修正 filters 后重试一次；这不是「没有数据」。");
        }
        JsonNode data = root.has("code") ? root.path("data") : root;
        return shape(entity, data, usedLimit, body);
    }

    // ══════════════════════════════════════════════════════════════════
    // 结果整形
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把主系统的 {@code {rows, unit, caliber, data_time, scope}} <b>原样</b>带出。
     *
     * <p>原样是刻意的：{@code caliber}（口径）、{@code data_time}（数据时间）、{@code scope}（范围）
     * 是 §11.4 的硬要求——模型必须能原话转述，否则用户拿到的数字无法审计。
     * 这里只做"换个容器"，不做任何格式规范化（金额/时间一律不转换）。
     *
     * <p>⚠️ 200 但没有 {@code rows} 字段时<b>不能当空结果</b>：那说明接口形状变了，
     * 静默按"没有数据"回答正是 §11 要避免的事，所以按错误返回。
     */
    private Map<String, Object> shape(String entity, JsonNode data, int usedLimit, String rawBody) {
        JsonNode rowsNode = data != null && data.isObject() ? data.get("rows") : null;
        if (rowsNode == null && data != null && data.isArray()) {
            rowsNode = data; // 容忍"直接返回数组"的简化实现
        }
        if (rowsNode == null || !rowsNode.isArray()) {
            return error("主系统返回的结构不符合契约（HTTP 200 但缺少 rows 字段，预期 "
                    + "{rows,unit,caliber,data_time,scope}）：" + abbreviate(rawBody) + NOT_FOUND_REMINDER);
        }

        List<Map<String, Object>> rows = MAPPER.convertValue(
                rowsNode, new TypeReference<List<Map<String, Object>>>() { });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", entity);
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("limit", usedLimit);
        out.put("unit", text(data, "unit"));
        out.put("caliber", text(data, "caliber"));
        out.put("data_time", text(data, "data_time"));
        out.put("scope", text(data, "scope"));
        if (rows.isEmpty()) {
            // 空结果不是错误（§11.6）：模型要能区分"没数据"与"调用失败"
            out.put("note", EMPTY_NOTE);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════════════

    /**
     * 请求体：{@code {"filters": {...}, "limit": n}}。
     *
     * <p>{@code filters} 一定是对象（没有过滤条件就是 {@code {}}）：主系统按结构化字段收参，
     * <b>不收 SQL/表达式/字段名拼接</b>，所以这里只负责形状，不做任何字段白名单（清单在主系统侧）。
     */
    String payload(Map<String, Object> filters, int limit) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("filters", filters == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(filters));
        body.put("limit", limit);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception exc) {
            // 理论上不可达（都是 JSON 原生类型）；真发生了就让上层按空 filters 重试
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.set("filters", MAPPER.createObjectNode());
            fallback.put("limit", limit);
            return fallback.toString();
        }
    }

    /** {@code {url}/{entity}}，容忍主系统给的地址带/不带尾斜杠。 */
    static String endpoint(String url, String entity) {
        String base = url == null ? "" : url.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + entity;
    }

    /** {@code limit} 归一：缺省/非正 → 20；超上限 → 100（契约值，见 §11.3）。 */
    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 超时（秒）取 {@code ai.biz-query.timeout-seconds}（默认 20，见 {@link AiSettings.BizQuery}）。
     *
     * <p>为什么 15~30s：主系统侧是"一条受控 SQL + 写 operate_log"，正常亚秒级；
     * 这个量级既留了并发/慢查询的余量，又远小于网关的 300s——
     * 回调一旦卡死，宁可这轮问答明说"系统数据没查到"，也不能把整轮问答拖成几分钟。
     */
    private long timeoutSeconds() {
        int configured = settings == null || settings.getBizQuery() == null
                ? 0
                : settings.getBizQuery().getTimeoutSeconds();
        return configured > 0 ? configured : 20;
    }

    private static String text(JsonNode data, String key) {
        if (data == null || !data.isObject()) {
            return "";
        }
        JsonNode node = data.get(key);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    /** 取"对方原话"：优先结构化错误字段，取不到就把响应体原文截断带上。 */
    static String originalWords(String rawBody) {
        String body = rawBody == null ? "" : rawBody.strip();
        if (body.isEmpty()) {
            return "（响应体为空）";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            for (String key : List.of("msg", "message", "detail", "error", "error_message", "data")) {
                JsonNode node = root.get(key);
                if (node != null && node.isTextual() && !node.asText("").isBlank()) {
                    return node.asText("").strip();
                }
            }
        } catch (Exception ignored) {
            // 不是 JSON：下面按纯文本处理
        }
        return abbreviate(body);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= ERROR_BODY_CHARS
                ? oneLine
                : oneLine.substring(0, ERROR_BODY_CHARS) + "…";
    }

    /** 与 {@link Tools} 同样的错误形状：{@code {"error": "..."}}，模型只需认一个键。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        return out;
    }
}
