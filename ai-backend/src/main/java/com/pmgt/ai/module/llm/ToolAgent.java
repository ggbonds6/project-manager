package com.pmgt.ai.module.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用循环——让模型"自己去找答案"，而不是把文档硬塞进上下文。
 *
 * <h2>一次问答是怎么跑的</h2>
 *
 * <pre>
 * 用户提问
 *   → 模型判断需要什么信息 → 发起工具调用（如 search_documents("付款条款")）
 *   → 本地执行工具，把结果回传
 *   → 模型看结果，可能再查一次（换关键词 / 读某页原文 / 算一下金额）
 *   → 信息够了 → 给出最终答案（带来源页码）
 * </pre>
 *
 * <h2>为什么要限制轮数</h2>
 *
 * <p>不限制的话模型可能反复检索、绕圈（尤其在提示词约束很严时）。
 * 到上限后<b>不会直接失败</b>，而是再做一次"禁用工具"的调用，让模型基于<b>已经拿到的信息</b>作答，
 * 并要求它把没找到的部分明说——这样用户至少能拿到部分答案 + 明确的缺口，而不是一个报错。
 *
 * <h2>可靠性地基</h2>
 *
 * <ul>
 *   <li>工具执行<b>永不抛异常</b>（{@link Tools#execute} 内部兜底成结构化错误返回给模型），
 *       一条工具失败不会让整轮问答崩掉；</li>
 *   <li>每轮的 token 与耗时都记账，便于排查"为什么这次特别慢"。</li>
 * </ul>
 */
@Component
public class ToolAgent {

    /** 与 Python 版一致：给模型看的 JSON 用紧凑写法。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具调用轮数上限。
     *
     * <p>设小了信息不够，设大了慢且 token 消耗陡增（每轮都要重发全部上下文）。
     * 提示词里已明确引导模型"控制在 5 次工具调用以内"，这里留出余量兜底。 */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    /** 工具结果在"调用轨迹"里的摘要长度（给人和前端看，不影响模型）。 */
    public static final int BRIEF_CHARS = 120;

    private final LlmClient llmClient;
    private final Tools tools;

    public ToolAgent(LlmClient llmClient, Tools tools) {
        this.llmClient = llmClient;
        this.tools = tools;
    }

    /** 一次工具调用记录——前端据此展示"模型查了什么、查到几条"。 */
    public record ToolTrace(
            int round,
            String name,
            Map<String, Object> arguments,
            String brief,
            double elapsed,
            boolean isError) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("round", round);
            out.put("name", name);
            out.put("arguments", arguments == null ? Map.of() : arguments);
            out.put("brief", brief);
            out.put("elapsed", round2(elapsed));
            out.put("is_error", isError);
            return out;
        }
    }

    /** 一轮完整的"提问 → 工具 → 回答"的结果。字段对齐 Python 的 {@code AgentResult}。 */
    public static final class AgentResult {

        private final String text;
        private final List<ToolTrace> trace;
        private final int rounds;
        private final int promptTokens;
        private final int completionTokens;
        private final double elapsed;
        private final String stoppedReason; // done | max_rounds | error
        private final String error;

        public AgentResult(
                String text,
                List<ToolTrace> trace,
                int rounds,
                int promptTokens,
                int completionTokens,
                double elapsed,
                String stoppedReason,
                String error) {
            this.text = text;
            this.trace = trace;
            this.rounds = rounds;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.elapsed = elapsed;
            this.stoppedReason = stoppedReason;
            this.error = error;
        }

        public String getText() {
            return text;
        }

        public List<ToolTrace> getTrace() {
            return trace;
        }

        public int getRounds() {
            return rounds;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public double getElapsed() {
            return elapsed;
        }

        /** {@code done} | {@code max_rounds} | {@code error}。 */
        public String getStoppedReason() {
            return stoppedReason;
        }

        public String getError() {
            return error;
        }

        public List<Map<String, Object>> traceMaps() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ToolTrace item : trace) {
                out.add(item.toMap());
            }
            return out;
        }

        /**
         * 对齐 Python 的 {@code AgentResult.to_dict()}。
         *
         * <p>注意 Python 的 trace 只在这里被转成 dict（{@code tool_calls=len(result.trace)} 也读它）。
         */
        public Map<String, Object> toDict() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", text);
            out.put("trace", traceMaps());
            out.put("rounds", rounds);
            out.put("prompt_tokens", promptTokens);
            out.put("completion_tokens", completionTokens);
            out.put("elapsed", round2(elapsed));
            out.put("stopped_reason", stoppedReason);
            out.put("error", error == null ? "" : error);
            return out;
        }
    }

    /**
     * 跑一轮完整的"提问 → 工具 → 回答"。
     *
     * <p>注意：会<b>原地修改</b>传入的 messages（追加 assistant / tool 消息），
     * 这样多轮对话时上下文能自然延续。
     */
    public AgentResult run(List<Map<String, Object>> messages, Integer maxRounds, Double timeoutSeconds) {
        long startedNanos = System.nanoTime();
        List<ToolTrace> trace = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        int rounds = 0;
        int limit = maxRounds == null ? DEFAULT_MAX_ROUNDS : maxRounds;

        try {
            for (int roundNo = 1; roundNo <= limit; roundNo++) {
                rounds = roundNo;
                LlmClient.ChatResult resp = llmClient.chatMessages(
                        messages, tools.schemas(), null, timeoutSeconds);
                promptTokens += resp.promptTokens();
                completionTokens += resp.completionTokens();

                // 没有工具调用 → 这就是最终答案
                if (!resp.hasToolCalls()) {
                    return new AgentResult(
                            resp.text(),
                            trace,
                            rounds,
                            promptTokens,
                            completionTokens,
                            elapsedSeconds(startedNanos),
                            "done",
                            "");
                }

                messages.add(toAssistantMessage(resp));

                for (LlmClient.ToolCall call : resp.toolCalls()) {
                    long t0 = System.nanoTime();
                    Map<String, Object> result = tools.execute(call.name(), call.arguments());
                    double cost = elapsedSeconds(t0);

                    // 工具结果必须回传；用 JSON 保证结构清晰、模型好解析
                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", call.id());
                    toolMessage.put("content", toJson(result));
                    messages.add(toolMessage);

                    trace.add(new ToolTrace(
                            roundNo,
                            call.name(),
                            call.arguments(),
                            brief(call.name(), result),
                            cost,
                            result != null && result.containsKey("error")));
                }
            }

            // ── 到上限：禁用工具再问一次，让它用已有信息作答 ──
            Map<String, Object> nudge = new LinkedHashMap<>();
            nudge.put("role", "user");
            nudge.put("content",
                    "（已达到本轮工具调用上限。请**仅根据以上已经获得的信息**作答；"
                            + "仍然必须标注来源页码；确实没有查到的部分，请明确写「文档中未找到」，不要猜测。）");
            messages.add(nudge);

            LlmClient.ChatResult resp = llmClient.chatMessages(messages, null, null, timeoutSeconds);
            promptTokens += resp.promptTokens();
            completionTokens += resp.completionTokens();
            return new AgentResult(
                    resp.text(),
                    trace,
                    rounds,
                    promptTokens,
                    completionTokens,
                    elapsedSeconds(startedNanos),
                    "max_rounds",
                    "");

        } catch (Exception exc) {
            // 把失败原因如实带回，不吞掉
            String detail = exc.getClass().getSimpleName()
                    + (exc.getMessage() == null ? "" : ": " + exc.getMessage());
            return new AgentResult(
                    "",
                    trace,
                    rounds,
                    promptTokens,
                    completionTokens,
                    elapsedSeconds(startedNanos),
                    "error",
                    detail);
        }
    }

    public AgentResult run(List<Map<String, Object>> messages) {
        return run(messages, DEFAULT_MAX_ROUNDS, null);
    }

    /**
     * 把带 tool_calls 的响应转成可回传给模型的消息。
     *
     * <p>注意 content 可能是空串，OpenAI 规范要求此时传 null（不能传空字符串）。
     */
    private static Map<String, Object> toAssistantMessage(LlmClient.ChatResult resp) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", resp.text() == null || resp.text().isEmpty() ? null : resp.text());
        List<Map<String, Object>> callMaps = new ArrayList<>();
        for (LlmClient.ToolCall call : resp.toolCalls()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments",
                    call.rawArguments() == null || call.rawArguments().isEmpty()
                            ? "{}"
                            : call.rawArguments());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id());
            item.put("type", "function");
            item.put("function", function);
            callMaps.add(item);
        }
        message.put("tool_calls", callMaps);
        return message;
    }

    /** 把工具结果压成一行摘要，供展示与排错。 */
    static String brief(String name, Map<String, Object> result) {
        if (result == null) {
            return "null";
        }
        if (result.containsKey("error")) {
            return "错误：" + result.get("error");
        }
        if ("search_documents".equals(name)) {
            Object raw = result.get("hits");
            List<?> hits = raw instanceof List<?> list ? list : List.of();
            if (hits.isEmpty()) {
                return "无命中";
            }
            List<String> pages = new ArrayList<>();
            for (Object item : hits.subList(0, Math.min(4, hits.size()))) {
                if (item instanceof Map<?, ?> hit) {
                    pages.add(hit.get("filename") + "P" + hit.get("page_no"));
                }
            }
            return "命中 " + hits.size() + " 段：" + String.join("、", pages);
        }
        if ("read_page".equals(name)) {
            Object text = result.get("text");
            int chars = text == null ? 0 : String.valueOf(text).length();
            return "读取 " + result.get("filename") + " 第 " + result.get("page_no") + " 页（" + chars + " 字）";
        }
        if ("calculate".equals(name)) {
            return result.get("expression") + " = " + result.get("result");
        }
        return truncate(toJson(result), BRIEF_CHARS);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exc) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static double elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    /** 对齐 Python 的 {@code round(x, 2)}（HALF_UP，用于展示）。 */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
