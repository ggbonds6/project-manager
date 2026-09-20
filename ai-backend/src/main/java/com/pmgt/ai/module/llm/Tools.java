package com.pmgt.ai.module.llm;

import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型可调用的工具集。
 *
 * <h2>设计原则</h2>
 *
 * <p><b>只给模型"它做不到的事"，不给"它已经会的事"。</b>
 *
 * <table border="1">
 *   <caption>三个工具的存在理由</caption>
 *   <tr><th>工具</th><th>为什么必须做成工具</th></tr>
 *   <tr><td>{@code search_documents}</td><td>长文档塞不进上下文，必须按需检索</td></tr>
 *   <tr><td>{@code read_page}</td><td>检索到的片段可能不够，需要读整页原文核对</td></tr>
 *   <tr><td>{@code calculate}</td><td><b>模型算数不可靠</b>，金额求和/比例校验必须交给代码</td></tr>
 * </table>
 *
 * <blockquote>
 * <b>为什么没有 {@code list_documents}</b>（2026-09-18 删除）：文档清单已经由
 * {@code QaService} → {@link Prompts#buildDocScopeNote} 注入 system 提示词
 * （{@code doc_id | 文件名 | 页数}），工具返回的信息与之重复，只会引诱模型"先列一遍文档"
 * 白烧一轮往返。清单若要展示更多字段，改 scope note 即可。
 * </blockquote>
 *
 * <h2>为什么不做成"全流程 agent 自主调度"</h2>
 *
 * <p>文档处理的步骤是<b>确定的</b>（解析 → 检索 → 回答），用代码编排比让模型自己决定更快更稳。
 * 工具的价值在于<b>补模型的能力短板</b>（记忆、精确计算、全文检索），而不是把编排权也交出去——
 * 审计场景要求可复现、可解释。
 *
 * <h2>⚠️ 将来接向量库只改这里</h2>
 *
 * <p>{@code search_documents} 的实际实现在检索层（{@link SearchPort}）：
 * 向量召回 + 关键词召回 → Reranker 精排。工具这一层只做"参数容错 + 结果整形"，
 * <b>工具签名不变</b>——这正是当初把检索单独分层的意义。
 *
 * <h2>结构化引用（cite）</h2>
 *
 * <p>{@code search_documents} 的每条命中与 {@code read_page} 的每一页都会登记进
 * <b>本次问答的</b> {@link CitationRegistry}，并在返回里带上 {@code cite} 编号；
 * 模型据此写 {@code [1][3]}，前端据此跳到附件第 N 页。注册表由 {@code QaService} 每次问答新建、
 * <b>显式传参</b>进来（工具是单例，不能持有请求级状态）。
 */
@Component
public class Tools {

    /** 表达式长度上限（挡 `9**9**9` 之外的"硬算超长表达式"）。 */
    public static final int MAX_EXPRESSION_CHARS = 200;

    /** {@code read_page} 单页返回上限：整页原文可能很长，截断并明确告知模型。 */
    public static final int MAX_PAGE_CHARS = 4000;

    private final SearchPort searchPort;
    private final DocStore docStore;

    public Tools(SearchPort searchPort, DocStore docStore) {
        this.searchPort = searchPort;
        this.docStore = docStore;
    }

    /**
     * 给模型看的工具清单（OpenAI 兼容 {@code tools} 字段）。
     *
     * <p>只有 3 个：{@code search_documents} / {@code read_page} / {@code calculate}。
     * <b>不含 {@code list_documents}</b>（见类注释）。
     */
    public List<Map<String, Object>> schemas() {
        return TOOL_SCHEMAS;
    }

    /** 分发用的工具名集合——必须与 {@link #schemas()} 里的名字完全一致。 */
    public Set<String> toolNames() {
        return TOOL_SCHEMAS.stream()
                .map(schema -> String.valueOf(((Map<?, ?>) schema.get("function")).get("name")))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具 1：检索文档
    // ══════════════════════════════════════════════════════════════════

    /** 在文档中检索相关段落。{@code docId} 为空则检索全部已上传文档。 */
    public Map<String, Object> searchDocuments(String query, Integer topK, String docId) {
        return searchDocuments(query, topK, docId, null);
    }

    /**
     * 在文档中检索相关段落，并把每条命中登记到本次问答的引用表里。
     *
     * <p>⚠️ <b>{@code cite} 必须在这一层就写进命中</b>，不能等最后再统一编号：
     * 模型是靠"命中里带的 cite"来写 {@code [1][2]} 的，编号必须<b>在模型看到命中时就已经存在</b>，
     * 否则模型只能自己编页码，前端拿到答案里的编号也对不上引用表。
     *
     * @param citations 本次问答的引用注册表；{@code null} 表示不登记（老调用点/单测的纯检索用法）
     */
    public Map<String, Object> searchDocuments(
            String query, Integer topK, String docId, CitationRegistry citations) {
        int k = normalizeTopK(topK);
        SearchPort.SearchResult result = searchPort.search(query == null ? "" : query, k, docId);
        List<Map<String, Object>> hits = new ArrayList<>();
        if (result != null && result.hits() != null) {
            for (SearchPort.Hit hit : result.hits()) {
                if (hit != null) {
                    hits.add(hitMap(hit, citations));
                }
            }
        }
        if (hits.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("found", 0);
            empty.put("hits", hits);
            empty.put("hint",
                    "没有检索到相关内容。可换关键词（如换成金额、合同编号、条款名），"
                            + "或确认问题涉及的文档是否已上传。");
            // note 会写明"向量服务不可用，本次仅关键词召回"这类降级信息，别吞掉
            empty.put("note", result == null || result.note() == null ? "" : result.note());
            return empty;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", result.found());
        out.put("hits", hits);
        out.put("retrieval", result.retrieval());
        out.put("reranked", result.reranked());
        out.put("note", result.note());
        return out;
    }

    /** 模型有时把数字传成字符串（"10"），这里做容错——对齐 Python 的 {@code int(top_k)} + 兜底 5。 */
    private static int normalizeTopK(Integer topK) {
        return topK == null ? 5 : topK;
    }

    /**
     * 把一条命中整形给模型：复制成<b>保序可变</b> Map，再补上 {@code cite} 编号。
     *
     * <p>为什么要复制：{@link SearchPort.Hit#toMap()} 是"只读快照"，
     * 而 {@code cite} 是<b>本次问答才有的一次性状态</b>（每次问答的编号表都不一样），
     * 往共享对象上写会把上一次请求的编号带进下一次。复制一份再写，边界清楚。
     */
    private static Map<String, Object> hitMap(SearchPort.Hit hit, CitationRegistry citations) {
        Map<String, Object> out = new LinkedHashMap<>(hit.toMap());
        if (citations != null) {
            out.put("cite", citations.register(
                    hit.docId(), hit.filename(), hit.pageNo(), hit.text(), hit.score()));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具 2：读整页
    // ══════════════════════════════════════════════════════════════════

    /** 读取指定文档的某一页原文（核对上下文用）。 */
    public Map<String, Object> readPage(String docId, Integer pageNo) {
        return readPage(docId, pageNo, null);
    }

    /**
     * 读取某页原文，并把这一页登记进本次问答的引用表。
     *
     * <p>为什么整页也要登记：模型有两种引用路径——检索命中片段、或直接读整页核对。
     * 两条路径都必须能落到引用表里，否则"答案引用了 read_page 看到的原文"就成了无出处的内容。
     * 同一页先被检索登记过时，这里只是<b>取回原来的编号</b>，不会产生第二个号。
     *
     * <p>⚠️ 空白页/越界页在上面就返回错误了：没有正文可引，<b>不登记</b>。
     *
     * @param citations 本次问答的引用注册表；{@code null} 表示不登记
     */
    public Map<String, Object> readPage(String docId, Integer pageNo, CitationRegistry citations) {
        StoredDoc doc = docStore == null ? null : docStore.get(docId);
        if (doc == null) {
            return error("找不到文档 " + docId + "；文档清单见 system 提示词。");
        }
        if (pageNo == null) {
            return error("页码无效：" + pageNo);
        }
        String text = doc.pageText(pageNo);
        if (text.strip().isEmpty()) {
            Map<String, Object> out = error(
                    doc.getFilename() + " 第 " + pageNo + " 页没有文本（可能是空白页或未识别）。");
            out.put("total_pages", doc.getPageCount());
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_id", doc.getDocId());
        out.put("filename", doc.getFilename());
        out.put("page_no", pageNo);
        out.put("total_pages", doc.getPageCount());
        out.put("text", text.length() > MAX_PAGE_CHARS
                ? text.substring(0, MAX_PAGE_CHARS) + "…（本页过长，已截断）"
                : text);
        if (citations != null) {
            // 分数传 0.0：整页读取没有检索分数，前端可据此区分"检索命中"与"主动读页"
            // （若同一页先前被检索命中过，注册表会保留那次更高的分数）
            out.put("cite", citations.register(doc.getDocId(), doc.getFilename(), pageNo, text, 0.0));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具 3：计算（金额求和、比例校验等）
    // ══════════════════════════════════════════════════════════════════

    // ⚠️ 只留四则运算。**刻意不给 `**` / `//` / `%`**（2026-09-18 收紧）：
    // 表达式长度上限 200 字符挡不住 `9**9**9` —— 大整数幂会算出一个天文数字，CPU/内存被打满；
    // 而"金额求和、比例校验、差额"这些审计场景根本用不到乘方/取整/取模。
    //
    // Python 版靠 ast 白名单实现；Java 没有等价的安全求值入口（脚本引擎既慢又更危险），
    // 所以这里自己写一个极小的递归下降解析器：**词法层就只认数字与 + - * / ( )**，
    // 任何其他字符（`.`, `_`, 字母, `**`, `//`, `%`, `"`）在词法阶段即被拒——
    // 比"先解析再检查节点类型"更不容易漏。

    /**
     * 做精确算术。<b>金额求和、比例校验请用本工具，不要自己心算。</b>
     *
     * <p>例：{@code calculate("2394690 + 3192920 + 1596460 + 798230")}
     *
     * <p>返回的 {@code result} 是<b>字符串</b>而不是数字：JSON 里的浮点会丢分位精度，
     * 而审计口径要求"看到什么就是什么"。需要四舍五入到分时用 {@code rounded_2}。
     */
    public Map<String, Object> calculate(String expression) {
        String expr = (expression == null ? "" : expression)
                .strip()
                .replace(",", "")
                .replace("，", "");
        if (expr.isEmpty()) {
            return error("表达式为空");
        }
        if (expr.length() > MAX_EXPRESSION_CHARS) {
            return error("表达式过长");
        }
        BigDecimal value;
        try {
            value = new ExpressionParser(expr).parse();
        } catch (ArithmeticException exc) {
            // 除零等算术错误：消息对齐 Java 的 "Division by zero"（Python 是 "division by zero"）
            return error("无法计算：ArithmeticException: " + exc.getMessage());
        } catch (RuntimeException exc) {
            return error("无法计算：" + exc.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expression", expression);
        out.put("result", plain(value.stripTrailingZeros()));
        out.put("rounded_2", plain(value.setScale(2, RoundingMode.HALF_EVEN)));
        return out;
    }

    /** {@code Decimal.normalize()} 的等价物：去掉多余的尾零，但不把小整数变成科学计数法。 */
    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }

    /**
     * 白名单表达式解析器（数字 + {@code + - * / ( )} 与一元正负号，全程 {@link BigDecimal}）。
     *
     * <p>文法（与 Python 的 ast 优先级一致，故 {@code -2*3} 都是 {@code -6}）：
     * <pre>
     *   expr   := term (('+' | '-') term)*
     *   term   := unary (('*' | '/') unary)*
     *   unary  := ('+' | '-') unary | primary
     *   primary:= NUMBER | '(' expr ')'
     * </pre>
     *
     * <p>除法用 {@link MathContext#DECIMAL128}（34 位有效数字）：既不像 BigDecimal 默认那样
     * "非终止小数直接抛异常"，也不会像 float 那样丢精度。
     * 全程 {@code BigDecimal}，与校验层（{@code checks.py}）的口径一致。
     */
    private static final class ExpressionParser {

        private final String src;
        private int pos;

        ExpressionParser(String src) {
            this.src = src;
        }

        BigDecimal parse() {
            BigDecimal value = parseExpr();
            skipSpaces();
            if (pos < src.length()) {
                throw new IllegalArgumentException("表达式语法错误：不支持的内容 \"" + src.charAt(pos) + "\"");
            }
            return value;
        }

        private BigDecimal parseExpr() {
            BigDecimal left = parseTerm();
            while (true) {
                skipSpaces();
                if (eat('+')) {
                    left = left.add(parseTerm());
                } else if (eat('-')) {
                    left = left.subtract(parseTerm());
                } else {
                    return left;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal left = parseUnary();
            while (true) {
                skipSpaces();
                if (eat('*')) {
                    left = left.multiply(parseUnary());
                } else if (eat('/')) {
                    BigDecimal right = parseUnary();
                    if (right.signum() == 0) {
                        throw new ArithmeticException("Division by zero");
                    }
                    left = left.divide(right, MathContext.DECIMAL128);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal parseUnary() {
            skipSpaces();
            if (eat('-')) {
                return parseUnary().negate();
            }
            if (eat('+')) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private BigDecimal parsePrimary() {
            skipSpaces();
            if (eat('(')) {
                BigDecimal value = parseExpr();
                skipSpaces();
                if (!eat(')')) {
                    throw new IllegalArgumentException("表达式语法错误：缺少右括号");
                }
                return value;
            }
            return parseNumber();
        }

        /**
         * 数字只认 {@code 123} / {@code 1.5} / {@code 1.5e3} 三种写法。
         *
         * <p>刻意<b>不</b>认 {@code True} / {@code abc} / {@code _}——它们在词法阶段就被拒，
         * 不会走到求值。
         */
        private BigDecimal parseNumber() {
            skipSpaces();
            int start = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos == start || (pos == start + 1 && src.charAt(start) == '.')) {
                String bad = pos < src.length() ? String.valueOf(src.charAt(pos)) : "表达式结束";
                throw new IllegalArgumentException("表达式语法错误：此处需要数字，实际是 \"" + bad + "\"");
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                int digitsStart = pos;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
                if (digitsStart == pos) {
                    pos = save; // 不是指数写法，"e" 留给语法检查报错
                }
            }
            String raw = src.substring(start, pos);
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException exc) {
                throw new IllegalArgumentException("表达式语法错误：无法识别数字 \"" + raw + "\"");
            }
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean eat(char expected) {
            if (pos < src.length() && src.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具注册表（给模型看的 schema + 本地执行分发）
    // ══════════════════════════════════════════════════════════════════

    public static final List<Map<String, Object>> TOOL_SCHEMAS = buildSchemas();

    private static List<Map<String, Object>> buildSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();
        schemas.add(function(
                "search_documents",
                "在已上传文档中检索相关段落，返回页码与原文片段。"
                        + "**回答任何关于文档内容的问题前，都必须先用本工具检索**，"
                        + "不要凭记忆或常识作答。可多次调用，换不同关键词。"
                        + "每条命中都带 `cite` 编号：引用该内容时必须在句末写 `[cite]`"
                        + "（如 `[1]`、`[1][3]`），编号只能用这里返回的，不得自己编造。",
                props(
                        "query", property("string", "检索关键词或问题，如“付款条款”“中标金额”"),
                        "top_k", property("integer", "返回片段数，默认 5，最多 10"),
                        "doc_id", property("string", "限定在某份文档内检索；不填则检索全部")),
                List.of("query")));
        schemas.add(function(
                "read_page",
                "读取某份文档指定页的完整原文。当检索片段不足以判断、需要看上下文时使用。"
                        + "返回里的 `cite` 是该页在本次问答里的引用编号，引用这页内容时用它标注（`[cite]`）。",
                props(
                        "doc_id", property("string",
                                "文档 ID（见 system 提示词里的文档清单，或 search_documents 的返回）"),
                        "page_no", property("integer", "页码，从 1 开始")),
                List.of("doc_id", "page_no")));
        schemas.add(function(
                "calculate",
                "做精确算术计算。**涉及金额求和、比例校验、差额计算时必须使用本工具**，"
                        + "不要自己心算。表达式只支持数字与 + - * / ( )。",
                props(
                        "expression", property("string",
                                "算式，如 2394690 + 3192920 + 1596460 + 798230")),
                List.of("expression")));
        return Collections.unmodifiableList(schemas);
    }

    /** 一个参数属性：{@code {type, description}}。 */
    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("description", description);
        return out;
    }

    /**
     * 按出现顺序拼装 {@code properties}（Python 的 dict 保序，schema 顺序也要稳定）。
     *
     * @param pairs {@code 参数名, 属性} 成对出现
     */
    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prop = (Map<String, Object>) pairs[i + 1];
            out.put(String.valueOf(pairs[i]), prop);
        }
        return out;
    }

    private static Map<String, Object> function(
            String name, String description, Map<String, Object> props, List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", props);
        parameters.put("required", required);

        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", fn);
        return Collections.unmodifiableMap(schema);
    }

    // ⚠️ 直接指向真实方法，**不要用 lambda 包一层**。
    // Python 踩过的坑：`inspect.signature(lambda **kw: ...)` 只能看到 `**kw`，
    // 会让参数过滤把模型传来的参数**全部丢掉**
    // （实测症状：`search_documents() missing 1 required positional argument: 'query'`）。
    // Java 里没有这个问题，但仍保持"未知参数一律丢弃"的行为：
    // execute() 只按工具取自己认识的参数，多余参数不报错。

    /**
     * 执行工具。<b>任何异常都转成结构化错误返回给模型</b>，不让它中断整个问答。
     */
    public Map<String, Object> execute(String name, Map<String, Object> arguments) {
        return execute(name, arguments, null);
    }

    /**
     * 执行工具（带本次问答的引用注册表）。
     *
     * <p>注册表只影响 {@code search_documents} / {@code read_page} 的返回里多一个 {@code cite}，
     * {@code calculate} 与错误分支完全不受影响。
     *
     * @param citations 本次问答的引用注册表；{@code null} 表示不登记
     */
    public Map<String, Object> execute(
            String name, Map<String, Object> arguments, CitationRegistry citations) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            if ("search_documents".equals(name)) {
                return searchDocuments(
                        asString(args.get("query")),
                        asInt(args.get("top_k"), (Integer) null),
                        asString(args.get("doc_id")),
                        citations);
            }
            if ("read_page".equals(name)) {
                return readPage(
                        asString(args.get("doc_id")),
                        asInt(args.get("page_no"), (Integer) null),
                        citations);
            }
            if ("calculate".equals(name)) {
                return calculate(asString(args.get("expression")));
            }
            return error("未知工具：" + name);
        } catch (Exception exc) {
            return error(exc.getClass().getSimpleName() + ": " + exc.getMessage());
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        return out;
    }

    /** 模型传参容错：数字可能是 {@code "10"}（字符串），也可能缺键。 */
    private static Integer asInt(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).strip());
        } catch (NumberFormatException exc) {
            return fallback;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static {
        // 防御：工具集只允许 3 个（list_documents 已于 2026-09-18 删除），
        // 且 schema 里的名字必须都能被 execute 分发（有测试钉住）。
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> schema : TOOL_SCHEMAS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) schema.get("function");
            names.add(String.valueOf(fn.get("name")));
        }
        if (!names.equals(Set.of("search_documents", "read_page", "calculate"))) {
            throw new IllegalStateException("工具集只允许 3 个工具（list_documents 已于 2026-09-18 删除），实际：" + names);
        }
    }
}
