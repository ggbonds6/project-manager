package com.pmgt.ai.module.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型客户端（千问，走 OpenAI 兼容协议）。
 *
 * <p><b>为什么不直接写死某个厂商 SDK</b>：只要推理服务暴露 {@code /v1/chat/completions}，
 * 本客户端就能接——vLLM、Ollama、各厂商一体机都符合。这样后续换模型/换部署方式时，
 * 上层抽取与问答代码零改动。Java 版刻意只用 JDK 自带的 {@link HttpClient} + Jackson，
 * 不引入新依赖（Python 版用 openai SDK，那是 Python 侧的便利，不是协议要求）。
 *
 * <h2>⚠️ Qwen3 系列思维链的两个坑（实测踩过，2026-09-16）</h2>
 *
 * <b>坑 1：思维链会吃光输出额度，导致 {@code content} 为空。</b>
 * Qwen3 是混合推理模型，默认开启思考。思考内容放在 {@code reasoning_content}，
 * {@code message.content} 才是最终答案。思考过长时<b>全部输出额度被思考耗尽</b>，
 * {@code content} 返回空字符串——上层看起来就是"模型什么都没答"。
 * 实测：抽取一份 6 页合同 → {@code completion_tokens=8192}（正好撞上限）、{@code content} 为空、
 * {@code finish_reason=length}、推理耗时 148s。
 *
 * <p>对策：① {@code LLM_MAX_TOKENS} 默认放大到 16384；② 保留 {@code reasoning} 字段用于诊断
 * "为什么没答案"；③ {@code finish_reason == "length"} 时明确报"输出被截断"，
 * 而不是含糊地说"系统繁忙"。
 *
 * <p><b>坑 2：长时间思考会把最终答案挤掉——所以本项目默认关闭思考。</b>
 * 实测（2026-09-16，同一份 6 页扫描件合同）：
 *
 * <table border="1">
 *   <caption>开/关思考对比</caption>
 *   <tr><th>配置</th><th>结果</th></tr>
 *   <tr><td>开启思考</td><td>思考 30584 字 → 耗尽 16384 token → {@code finish_reason=length}
 *       → <b>最终答案为空</b>，耗时 4 分 55 秒</td></tr>
 *   <tr><td>关闭思考</td><td>完整产出「文件概要 / 关键信息 / 原文依据 / 存疑项」四节</td></tr>
 * </table>
 *
 * <p>故默认 {@code llm.enableThinking=false}。若换到确实需要深度推理的任务，可设 {@code true} 一试，
 * 但<b>务必同时把 {@code LLM_MAX_TOKENS} 调得更大</b>，并预期耗时显著上升。
 *
 * <p>{@code temperature} 默认 0：字段抽取与审计相关任务需要<b>可复现</b>，
 * 不要让同一个输入每次给出不同答案。
 */
@Component
public class LlmClient {

    /** 与 Python 版一致：给模型看的 JSON 用紧凑写法（不含多余空格）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 出错时贴进异常的响应体上限——够定位问题，又不会把日志冲爆。 */
    private static final int ERROR_BODY_CHARS = 500;

    private final AiSettings settings;
    private final HttpClient http;

    public LlmClient(AiSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── 数据形状（对齐 llm_client.py 的 dataclass）────────────────────

    /** 模型发起的一次工具调用请求。 */
    public record ToolCall(String id, String name, Map<String, Object> arguments, String rawArguments) {
    }

    /**
     * 一次调用的结果。
     *
     * @param reasoning 思维链内容（Qwen3 等推理模型）。不展示给用户，用于诊断"为什么没答案"。
     */
    public record ChatResult(
            String text,
            String model,
            int promptTokens,
            int completionTokens,
            String reasoning,
            String finishReason,
            List<ToolCall> toolCalls) {

        /** 是否因输出额度用尽被截断。 */
        public boolean isTruncated() {
            return "length".equals(finishReason);
        }

        public boolean hasText() {
            return text != null && !text.isBlank();
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        /** 思考内容的字符数（对齐 Python 的 {@code len(chat.reasoning)}）。 */
        public int reasoningChars() {
            return reasoning == null ? 0 : reasoning.length();
        }
    }

    // ── 主入口 ───────────────────────────────────────────────────────

    /**
     * 带<b>完整消息列表</b>的调用（支持工具调用）。
     *
     * <p>与 {@link #chat} 的区别：这里可以传多条消息——包含 assistant 的 {@code tool_calls}
     * 以及 {@code role="tool"} 的工具结果，供工具调用循环反复调用。
     *
     * @param timeoutSeconds 传 null 沿用配置的 {@code gateway.timeoutSeconds}
     * @param maxTokens      输出上限；null 取 {@code llm.maxTokens}
     */
    public ChatResult chatMessages(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Integer maxTokens,
            Double timeoutSeconds) {
        return chatMessages(messages, tools, 0.0, maxTokens, timeoutSeconds, null);
    }

    /**
     * 完整参数版本。
     *
     * <p>{@code enableThinking} 为 null 时取 {@code llm.enableThinking}。
     * <b>只在为 false 时才附带参数</b>——服务端不支持该字段时附带它会直接 400，所以非必要不传。
     */
    public ChatResult chatMessages(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            double temperature,
            Integer maxTokens,
            Double timeoutSeconds,
            Boolean enableThinking) {

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", settings.getLlm().getModel());
        ArrayNode msgs = body.putArray("messages");
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                msgs.add(MAPPER.valueToTree(message));
            }
        }
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens == null ? settings.getLlm().getMaxTokens() : maxTokens);

