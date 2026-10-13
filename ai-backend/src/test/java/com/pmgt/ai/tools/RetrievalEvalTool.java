package com.pmgt.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.module.eval.RetrievalEval;
import com.pmgt.ai.module.retrieval.DocMetaStore;
import com.pmgt.ai.module.retrieval.OpenSearchIndex;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.retrieval.SearchScope;
import com.pmgt.ai.module.retrieval.VecClient;
import com.pmgt.ai.module.store.DocStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>检索级评测的 CLI 入口</b>（recall@k / MRR / 命中率），手动跑、不在 {@code mvn test} 里。
 *
 * <h2>为什么放在 {@code src/test/java}（而不是开一个 {@code /admin/eval} 端点）</h2>
 * 这是本轮"二选一"的明确取舍，理由是三条实的：
 * <ol>
 *   <li><b>它需要外部真值 + 平台网关</b>：向量化/重排要连内网网关，真值页码要人工标注——
 *       这是<b>开发/验收期活动</b>，不是线上能力（放端点等于把开发工具塞进生产 jar，还要跟着一起发版）；</li>
 *   <li><b>AI 服务当前没有入站鉴权</b>（见 {@code ai-backend/README.md} 的运维边界）：
 *       新开一个"能对全库跑任意 query 检索"的端点等于开一个未鉴权的探测面，
 *       收益（省一次命令行）远小于风险；</li>
 *   <li><b>与既有先例一致</b>：{@link TopKCompareTool} 就是"手工对真集群"的同类工具，
 *       同样不带 {@code @Test}、同样只从环境变量读口令——两条工具一个口径，不引入第二种跑法。</li>
 * </ol>
 * 指标计算本身在<b>主源码</b>（{@code module.eval.RetrievalEval}，纯函数），
 * 因此它被 {@code RetrievalEvalTest} 覆盖（含 {@code citation_pages=null ⇒ SKIP} 的用例）——
 * "工具不入 CI"不等于"逻辑没测"。
 *
 * <h2>用例格式（沿用 {@code eval/smoke-30.json}，只读两个字段）</h2>
 * <pre>
 *   {
 *     "id": "D-002-001",
 *     "question": "中标通知书里约定的付款期限是多少天？",
 *     "scope": { "attachmentIds": [1321] },
 *     "expected": {
 *       "citation_pages": [2, 3],                       // 人工标注的页码；null/缺失 ⇒ 该题 SKIP
 *       "citation_chunk_keys": ["d=…:p=2:h=…:c=1:k=0"]  // 可选：更严格的"正确切片"（优先）
 *     }
 *   }
 * </pre>
 * {@code citation_pages=null} ⇒ 该题记 {@code SKIPPED_UNLABELED}，<b>不算失败</b>、退出码仍为 0；
 * 指标分母只统计已标注题（全未标注时打印 {@code n/a}，不是 0）。
 *
 * <h2>用法</h2>
 * <pre>
 *   mvn -o test-compile
 *   mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *   # Windows
 *   $cp = (Get-Content target/cp.txt -Raw).Trim()
 *   java -cp "target/classes;target/test-classes;$cp" com.pmgt.ai.tools.RetrievalEvalTool `
 *        --work ./work --eval ../eval/smoke-30.json --top-k 5
 *   # Linux
 *   CP=$(cat target/cp.txt)
 *   java -cp "target/classes:target/test-classes:$CP" com.pmgt.ai.tools.RetrievalEvalTool \
 *        --work ./work --eval ../eval/smoke-30.json --top-k 5
 * </pre>
 * 🔐 口令只从环境变量读：{@code LLM_API_KEY}（平台网关）、{@code OPENSEARCH_PASSWORD}（集群）。
 */
public final class RetrievalEvalTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RetrievalEvalTool() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            printUsage();
            return;
        }
        if (options.evalFile == null) {
            System.out.println("缺少 --eval <评测集 json>（或 --queries \"q1;q2;q3\"）；--help 看用法");
            System.exit(2);
            return;
        }
        List<RetrievalEval.Case> cases = options.evalFile != null
                ? readCases(options.evalFile)
                : queriesAsCases(options.queries);
        if (cases.isEmpty()) {
            System.out.println("评测集里没有可用题目（需要 cases[].question）：" + options.evalFile);
            System.exit(2);
            return;
        }
        System.out.println("== 检索级评测 ==");
        System.out.println("work=" + options.work + "  backend=" + options.backend
                + "  index=" + options.index + "  url=" + options.url
                + "  gateway=" + options.gateway + "  topK=" + options.topK
                + "  cases=" + cases.size()
                + "  password=" + (blank(System.getenv("OPENSEARCH_PASSWORD")) ? "未配置" : "已配置（环境变量）"));

        AiSettings settings = new AiSettings();
        settings.setWorkDir(options.work);
        settings.getGateway().setBaseUrl(options.gateway);
        settings.getGateway().setApiKey(envOrEmpty("LLM_API_KEY"));
        settings.getRetrieval().setBackend(options.backend);
        settings.getRetrieval().setTopK(options.topK);
        settings.getRetrieval().setRecall(options.recall);
        settings.getOpenSearch().setUrl(options.url);
        settings.getOpenSearch().setIndex(options.index);
        settings.getOpenSearch().setUsername(options.user);
        settings.getOpenSearch().setPassword(envOrEmpty("OPENSEARCH_PASSWORD"));

        DocStore docStore = DocStore.underWorkDir(options.work);
        int docs = 0;
        for (var ignored : docStore.allDocs()) {
            docs++;
        }
        System.out.println("文档库：" + docs + " 份文档（切片口径与入库一致：清洗 + 结构感知切分）");

        VecClient vec = new VecClient(settings, ModelRegistry.offline(settings), new SimpleMeterRegistry());
        OpenSearchIndex index = "opensearch".equalsIgnoreCase(options.backend)
                ? new OpenSearchIndex(settings, new SimpleMeterRegistry()) : null;
        RetrievalService retrieval = new RetrievalService(settings, vec, docStore, index,
                ModelRegistry.offline(settings), new SimpleMeterRegistry(), new DocMetaStore(options.work));

        RetrievalEval.Report report = RetrievalEval.run(cases, options.topK,
                (query, k) -> retrieval.searchInScope(query, k, SearchScope.empty()));

        for (RetrievalEval.CaseResult line : report.results()) {
            System.out.printf(java.util.Locale.ROOT, "  [%s] %s: found=%d expected=%d matched=%d recall=%.4f "
                            + "RR=%.4f %s%n",
                    line.status(), line.id(), line.found(), line.expected(), line.matched(),
                    line.recallAtK(), line.reciprocalRank(), line.note());
        }
        System.out.println();
        System.out.println(RetrievalEval.describe(report, options.topK));
        System.out.println("  " + report.note());
        if (options.out != null) {
            writeReport(options.out, report, options.topK);
            System.out.println("  报告已写入：" + options.out.toAbsolutePath());
        }
        // 退出码口径与 eval-ai.mjs 一致：0 = 无失败（含全跳过）；1 = 有失败
        System.exit(report.hasFailure() ? 1 : 0);
    }

    /** 读评测集：只读 {@code id/question/scope/expected.citation_pages|citationPages}。 */
    public static List<RetrievalEval.Case> readCases(Path file) throws Exception {
        List<RetrievalEval.Case> out = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            System.out.println("评测集不存在：" + (file == null ? "null" : file.toAbsolutePath()));
            return out;
        }
        JsonNode root = MAPPER.readTree(Files.readString(file));
        for (JsonNode item : root.path("cases")) {
            String question = item.path("question").asText("");
            if (question.isBlank()) {
                continue;
            }
            JsonNode expected = item.path("expected");
            List<Integer> pages = new ArrayList<>();
            JsonNode pageNode = expected.has("citation_pages") ? expected.path("citation_pages")
                    : expected.path("citationPages");
            if (pageNode.isArray()) {
                for (JsonNode page : pageNode) {
                    if (page.isNumber()) {
                        pages.add(page.asInt());
                    } else if (!page.asText("").isBlank()) {
                        try {
                            pages.add(Integer.parseInt(page.asText().strip()));
                        } catch (NumberFormatException ignored) {
                            // 标错的页码不静默当 0：跳过该值（下面的 note 会体现"标注为空"）
                        }
                    }
                }
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode key : expected.path("citation_chunk_keys")) {
                if (!key.asText("").isBlank()) {
                    keys.add(key.asText().strip());
                }
            }
            String docId = item.path("scope").path("doc_id").asText("");
            out.add(new RetrievalEval.Case(item.path("id").asText(""), question.strip(), docId,
                    pages, keys, item.path("note").asText("")));
        }
        return out;
    }

    private static List<RetrievalEval.Case> queriesAsCases(List<String> queries) {
        List<RetrievalEval.Case> out = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            // 手输 query 没有真值 ⇒ 必然 SKIP；它的用途是"看检索返回了什么"，不是算指标
            out.add(new RetrievalEval.Case("Q-" + (i + 1), queries.get(i), "", List.of(), List.of(),
                    "手输 query：无真值，必然 SKIP_UNLABELED"));
        }
        return out;
    }

    private static void writeReport(Path out, RetrievalEval.Report report, int topK) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "retrieval-eval");
        root.put("generated_at", java.time.OffsetDateTime.now().toString());
        root.put("top_k", topK);
        root.put("cases", report.cases());
        root.put("labeled", report.labeled());
        root.put("skipped_unlabeled", report.skippedUnlabeled());
        putNullable(root, "recall_at_k", report.recallAtK());
        putNullable(root, "mrr", report.mrr());
        putNullable(root, "hit_rate", report.hitRate());
        root.put("note", report.note());
        ArrayNode results = root.putArray("results");
        for (RetrievalEval.CaseResult line : report.results()) {
            ObjectNode node = results.addObject();
            node.put("id", line.id());
            node.put("status", line.status());
            node.put("found", line.found());
            node.put("expected", line.expected());
            node.put("matched", line.matched());
            node.put("recall_at_k", line.recallAtK());
            node.put("reciprocal_rank", line.reciprocalRank());
            node.put("hit", line.hit());
            ArrayNode keys = node.putArray("hit_chunk_keys");
            line.hitKeys().forEach(keys::add);
            node.put("note", line.note());
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private static void putNullable(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void printUsage() {
        System.out.println("""
                用法：java -cp "<依赖 classpath>" com.pmgt.ai.tools.RetrievalEvalTool [选项]
                  --work <目录>        AI 服务的 work 目录（含 docs/ 与 vectors/），默认 ./work
                  --eval <json>        评测集（读 cases[].question / expected.citation_pages），如 ../eval/smoke-30.json
                  --queries "q1;q2"    直接给 query（分号分隔；无真值 ⇒ 全部 SKIP，只看返回）
                  --top-k <n>          recall@k / MRR 的 k，默认 5
                  --recall <n>         召回道数（送进重排前的候选数），默认跟随配置 50
                  --backend <local|opensearch>  默认 local（进程内余弦 + work/vectors 缓存）
                  --url/--index/--user  OpenSearch 连接（backend=opensearch 时用）
                  --gateway <地址>     平台网关（向量化/重排），默认 http://10.254.208.35:8090/v1
                  --out <json>         把逐题结果写成 JSON（回归对比用）
                  --help
                口径：citation_pages=null 的题记 SKIPPED_UNLABELED（**跳过而不是失败**，退出码 0），
                      指标分母只统计已标注题；全未标注时打印 n/a（不是 0）。页码需人工标注（eval/README.md §1）。
                口令只从环境变量读：LLM_API_KEY、OPENSEARCH_PASSWORD。
                """);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String envOrEmpty(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    /** 命令行参数（口令刻意不在此列：只从环境变量读）。 */
    private static final class Options {
        private Path work = Path.of("./work");
        private Path evalFile;
        private Path out;
        private String url = "http://10.254.212.110:9200";
        private String index = "pm-ai-chunks";
        private String user = "admin";
        private String gateway = "http://10.254.208.35:8090/v1";
        private String backend = "local";
        private int topK = 5;
        private int recall = 50;
        private boolean help;
        private final List<String> queries = new ArrayList<>();

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                switch (key) {
                    case "--work" -> options.work = Path.of(args[++i]);
                    case "--eval" -> options.evalFile = Path.of(args[++i]);
                    case "--out" -> options.out = Path.of(args[++i]);
                    case "--url" -> options.url = args[++i];
                    case "--index" -> options.index = args[++i];
                    case "--user" -> options.user = args[++i];
                    case "--gateway" -> options.gateway = args[++i];
                    case "--backend" -> options.backend = args[++i];
                    case "--top-k" -> options.topK = Integer.parseInt(args[++i]);
                    case "--recall" -> options.recall = Integer.parseInt(args[++i]);
                    case "--help", "-h" -> options.help = true;
                    case "--queries" -> {
                        for (String query : args[++i].split(";")) {
                            if (!query.isBlank()) {
                                options.queries.add(query.strip());
                            }
                        }
                    }
                    default -> System.out.println("忽略未知参数：" + key);
                }
            }
            return options;
        }
    }

    /** 供文档引用：用例字段名清单（避免"文档写一套、代码读另一套"）。 */
    public static Map<String, String> caseFields() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", "题目编号（原样回显）");
        out.put("question", "检索 query（必填；为空则该题被忽略）");
        out.put("scope.doc_id", "可选：只认该文档的命中（否则 A 文档第 3 页会命中 B 文档第 3 页）");
        out.put("expected.citation_pages", "人工标注的正确页码（数组）；null/缺失 ⇒ 该题 SKIP_UNLABELED");
        out.put("expected.citation_chunk_keys", "可选：正确切片键（优先于页码，最严格）");
        return out;
    }
}
