package com.pmgt.ai.module.llm;

import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <caption>工具的存在理由（前三个始终注册；第四个见下文"条件注册"）</caption>
 *   <tr><th>工具</th><th>为什么必须做成工具</th></tr>
 *   <tr><td>{@code search_documents}</td><td>长文档塞不进上下文，必须按需检索</td></tr>
 *   <tr><td>{@code read_page}</td><td>检索到的片段可能不够，需要读整页原文核对</td></tr>
 *   <tr><td>{@code calculate}</td><td><b>模型算数不可靠</b>，金额求和/比例校验必须交给代码</td></tr>
 *   <tr><td>{@code query_business_data}</td><td><b>模型看不到业务数据</b>：项目/合同/付款/附件数量
 *       这类事实只能由主系统的受控查询给出，模型自己数文档或推算必然错（P2，条件注册）</td></tr>
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
 *
 * <h2>条件注册：{@code query_business_data}（P2 受控查询，2026-09-23）</h2>
 *
 * <p>第 4 个工具 {@code query_business_data} 只在主系统的 {@code /chat} 请求带了
 * {@code biz_query} 时才对模型可见（{@link #schemas(BizQuerySpec)}）——它是"反向回调"：
 * 地址与短时效 scope_token 由主系统每次现给，AI 侧不直连库、不生成 SQL
 * （契约见 {@code docs/AI前端与集成方案.md} §11）。
 *
 * <p>⚠️ <b>它是请求级注册，不是全局第 4 个工具</b>：不传 {@code biz_query} 时工具清单与顺序<b>逐字节不变</b>
 * （3 个：search_documents / read_page / calculate），老调用方的行为完全不受影响，也便于两侧分批发版。
 * 因此校验也相应放宽为"集合校验"——见类尾的 {@code static} 块：基础集固定，条件集按请求 +1。
 */
@Component
public class Tools {

    /** 表达式长度上限（挡 `9**9**9` 之外的"硬算超长表达式"）。 */
    public static final int MAX_EXPRESSION_CHARS = 200;

    /** {@code read_page} 单页返回上限：整页原文可能很长，截断并明确告知模型。 */
    public static final int MAX_PAGE_CHARS = 4000;

    /** 条件注册的工具名（与 {@code execute} 的分发、schema 里的名字必须一致）。 */
    public static final String BIZ_QUERY_TOOL_NAME = "query_business_data";

    /** 合法实体枚举（§11.3 写死，顺序即 schema 里 {@code enum} 的顺序）。 */
    public static final List<String> BIZ_ENTITIES =
            List.of("projects", "contracts", "payments", "stats");

    /** 基础工具集（**始终注册**）：这三个的名字与顺序是对外契约，不许动。 */
    public static final Set<String> BASE_TOOL_NAMES =
            Set.of("search_documents", "read_page", "calculate");

    private final SearchPort searchPort;
    private final DocStore docStore;
    private final BizQueryClient bizQueryClient;

    /**
     * 正式装配（Spring 用这个构造器）。
     *
     * <p>单例只持有<b>无状态</b>协作者；请求级的 {@link BizQuerySpec} 一律走参数传进来
     * （与 {@link CitationRegistry} 同一个纪律）。
     */
    @Autowired
    public Tools(SearchPort searchPort, DocStore docStore, BizQueryClient bizQueryClient) {
        this.searchPort = searchPort;
        this.docStore = docStore;
        this.bizQueryClient = bizQueryClient;
    }

    /**
     * 只接检索与文档库的构造器：给老调用点/纯检索单测用（没有业务查询通道，
     * 此时 {@code query_business_data} 只返回结构化错误）。
     */
    public Tools(SearchPort searchPort, DocStore docStore) {
        this(searchPort, docStore, null);
    }

    /**
     * 给模型看的工具清单（OpenAI 兼容 {@code tools} 字段）。
     *
     * <p>基础 3 个：{@code search_documents} / {@code read_page} / {@code calculate}，
     * <b>不含 {@code list_documents}</b>（见类注释）；也不含条件注册的 {@code query_business_data}
     * ——要带上它就调 {@link #schemas(BizQuerySpec)}。
     */
    public List<Map<String, Object>> schemas() {
        return TOOL_SCHEMAS;
    }

    /**
     * 本次问答的工具清单：{@code bizQuery} 非空（主系统给了受控查询通道）时<b>追加</b>
     * {@code query_business_data}，否则与 {@link #schemas()} 完全相同。
     *
     * <p>为什么要按请求给清单：模型只能调用它"看得见"的工具。没通道却把工具列出去，
     * 模型会去调一个必然失败的工具，白烧一轮往返（§11.2 明确要求"不传就不注册"）。
     */
    public List<Map<String, Object>> schemas(BizQuerySpec bizQuery) {
        if (bizQuery == null) {
            return TOOL_SCHEMAS;
        }
        List<Map<String, Object>> out = new ArrayList<>(TOOL_SCHEMAS);
        out.add(BIZ_QUERY_SCHEMA);
        return Collections.unmodifiableList(out);
    }

    /** 分发用的工具名集合——必须与 {@link #schemas()} 里的名字完全一致。 */
    public Set<String> toolNames() {
        return namesOf(TOOL_SCHEMAS);
    }

    /** 本次问答的工具名集合；{@code bizQuery} 非空时比 {@link #toolNames()} 多一个。 */
    public Set<String> toolNames(BizQuerySpec bizQuery) {
        return namesOf(schemas(bizQuery));
    }

    private static Set<String> namesOf(List<Map<String, Object>> schemas) {
        return schemas.stream()
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
    // 工具 4（条件注册）：受控查询主系统的业务数据
    // ══════════════════════════════════════════════════════════════════

    // 为什么它必须是"工具"：项目有几个附件、预算多少、已付多少这类**事实**，
    // 文档里根本没有（文档只有条款与条文），模型只能数已入库的文档、或者干脆编一个数。
    // 这些数字必须来自主系统的受控查询（AI 侧不直连库、不生成 SQL，见 §8.2/§11.1），
    // 而且返回里的口径（caliber）与数据时间（data_time）要能原话转述，数字才可审计。

    /**
     * 查主系统里的业务事实（项目 / 合同 / 付款 / 统计）。
     *
     * <p>参数容错与错误映射都在这里收口，<b>任何情况都返回结构化结果、绝不抛异常</b>：
     * <ul>
     *   <li>{@code bizQuery == null}（本次问答没开通道）→ 结构化错误（模型本来也不该看到这个工具）；</li>
     *   <li>模型幻觉出的 {@code entity} → 结构化错误并列出合法枚举，引导它重试；</li>
     *   <li>越出本次授权 {@code entities} 的实体 → 说清"无权"，不是"没有数据"；</li>
     *   <li>HTTP 层的一切（4xx/5xx/连不上/超时）→ {@link BizQueryClient} 已映射成结构化错误。</li>
     * </ul>
     *
     * @param arguments 模型给的工具参数（{@code entity} / {@code filters} / {@code limit}）
     * @param bizQuery  本次问答的受控查询通道（请求级；{@code null} 表示未开通）
     */
    public Map<String, Object> queryBusinessData(Map<String, Object> arguments, BizQuerySpec bizQuery) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        if (bizQuery == null) {
            return error("本次问答未开通业务数据查询（请求里没有 biz_query），该工具不可用；"
                    + "请如实告知用户查不到系统数据，不要用文档内容或常识顶替。");
        }
        String raw = asString(args.get("entity"));
        if (raw == null || raw.isBlank()) {
            return error("缺少 entity 参数。" + entityHint());
        }
        // 大小写宽容（模型偶尔写 Projects）：entity 是枚举，不是用户数据，做归一不会误伤
        String entity = raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (!BIZ_ENTITIES.contains(entity)) {
            return error("entity「" + raw.strip() + "」不是合法取值。" + entityHint());
        }
        if (!bizQuery.allows(entity)) {
            return error("本次授权范围（biz_query.entities=" + bizQuery.entities() + "）不包含「" + entity
                    + "」；请改用授权范围内的实体，或如实告知用户这类数据不在本次授权范围内。");
        }
        Map<String, Object> filters = filtersOf(args.get("filters"));
        if (filters == null) {
            return error("filters 必须是 JSON 对象（形如 {\"projectId\":12}），收到的是："
                    + asString(args.get("filters")) + "。请按该 entity 支持的字段重传。");
        }
        if (bizQueryClient == null) {
            return error("业务数据查询客户端未装配（服务内部错误），本次查不到系统数据；"
                    + "请如实说明，不要用文档内容或常识顶替。");
        }
        return bizQueryClient.query(bizQuery, entity, filters, asInt(args.get("limit"), (Integer) null));
    }

    /** 幻觉实体的引导语：把合法枚举与中文含义一次说清，模型第二轮就能改对。 */
    private static String entityHint() {
        return "合法取值只有四个：projects（项目事实，含每阶段附件数）、contracts（合同清单）、"
                + "payments（付款记录）、stats（统计口径，kind=phase_attachment_count 可查各阶段附件数）。";
    }

    /**
     * {@code filters} 容错：缺失/空视为"无过滤"；对象直接用；
     * 模型偶尔把对象序列化成字符串（{@code "{\"projectId\":12}"}），这里解一层。
     *
     * @return 过滤条件；{@code null} 表示传了但不是对象（无法修复，交给上层报错）
     */
    private static Map<String, Object> filtersOf(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                Map<?, ?> parsed = JSON_MAPPER.readValue(text, Map.class);
                Map<String, Object> out = new LinkedHashMap<>();
                parsed.forEach((key, item) -> out.put(String.valueOf(key), item));
                return out;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** 只用于 {@code filters} 的"字符串里套了 JSON"容错，不参与别处序列化。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ══════════════════════════════════════════════════════════════════
    // 工具注册表（给模型看的 schema + 本地执行分发）
    // ══════════════════════════════════════════════════════════════════

    public static final List<Map<String, Object>> TOOL_SCHEMAS = buildSchemas();

    /** 条件注册的工具 schema（**不进** {@link #TOOL_SCHEMAS}，只在请求带 biz_query 时追加）。 */
    public static final Map<String, Object> BIZ_QUERY_SCHEMA = buildBizQuerySchema();

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

    /**
     * {@code query_business_data} 的 schema（<b>条件注册</b>，见 {@link #schemas(BizQuerySpec)}）。
     *
     * <p>描述按 §11.3/§11.5 写死三件事：①"这类事实必须用它，不得靠文档推测、不得自行推算"；
     * ②"文档内容请走 search_documents / read_page"；③<b>逐实体列出支持的 filters 字段与示例</b>
     * （清单只来自 §11.4，不自己发明字段——模型照着编 filters 是最容易出错的地方）。
     */
    private static Map<String, Object> buildBizQuerySchema() {
        return function(
                BIZ_QUERY_TOOL_NAME,
                "查询主系统里的**业务事实**（不是文档内容）：项目 / 合同 / 付款记录 / 统计口径。"
                        + "**项目/合同/付款/附件数量/统计口径这类事实必须用它**，"
                        + "不得靠文档推测、不得自行推算（也不要把文档里数出来的份数当成系统里的数量）；"
                        + "条款、金额条文、验收标准这类**文档内容**请用 search_documents / read_page。"
                        + "filters 只能用下面列出的结构化字段（不收 SQL / 表达式 / 字段名拼接）；"
                        + "返回里的 `caliber`（口径）与 `data_time`（数据时间）必须原话转述，"
                        + "`scope` 是本次授权范围；`rows: []` + `note` 表示该范围内没有匹配数据（不是出错），"
                        + "`error` 表示调用失败或越权（也不等于没有数据）。"
                        + "\n各 entity 支持的 filters 字段（`?` = 选填）："
                        + "\n- projects：项目事实（名称/编号/类型/状态/当前阶段/进度/预算/已付/合同数，"
                        + "以及每阶段附件数 phases[].attachmentCount）——"
                        + "filters: projectId?、name?、status?、type?、year?；"
                        + "例 {\"projectId\":12}"
                        + "\n- contracts：合同清单（名称/编号/供应商/金额/状态/签订日期/覆盖的子项目）——"
                        + "filters: projectId?、vendorName?；"
                        + "例 {\"projectId\":12,\"vendorName\":\"某某科技有限公司\"}"
                        + "\n- payments：付款记录（节点/计划金额/已付金额/日期/状态/归属合同）——"
                        + "filters: projectId?、nodeCode?、status?；"
                        + "例 {\"projectId\":12,\"status\":\"PAID\"}"
                        + "\n- stats：聚合值——filters: projectId?、kind（必填："
                        + "phase_attachment_count＝某项目各阶段附件数、type_distribution＝项目类型分布、"
                        + "year_amount＝按年度金额）；"
                        + "例 {\"projectId\":12,\"kind\":\"phase_attachment_count\"}",
                props(
                        "entity", property("string",
                                "要查的实体，只能是这四个之一：projects（项目）、contracts（合同）、"
                                        + "payments（付款）、stats（统计）",
                                BIZ_ENTITIES),
                        "filters", property("object",
                                "结构化过滤条件，字段随 entity 不同（见本工具描述里的清单），"
                                        + "如 {\"projectId\":12}；不填表示不加过滤"),
                        "limit", property("integer",
                                "返回条数上限，默认 " + BizQueryClient.DEFAULT_LIMIT
                                        + "，最大 " + BizQueryClient.MAX_LIMIT)),
                List.of("entity"));
    }

    /** 一个参数属性：{@code {type, description}}。 */
    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("description", description);
        return out;
    }

    /** 带 {@code enum} 的参数属性（枚举值写进 schema，模型第一轮就不容易幻觉）。 */
    private static Map<String, Object> property(String type, String description, List<String> allowed) {
        Map<String, Object> out = property(type, description);
        out.put("enum", new ArrayList<>(allowed));
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
        return execute(name, arguments, citations, null);
    }

    /**
     * 执行工具（带引用注册表 + 本次问答的受控查询通道）。
     *
     * <p>{@code bizQuery} 只影响条件注册的 {@code query_business_data}：其它三个工具与错误分支
     * 完全不受影响——这正是"新增工具不改老行为"的落点（老调用点仍走上面的三参重载）。
     *
     * @param citations 本次问答的引用注册表；{@code null} 表示不登记
     * @param bizQuery  本次问答的受控查询通道；{@code null} 表示未开通
     *                  （此时 {@code query_business_data} 返回结构化错误而不是抛异常）
     */
    public Map<String, Object> execute(
            String name,
            Map<String, Object> arguments,
            CitationRegistry citations,
            BizQuerySpec bizQuery) {
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
            if (BIZ_QUERY_TOOL_NAME.equals(name)) {
                return queryBusinessData(args, bizQuery);
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
        // 防御（2026-09-23 按新事实更新）：**不再写死"总共 3 个"**——注册集现在是"基础集 + 按请求条件注册"，
        // 写死总数会让"带上 biz_query 的那条路径"变成非法状态。改为两次集合校验：
        //   ① 基础集必须恰好是这 3 个（list_documents 已于 2026-09-18 删除，不许顺手加回来）；
        //   ② 条件注册的工具名必须与 execute 的分发名一致，且不与基础集重名
        //      （重名会让 schemas(bizQuery) 里出现两个同名工具，模型行为不可预期）。
        Set<String> base = namesOf(TOOL_SCHEMAS);
        if (!base.equals(BASE_TOOL_NAMES)) {
            throw new IllegalStateException("基础工具集固定为 " + BASE_TOOL_NAMES
                    + "（list_documents 已于 2026-09-18 删除；query_business_data 是条件注册的，不进基础集）。实际：" + base);
        }
        String conditional = String.valueOf(
                ((Map<?, ?>) BIZ_QUERY_SCHEMA.get("function")).get("name"));
        if (!BIZ_QUERY_TOOL_NAME.equals(conditional) || base.contains(conditional)) {
            throw new IllegalStateException("条件注册的工具名必须是把 " + BIZ_QUERY_TOOL_NAME + " 且不与基础集重名，实际："
                    + conditional);
        }
    }
}