        if (tools != null && !tools.isEmpty()) {
            // tool_choice=auto：由模型自行决定是否调用工具。
            // 强制调用会破坏"信息不足就直说"的场景（模型不得不编一个查询出来）。
            ArrayNode toolNodes = body.putArray("tools");
            for (Map<String, Object> tool : tools) {
                toolNodes.add(MAPPER.valueToTree(tool));
            }
            body.put("tool_choice", "auto");
        }

        boolean thinking = enableThinking == null
                ? settings.getLlm().isEnableThinking()
                : enableThinking;
        if (!thinking) {
            // vLLM 部署 Qwen3 时通过 chat_template_kwargs 关闭思考
            body.putObject("chat_template_kwargs").put("enable_thinking", false);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(timeout()));
        builder.header("Content-Type", "application/json");
        String apiKey = settings.getGateway().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        String payload;
        try {
            payload = MAPPER.writeValueAsString(body);
        } catch (Exception exc) {
            throw new LlmException("请求体序列化失败: " + exc.getMessage(), exc);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8));

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new LlmException("请求被中断: " + exc.getMessage(), exc);
        } catch (Exception exc) {
            throw new LlmException(exc.getClass().getSimpleName() + ": " + exc.getMessage(), exc);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmException("HTTP " + response.statusCode() + ": " + brief(response.body()));
        }
        return parse(response.body());
    }

    /**
     * 单轮对话（system + 一条用户消息的便捷封装）。
     *
     * <p>默认 {@code temperature=0}：字段抽取与审计相关任务需要<b>可复现</b>。
     */
    public ChatResult chat(
            String prompt,
            String system,
            double temperature,
            Double timeoutSeconds,
            Integer maxTokens,
            Boolean enableThinking) {

        List<Map<String, Object>> messages = new ArrayList<>();
        if (system != null && !system.isEmpty()) {
            messages.add(message("system", system));
        }
        messages.add(message("user", prompt));
        return chatMessages(messages, null, temperature, maxTokens, timeoutSeconds, enableThinking);
    }

    /** 单轮对话，全默认（temperature=0，超时/额度/思考都取配置）。 */
    public ChatResult chat(String prompt, String system) {
        return chat(prompt, system, 0.0, null, null, null);
    }

    /**
     * 连通性自检：返回 (是否可用, 说明)。
     *
     * <p>刻意用<b>短超时 + 不重试</b>：自检的目的是"快速告诉你通不通"，
     * 而不是让用户对着终端等几分钟。推理服务在别的网段时尤其明显。
     */
    public LlmPing ping(double timeoutSeconds) {
        try {
            ChatResult result = chat(
                    "回复两个字：正常",
                    "你是一个测试助手。",
                    0.0,
                    timeoutSeconds,
                    null,
                    false); // 自检不需要思考，快就好
            return new LlmPing(true, "模型 " + result.model() + " 连通，回复：" + head(result.text(), 40));
        } catch (Exception exc) {
            return new LlmPing(false, exc.getClass().getSimpleName() + ": " + exc.getMessage());
        }
    }

    /** 默认 15 秒的探活（对齐 Python 的 {@code ping(timeout=15.0)}）。 */
    public LlmPing ping() {
        return ping(15.0);
    }

    /** 探活结果：{@code /health?with_llm=true} 直接输出 {@code {ok, detail}}。 */
    public record LlmPing(boolean ok, String detail) {
    }

    /** 模型调用失败。上层（抽取 / 问答）据此给出可操作的提示，而不是含糊的"系统繁忙"。 */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 网关 base_url 到 {@code /v1} 为止，拼接时去掉多余的斜杠。 */
    private String chatCompletionsUrl() {
        String base = settings.getGateway().getBaseUrl();
        if (base == null || base.isBlank()) {
            throw new LlmException("未配置网关地址（ai.gateway.base-url / LLM_BASE_URL）");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/chat/completions";
    }

    private long timeout() {
        double seconds = settings.getGateway().getTimeoutSeconds();
        return (long) Math.max(1, seconds);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private ChatResult parse(String raw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception exc) {
            throw new LlmException("响应不是合法 JSON: " + brief(raw), exc);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("响应中没有 choices: " + brief(raw));
        }
        JsonNode choice = choices.get(0);
        JsonNode msg = choice.path("message");
        JsonNode usage = root.path("usage");

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode function = tc.path("function");
            String argumentsRaw = text(function.path("arguments"));
            if (argumentsRaw.isEmpty()) {
                argumentsRaw = "{}";
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            try {
                JsonNode parsed = MAPPER.readTree(argumentsRaw);
                if (parsed.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> asMap = MAPPER.convertValue(parsed, Map.class);
                    arguments = asMap;
                }
            } catch (Exception ignored) {
                // 参数不是合法 JSON 时按空参数处理（对齐 Python 的 except 分支）
            }
            calls.add(new ToolCall(
                    text(tc.path("id")),
                    text(function.path("name")),
                    arguments,
                    argumentsRaw));
        }

        return new ChatResult(
                text(msg.path("content")),
                text(root.path("model")),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                // reasoning_content 不属于 OpenAI 标准字段，用 path 兼容其他模型
                text(msg.path("reasoning_content")),
                text(choice.path("finish_reason")),
                Collections.unmodifiableList(calls));
    }

    /** 取文本并 strip；缺字段 / null / 非文本节点都返回空串（对齐 Python 的 {@code or ""}）。 */
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("").strip();
    }

    private static String brief(String raw) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= ERROR_BODY_CHARS ? oneLine : oneLine.substring(0, ERROR_BODY_CHARS) + "…";
    }

    private static String head(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
