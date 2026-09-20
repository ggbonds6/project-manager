package com.pmgt.ai.module.llm;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.store.DocStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问答编排：组装消息 → 跑工具调用循环 → 返回答案、引用与调用轨迹。
 *
 * <h2>与「文档抽取」({@link AnalyzeService}) 的分工</h2>
 *
 * <table border="1">
 *   <caption>抽取 vs 问答</caption>
 *   <tr><th></th><th>抽取</th><th>问答</th></tr>
 *   <tr><td>输入</td><td>一份文件 + 固定模板</td><td>一个问题 + 可选文档范围</td></tr>
 *   <tr><td>信息获取</td><td><b>全文直接给模型</b></td><td><b>模型自己检索</b>（工具调用）</td></tr>
 *   <tr><td>适合</td><td>"把这份合同的付款条款整理出来"</td><td>"这几份材料里，验收结论是什么？"</td></tr>
 * </table>
 *
 * <h2>上下文控制</h2>
 *
 * <ul>
 *   <li><b>历史只保留纯文本的问答对</b>（不带 tool_calls）——把工具调用中间过程塞回历史会让
 *       上下文迅速膨胀，且容易让模型"学样"重复调用；</li>
 *   <li>只保留最近 {@link #MAX_HISTORY_TURNS} 轮，更早的丢弃（长对话对文档问答的价值递减）。</li>
 * </ul>
 *
 * <h2>结构化引用</h2>
 *
 * <p>每次问答新建一份 {@link CitationRegistry} 并传进工具循环：模型看到的每条命中/整页原文都带
 * {@code cite} 编号，答案里的 {@code [1]} 与响应里的 {@code citations[0]} 是同一处出处，
 * 前端据此跳到附件第 {@code page_no} 页。注册表<b>只在这里创建</b>（工具与编排器都是单例，
 * 不能持有请求级状态）。
 */
@Service
public class QaService {

    /** 保留的历史问答轮数。 */
    public static final int MAX_HISTORY_TURNS = 6;

    private static final String NO_DOC_ANSWER =
            "> ⚠️ **还没有可问答的文档。**\n>\n"
                    + "> 请先在左侧上传文件（PDF / 图片），上传后我就能基于文件内容回答。";

    private final AiSettings settings;
    private final DocStore docStore;
    private final ToolAgent agent;

    public QaService(AiSettings settings, DocStore docStore, ToolAgent agent) {
        this.settings = settings;
        this.docStore = docStore;
        this.agent = agent;
    }

    /** 问答结果。字段对齐 Python 的 {@code QaResult}（{@code /chat} 的 {@code data} 就是 {@link #toDict()}）。 */
    public static final class QaResult {

        private final String answer;
        private final List<Map<String, Object>> trace;
        private final List<Map<String, Object>> citations;
        private final List<Map<String, Object>> scope;
        private final Map<String, Object> llm;
        private final String stoppedReason;
        private final String error;

        public QaResult(
                String answer,
                List<Map<String, Object>> trace,
                List<Map<String, Object>> citations,
                List<Map<String, Object>> scope,
                Map<String, Object> llm,
                String stoppedReason,
                String error) {
            this.answer = answer;
            this.trace = trace;
            this.citations = citations == null ? List.of() : citations;
            this.scope = scope;
            this.llm = llm;
            this.stoppedReason = stoppedReason;
            this.error = error;
        }

        public String getAnswer() {
            return answer;
        }

        public List<Map<String, Object>> getTrace() {
            return trace;
        }

        /**
         * 结构化引用表：{@code index / doc_id / filename / page_no / snippet / score}，顺序 = 编号顺序。
         *
         * <p>前端用它把答案里的 {@code [1]} 变成"跳到某附件第 N 页"的链接；
         * 模型没有引用任何文档时是空列表（不是 {@code null}）。
         */
        public List<Map<String, Object>> getCitations() {
            return citations;
        }

        public List<Map<String, Object>> getScope() {
            return scope;
        }

        public Map<String, Object> getLlm() {
            return llm;
        }

        public String getStoppedReason() {
            return stoppedReason;
        }

        public String getError() {
            return error;
        }

        /** 对齐 Python 的 {@code QaResult.to_dict()}（{@code api.py} 的 {@code /chat} 直接返回它）。 */
        public Map<String, Object> toDict() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("answer", answer);
            out.put("trace", trace);
            out.put("scope", scope);
            out.put("llm", llm);
            out.put("stopped_reason", stoppedReason);
            out.put("error", error);
            // ⚠️ 新增字段一律**追加在末尾**：既有键的名字、顺序、语义都不动，
            // 老调用方（主系统）按 key 取值，加字段对它完全透明。
            out.put("citations", citations);
            return out;
        }
    }

    /** 组装结果：给模型的消息、本次问答覆盖的文档（scope note 用）、以及精简后的 scope。 */
    public record BuiltMessages(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> docs,
            List<Map<String, Object>> scope) {
    }

    /**
     * 回答一个关于已上传文档的问题。
     *
     * @param docIds    限定检索范围；为空则用全部文档
     * @param history   历史问答（只回放 user/assistant 纯文本，截断到最近 6 轮）
     * @param maxRounds 工具调用轮数上限；null 取 {@link ToolAgent#DEFAULT_MAX_ROUNDS}
     * @return 答案 + 调用轨迹 + <b>结构化引用表</b>（见 {@link CitationRegistry}，无引用时为空列表）
     */
    public QaResult ask(
            String question, List<String> docIds, List<Map<String, Object>> history, Integer maxRounds) {

        long startedNanos = System.nanoTime();
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            return new QaResult(
                    "> ⚠️ 请先输入问题。",
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    "done",
                    "问题为空");
        }

        List<Map<String, Object>> docs = scopedDocs(docIds);
        if (docs.isEmpty()) {
            return new QaResult(NO_DOC_ANSWER, List.of(), List.of(), List.of(), Map.of(), "done", "没有可用文档");
        }

        // 引用表**每次问答新建一份**：工具是单例，编号是请求级状态，绝不能放进 Bean/static/ThreadLocal
        CitationRegistry citations = new CitationRegistry();

        BuiltMessages built = buildQaMessages(q, docs, history);
        ToolAgent.AgentResult result = agent.run(
                built.messages(),
                maxRounds == null ? ToolAgent.DEFAULT_MAX_ROUNDS : maxRounds,
                (double) Math.max(1, settings.getGateway().getTimeoutSeconds()),
                citations);

        String answer = result.getText();
        if (answer == null || answer.isEmpty()) {
            if ("error".equals(result.getStoppedReason())) {
                answer = "> ⚠️ **问答失败**：`" + result.getError() + "`\n>\n"
                        + "> 排查：① 推理服务是否可达（`/health?with_llm=true`）　"
                        + "② `LLM_TIMEOUT` 是否偏小　③ 模型名是否正确";
            } else if ("max_rounds".equals(result.getStoppedReason())) {
                answer = "> ⚠️ 已达到工具调用轮数上限，仍未给出结论。\n>\n"
                        + "> 可尝试：把问题问得更具体、指定某一份文档，或调大 `LLM_MAX_TOKENS`。";
            } else {
                answer = "> ⚠️ " + Prompts.EMPTY_OUTPUT_HINT;
            }
        }

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("ok", !"error".equals(result.getStoppedReason()));
        llm.put("model", settings.getLlm().getModel());
        llm.put("rounds", result.getRounds());
        llm.put("prompt_tokens", result.getPromptTokens());
        llm.put("completion_tokens", result.getCompletionTokens());
        llm.put("tool_calls", result.getTrace().size());
        llm.put("elapsed", ToolAgent.round2((System.nanoTime() - startedNanos) / 1_000_000_000.0));

        return new QaResult(
                answer,
                result.traceMaps(),
                citations.toList(),
                built.scope(),
                llm,
                result.getStoppedReason(),
                result.getError());
    }

    /** 便捷入口（全默认）。 */
    public QaResult ask(String question, List<String> docIds, List<Map<String, Object>> history) {
        return ask(question, docIds, history, null);
    }

    /**
     * 组装问答消息列表（对应 Python 的 {@code qa.build_messages(question, doc_ids, history)}）。
     *
     * <p>⚠️ 只允许<b>一条 system 消息</b>，且必须在最前面。
     * 实测该推理服务（vLLM + Qwen3 模板）遇到第二条 system 会直接 400：
     * {@code "System message must be at the beginning."}
     * 因此把"角色铁律"与"文档清单"合并进同一条 system。
     *
     * <p>⚠️ <b>不要加 {@code buildQaMessages(String, List<String>, List<Map>)} 重载</b>：
     * 泛型擦除后它与本方法签名相同（都是 {@code (String, List, List)}），编译不过。
     * Python 没这个问题（鸭子类型），Java 必须区分开——要按 docIds 组装就先自己调
     * {@link #scopedDocs(List)} 再传进来。
     */
    public BuiltMessages buildQaMessages(
            String question, List<Map<String, Object>> docs, List<Map<String, Object>> history) {

        String systemContent = Prompts.QA_SYSTEM_PROMPT + "\n\n" + Prompts.buildDocScopeNote(docs);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage(systemContent));

        // 只回放纯文本历史（见类注释）；截断到最近 N 轮
        List<Map<String, Object>> items = history == null ? List.of() : history;
        int from = Math.max(0, items.size() - MAX_HISTORY_TURNS * 2);
        for (Map<String, Object> item : items.subList(from, items.size())) {
            String role = item == null ? null : asText(item.get("role"));
            String content = item == null ? "" : asText(item.get("content")).strip();
            if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                messages.add(roleMessage(role, content));
            }
        }

        messages.add(roleMessage("user", question));
        return new BuiltMessages(messages, docs, scopeOf(docs));
    }

    /** 作用域内的文档（元信息）。{@code docIds} 为空表示全部。 */
    public List<Map<String, Object>> scopedDocs(List<String> docIds) {
        List<Map<String, Object>> docs = docStore == null ? List.of() : docStore.list();
        if (docs == null) {
            return List.of();
        }
        if (docIds == null || docIds.isEmpty()) {
            return docs;
        }
        Set<String> keep = new LinkedHashSet<>(docIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            if (doc != null && keep.contains(asText(doc.get("doc_id")))) {
                out.add(doc);
            }
        }
        return out;
    }

    /** 响应里的 scope：只暴露 doc_id 与文件名。 */
    private static List<Map<String, Object>> scopeOf(List<Map<String, Object>> docs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("doc_id", asText(doc.get("doc_id")));
            item.put("filename", asText(doc.get("filename")));
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> systemMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> roleMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
