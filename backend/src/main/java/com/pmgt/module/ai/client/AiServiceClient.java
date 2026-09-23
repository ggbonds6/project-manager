package com.pmgt.module.ai.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.exception.BizException;
import com.pmgt.module.ai.config.AiHttpClientConfig;
import com.pmgt.module.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AI 能力服务（ai-backend）的 HTTP 客户端——本系统的<b>适配层</b>。
 *
 * <h2>它负责什么</h2>
 * <ol>
 *   <li><b>唯一知道 AI 服务原始契约的地方</b>：字段名是 snake_case（{@code doc_id} /
 *       {@code page_no}，任务的 {@code percent}）还是 camelCase、枚举是 {@code PARSING}
 *       还是 {@code RUNNING}，都在这里翻译；Service / Controller / 前端一律只认 §9 的契约。</li>
 *   <li><b>把「不可用」与「业务错误」分开</b>：连不上 / 超时 / 5xx / 响应不可解析
 *       一律抛 {@link AiUnavailableException}（明确报错，<b>绝不降级成「未找到」</b>）；
 *       4xx 与响应 {@code code≠0} 是 AI 服务的业务拒绝，抛 {@link BizException} 原样带出原因。</li>
 *   <li><b>两个超时</b>：问答走 60s 的 client，其余走 10s 的 client
 *       （见 {@link AiHttpClientConfig} 里为什么必须分开）。</li>
 * </ol>
 *
 * <h2>为什么返回 {@code Map}/{@code List} 而不是 DTO</h2>
 * 见 {@link AiJson} 的类注释：AI 服务契约正在演进（例如新增 {@code citations} 字段），
 * Map 读取对新增字段免疫，且能区分「字段缺失」与「字段为空」——后者是本次实现的要求
 * （缺 {@code citations} 时给空数组，不允许伪造）。
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    /** 只用于读错误响应体的 detail（仓库已有 Jackson，不额外引依赖）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** AI 服务的成功码（与主系统一致：0 为成功）。 */
    private static final int CODE_OK = 0;

    private final AiProperties props;
    private final RestClient chatClient;
    private final RestClient fastClient;

    public AiServiceClient(AiProperties props,
                           @Qualifier(AiHttpClientConfig.CHAT_REST_CLIENT) RestClient chatClient,
                           @Qualifier(AiHttpClientConfig.FAST_REST_CLIENT) RestClient fastClient) {
        this.props = props;
        this.chatClient = chatClient;
        this.fastClient = fastClient;
    }

    // ══════════════════════════════════════════════════════════════════
    // 具体接口
    // ══════════════════════════════════════════════════════════════════

    /**
     * 服务自检 {@code GET /health?with_ocr=&with_llm=&with_vec=}。
     *
     * <p>默认只探 OCR（代价小），与 AI 服务自身的默认一致：大模型探活要真发一次请求，
     * 失败要等十几秒，不该被一个「服务自检」请求拖住。
     *
     * <p><b>深探走长超时 client</b>：{@code with_llm}/{@code with_vec} 会真的 ping 平台大模型与
     * 向量网关（实测几秒到十几秒），10s 读超时的快速 client 会把探活掐断并误报成
     * 「无法连接 AI 能力服务」——那正是想让用户看见模型状态时最不该出现的结论。
     * 只探 OCR 时仍走快速 client，保持「页面一打开就出结论」。
     *
     * <p>注意该接口是<b>字面量</b>响应（{@code {code,service,version,config,ocr,...}}），
     * 不套 {@code {code,data}}，所以这里不做 data 解包。
     */
    public Map<String, Object> health(boolean withOcr, boolean withLlm, boolean withVec) {
        RestClient client = (withLlm || withVec) ? chatClient : fastClient;
        return call("健康探活", () -> statusHandler("健康探活", client.get()
                .uri(uri -> uri.path("/health")
                        .queryParam("with_ocr", withOcr)
                        .queryParam("with_llm", withLlm)
                        .queryParam("with_vec", withVec)
                        .build())
                .retrieve())
                .body(mapType()));
    }

    /** 文档库列表 {@code GET /documents}（无分页参数，主系统侧再做过滤与分页）。 */
    public List<Map<String, Object>> listDocuments() {
        return dataList("文档列表", () -> statusHandler("文档列表", fastClient.get()
                .uri("/documents")
                .retrieve())
                .body(mapType()));
    }

    /** 文档详情 {@code GET /documents/{docId}}（含逐页原文）。 */
    public Map<String, Object> getDocument(String docId) {
        return dataObject("文档详情", () -> statusHandler("文档详情", fastClient.get()
                .uri("/documents/{docId}", docId)
                .retrieve())
                .body(mapType()));
    }

    /** 删除文档 {@code DELETE /documents/{docId}}。 */
    public void deleteDocument(String docId) {
        call("删除文档", () -> statusHandler("删除文档", fastClient.delete()
                .uri("/documents/{docId}", docId)
                .retrieve())
                .body(mapType()));
    }

    /** 上传解析任务列表 {@code GET /upload-tasks}。 */
    public List<Map<String, Object>> listTasks() {
        return dataList("任务列表", () -> statusHandler("任务列表", fastClient.get()
                .uri("/upload-tasks")
                .retrieve())
                .body(mapType()));
    }

    /** 上传解析任务详情 {@code GET /upload-tasks/{taskId}}。 */
    public Map<String, Object> getTask(String aiTaskId) {
        return dataObject("任务详情", () -> statusHandler("任务详情", fastClient.get()
                .uri("/upload-tasks/{taskId}", aiTaskId)
                .retrieve())
                .body(mapType()));
    }

    /**
     * 提交解析 {@code POST /upload-tasks}（multipart: file）。
     *
     * <p>用 AI 服务的<b>任务</b>接口而不是同步的 {@code POST /documents}：
     * 扫描件解析要几十秒到几分钟（AI 服务自己的注释写明「同步接口必然被超时打断」），
     * 主系统代理层不该把用户请求挂在那里等。
     *
     * <p>用 {@link InputStreamResource} 直传而不是 {@code ByteArrayResource}：
     * 附件上限 500MB，整份读进堆内存既浪费又危险。{@code contentLength()} 返回 -1
     * 明确走 chunked，避免 Spring 为了算长度把流缓冲起来。
     */
    public Map<String, Object> submitParse(String filename, String contentType, InputStream content) {
        String safeName = filename == null || filename.isBlank() ? "file" : filename;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new InputStreamResource(content) {
            @Override
            public String getFilename() {
                return safeName;
            }

            @Override
            public long contentLength() {
                return -1;
            }
        }).contentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts = builder.build();

        return dataObject("提交解析任务", () -> statusHandler("提交解析任务", fastClient.post()
                .uri("/upload-tasks")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve())
                .body(mapType()));
    }

    /**
     * 问答 {@code POST /chat}。
     *
     * <p>请求体字段名用 <b>{@code doc_ids}</b>（snake_case）：AI 服务侧正在把该字段改成
     * 「{@code doc_ids} 为主名 + {@code docIds} 作为 {@code @JsonAlias} 两种都收」，
     * 发 {@code doc_ids} 在改动前后都能命中，是最稳的选择。
     *
     * <p>{@code docIds} 必须<b>显式传入</b>（哪怕空列表也不发 null）：AI 服务把
     * 「空/缺失」解释成「检索全部文档」，那等于绕开主系统的权限收口。
     * 空列表由调用方在服务层直接拦掉，不会走到这里。
     */
    public Map<String, Object> chat(String question, List<String> docIds,
                                    List<Map<String, Object>> history, Integer topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("doc_ids", docIds == null ? List.of() : docIds);
        if (history != null && !history.isEmpty()) {
            body.put("history", history);
        }
        if (topK != null) {
            // ⚠️ AI 服务的 ChatIn 目前没有 topK 字段（Jackson 默认忽略未知字段，不报错）。
            // 这里按 §9 契约透传：等 AI 服务支持后无需改本层，现在传了也没有副作用。
            body.put("top_k", topK);
        }
        return dataObject("问答", () -> statusHandler("问答", chatClient.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve())
                .body(mapType()));
    }

    /** 配置里的基址（供自检展示，不回显令牌）。 */
    public String baseUrl() {
        return props.getBaseUrl();
    }

    /** 健康探针的短超时（秒），供上层在提示语里说明「为什么这么快就失败了」。 */
    public int fastTimeoutSeconds() {
        return props.getTimeoutSeconds();
    }

    // ══════════════════════════════════════════════════════════════════
    // 统一调用与异常翻译
    // ══════════════════════════════════════════════════════════════════

    private static ParameterizedTypeReference<Map<String, Object>> mapType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    /** 只发请求、不解包（{@code /health} 与「不关心返回体」的删除用）。 */
    private Map<String, Object> call(String action, Supplier<Map<String, Object>> request) {
        return unwrapCode(action, execute(action, request));
    }

    /** 取 {@code data} 段（对象；如 {@code /documents/{id}}、{@code /upload-tasks/{id}}、{@code /chat}）。 */
    private Map<String, Object> dataObject(String action, Supplier<Map<String, Object>> request) {
        return AiJson.asMap(unwrapCode(action, execute(action, request)).get("data"));
    }

    /**
     * 取 {@code data} 段（数组；如 {@code GET /documents}、{@code GET /upload-tasks}）。
     *
     * <p>响应仍是信封 {@code {"code":0,"data":[...]}}，所以先用 {@code mapType()}
     * 收整份信封——<b>不能</b>直接反序列化到 List，那会因根节点是对象而抛异常
     * （这是「列表接口莫名 500」的典型写法错误）。
     */
    private List<Map<String, Object>> dataList(String action, Supplier<Map<String, Object>> request) {
        return AiJson.asList(unwrapCode(action, execute(action, request)).get("data"));
    }

    /**
     * 发起调用并把「不可用」与「业务错误」翻译成不同异常。
     *
     * <p>{@link BizException}（含 {@link AiUnavailableException}）原样上抛；
     * 连接失败/超时 → 不可用；其它意外 → 也当不可用（对调用方而言「拿不到有效响应」
     * 就是不可用，比一个无信息量的 500「系统繁忙」有用得多）。
     *
     * <p>⚠️ <b>4xx 必须在 {@link #statusHandler(String)} 里就被转成 BizException</b>：
     * {@code RestClient} 对 4xx 默认抛 {@code HttpClientErrorException}，它是
     * {@code RestClientException} 的子类；如果不拦，会被下面的通用分支当成
     * 「AI 不可用」——而「文档不存在」被说成「服务不可用」会把人带偏（实测踩到过）。
     */
    private <T> T execute(String action, Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) {
                throw new AiUnavailableException(action + "返回空响应体");
            }
            return body;
        } catch (BizException e) {
            // 业务拒绝（含 AiUnavailableException，它是 BizException 的子类）原样上抛：
            // 上层要能区分「不可用（503）」与「业务拒绝（4xx/502）」
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("[ai] {}连接失败: {}", action, e.getMessage());
            throw new AiUnavailableException(
                    action + "连接失败（" + props.getBaseUrl() + "）：" + rootMessage(e), e);
        } catch (Exception e) {
            log.warn("[ai] {}调用异常: {}", action, e.toString());
            throw new AiUnavailableException(action + "调用异常：" + rootMessage(e), e);
        }
    }

    /**
     * 状态码翻译：4xx = AI 服务明确拒绝（业务错误，原样带出 detail），
     * 其余（5xx）交给默认处理后由 {@link #execute} 归为「不可用」。
     */
    private RestClient.ResponseSpec statusHandler(String action, RestClient.ResponseSpec spec) {
        return spec.onStatus(status -> status.is4xxClientError(), (request, response) -> {
            String detail = "";
            try {
                Map<String, Object> body = MAPPER.readValue(response.getBody(), MAP_TYPE);
                detail = AiJson.text(body, "detail");
                if (detail.isBlank()) {
                    detail = AiJson.text(body, "message");
                }
            } catch (Exception ignored) {
                // 读不出结构化错误体就只报状态码，别让"解析错误体失败"掩盖真实错误
            }
            throw new BizException(400, action + "被 AI 服务拒绝（HTTP " + response.getStatusCode().value() + "）："
                    + (detail.isBlank() ? "未提供原因" : detail));
        });
    }

    /**
     * 校验信封的 {@code code}。
     *
     * <p>{@code code} 缺失视为成功：AI 服务的 {@code /health} 是字面量响应，
     * 而 {@code /documents} 等是信封——不能要求每个响应都长一个样。
     */
    private Map<String, Object> unwrapCode(String action, Map<String, Object> body) {
        Object code = body.get("code");
        if (code instanceof Number number && number.intValue() != CODE_OK) {
            String message = AiJson.text(body, "message");
            if (message.isBlank()) {
                message = AiJson.text(body, "detail");
            }
            throw new BizException(502, action + "被 AI 服务拒绝："
                    + (message.isBlank() ? "code=" + code : message));
        }
        return body;
    }

    /** 取异常链里最贴底的那条消息（Spring 会把真实原因包两三层）。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cur.getClass().getSimpleName();
        }
        return AiJson.truncate(msg, 300);
    }

    /**
     * 由扩展名推断请求用的 MIME。
     *
     * <p>不做成通用 MIME 库：AI 服务主要按文件名后缀/内容判定文件类型，
     * 给一个够用的映射即可；未知类型退回 {@code application/octet-stream}。
     */
    public static String contentTypeOf(String filename) {
        String ext = extensionOf(filename);
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "txt", "log", "md", "csv" -> "text/plain; charset=utf-8";
            case "ofd" -> "application/ofd";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int i = filename.lastIndexOf('.');
        return i >= 0 && i < filename.length() - 1 ? filename.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }
}
