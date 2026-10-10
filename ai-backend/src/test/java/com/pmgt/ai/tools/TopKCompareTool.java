package com.pmgt.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.retrieval.DocMetaStore;
import com.pmgt.ai.module.retrieval.OpenSearchIndex;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.retrieval.VecClient;
import com.pmgt.ai.module.store.DocStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>离线可对真的 top-k 一致性比对工具</b>：同一批切片 + 同一组 query，
 * 分别用 {@code local}（进程内余弦）与 {@code opensearch}（集群 kNN）检索，
 * 打印两边的 top-k 重合率与耗时。
 *
 * <p><b>它不在 {@code mvn test} 里</b>（名字不以 Test 结尾、没有 {@code @Test}），
 * 因为它需要真集群 + 平台网关（内网），评测环境没有。用法（在 {@code ai-backend/} 下）：
 *
 * <pre>
 *   mvn -o test-compile
 *   mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt      # 生成依赖 classpath
 *   # Windows（分号分隔）
 *   $cp = (Get-Content target/cp.txt -Raw).Trim()
 *   java -cp "target/classes;target/test-classes;$cp" com.pmgt.ai.tools.TopKCompareTool `
 *        --work ./work --url http://10.254.212.110:9200 --user admin `
 *        --eval ../eval/smoke-30.json --top-k 5 --reindex
 *   # Linux（冒号分隔）
 *   CP=$(cat target/cp.txt)
 *   java -cp "target/classes:target/test-classes:$CP" com.pmgt.ai.tools.TopKCompareTool \
 *        --work ./work --url http://10.254.212.110:9200 --user admin --eval ../eval/smoke-30.json
 * </pre>
 *
 * <p>为什么放在 {@code src/test/java}：它依赖测试专有的东西（真集群、真网关、评测集），
 * 不该进生产 jar；同时它<b>不带 {@code @Test}</b>，所以 surefire 不会把它当用例跑——
 * 「手工对真集群」与「CI 离线全绿」这两件事必须分开（要求里明确写了这一点）。
 *
 * <p>口径说明（<b>为什么比的是"重合率"而不是"分数相等"</b>）：
 * 两条后端的相似度数值不必逐位相等（OpenSearch 的 {@code _score} 经过引擎内部换算），
 * 真正要求一致的是<b>排序</b>——同一份语料、同一个 query，top-k 的<b>切片集合与顺序</b>
 * 应当基本一致；不一致通常意味着 {@code space_type}／字段名／向量写错
 * （见 {@code docs/OpenSearch部署与接入.md} 步骤 19 的验证口径）。
 *
 * <p>🔐 <b>口令只从环境变量读</b>（{@code OPENSEARCH_PASSWORD}、{@code LLM_API_KEY}），
 * 命令行参数里不接收明文口令，输出里也绝不打印它。
 */
public final class TopKCompareTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopKCompareTool() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help || options.queries.isEmpty()) {
            printUsage();
            if (options.help) {
                return;
            }
        }
        System.out.println("== top-k 一致性比对（local vs opensearch）==");
        System.out.println("work=" + options.work
                + "  index=" + options.index
                + "  url=" + options.url
                + "  gateway=" + options.gateway
                + "  topK=" + options.topK
                + "  queries=" + options.queries.size()
                + "  password=" + (blank(System.getenv("OPENSEARCH_PASSWORD")) ? "未配置" : "已配置（来自环境变量）"));

        DocStore docStore = DocStore.underWorkDir(options.work);
        int docs = 0;
        for (var ignored : docStore.allDocs()) {
            docs++;
        }
        System.out.println("文档库：" + docs + " 份文档");

        // 两条后端共用同一个 VecClient（同一个平台网关、同一把 sk）：向量必须来自同一处，比对才有意义
        AiSettings shared = new AiSettings();
        shared.setWorkDir(options.work);
        shared.getGateway().setBaseUrl(options.gateway);
        shared.getGateway().setApiKey(envOrEmpty("LLM_API_KEY"));
        VecClient vec = new VecClient(shared, ModelRegistry.offline(shared), new SimpleMeterRegistry());

        AiSettings localSettings = new AiSettings();
        localSettings.setWorkDir(options.work);
        localSettings.getGateway().setBaseUrl(options.gateway);
        localSettings.getGateway().setApiKey(envOrEmpty("LLM_API_KEY"));
        localSettings.getRetrieval().setBackend("local");
        localSettings.getRetrieval().setTopK(options.topK);

        AiSettings osSettings = new AiSettings();
        osSettings.setWorkDir(options.work);
        osSettings.getGateway().setBaseUrl(options.gateway);
        osSettings.getGateway().setApiKey(envOrEmpty("LLM_API_KEY"));
        osSettings.getRetrieval().setBackend("opensearch");
        osSettings.getRetrieval().setTopK(options.topK);
        osSettings.getOpenSearch().setUrl(options.url);
        osSettings.getOpenSearch().setIndex(options.index);
        osSettings.getOpenSearch().setUsername(options.user);
        osSettings.getOpenSearch().setPassword(envOrEmpty("OPENSEARCH_PASSWORD"));

        RetrievalService local = new RetrievalService(localSettings, vec, docStore, null,
                ModelRegistry.offline(localSettings), new SimpleMeterRegistry(), new DocMetaStore(options.work));
        OpenSearchIndex index = new OpenSearchIndex(osSettings, new SimpleMeterRegistry());
        RetrievalService openSearch = new RetrievalService(osSettings, vec, docStore, index,
                ModelRegistry.offline(osSettings), new SimpleMeterRegistry(), new DocMetaStore(options.work));

        if (options.reindex) {
            System.out.println("--reindex：先把文档库按 chunk_key 幂等灌进 " + options.index + "（跳过已存在）");
            DocIndexer indexer = new DocIndexer(osSettings, docStore, openSearch, index,
                    new SimpleMeterRegistry(), new DocMetaStore(options.work));
            int done = 0;
            for (var doc : docStore.allDocs()) {
                DocIndexer.IndexOutcome outcome = indexer.indexDocument(doc, true, null);
                done++;
                System.out.println("  [" + done + "/" + docs + "] " + doc.getDocId() + " → " + outcome.describe());
            }
            System.out.println("索引里现有切片数：" + index.count());
        }

        double overlapSum = 0;
        double top1Same = 0;
        double localMsSum = 0;
        double osMsSum = 0;
        int compared = 0;
        for (String query : options.queries) {
            long t0 = System.nanoTime();
            List<String> left = keysOf(local.search(query, options.topK, null));
            double localMs = (System.nanoTime() - t0) / 1_000_000.0;

            long t1 = System.nanoTime();
            List<String> right = keysOf(openSearch.search(query, options.topK, null));
            double osMs = (System.nanoTime() - t1) / 1_000_000.0;

            double overlap = overlapAtK(left, right, options.topK);
            overlapSum += overlap;
            if (!left.isEmpty() && !right.isEmpty() && left.get(0).equals(right.get(0))) {
                top1Same++;
            }
            localMsSum += localMs;
            osMsSum += osMs;
            compared++;

            System.out.printf(Locale.ROOT,
                    "%n[%d] %s%n  local      (%6.1fms): %s%n  opensearch (%6.1fms): %s%n  top-%d 重合率 %.2f%n",
                    compared, query, localMs, left, osMs, right, options.topK, overlap);
        }

        if (compared > 0) {
            System.out.printf(Locale.ROOT, "%n== 汇总 ==%n  平均 top-%d 重合率：%.3f%n  top-1 一致：%d/%d%n"
                            + "  平均耗时：local %.1fms ｜ opensearch %.1fms%n"
                            + "  ⚠️ 重合率明显偏低（< 0.8）时先查：space_type / 字段名 / 向量是否写错，"
                            + "以及索引是否漏灌（见 docs/OpenSearch部署与接入.md 步骤 19）%n",
                    options.topK, overlapSum / compared, (int) top1Same, compared,
                    localMsSum / compared, osMsSum / compared);
        }
        System.out.println("（本工具不写任何数据；--reindex 时才会向索引写入，写入是幂等的）");
    }

    /** 命中的切片键（{@code docId:pageNo:sha1前12}）——比页码更严格：内容变了键就变。 */
    private static List<String> keysOf(SearchPort.SearchResult result) {
        List<String> out = new ArrayList<>();
        if (result == null || result.hits() == null) {
            return out;
        }
        for (SearchPort.Hit hit : result.hits()) {
            out.add(hit.docId() + ":" + hit.pageNo());
        }
        return out;
    }

    /** 前 k 位的重合率（0~1）：两边各自 top-k 的集合交集 ÷ k。 */
    private static double overlapAtK(List<String> left, List<String> right, int k) {
        if (k <= 0) {
            return 0;
        }
        Set<String> l = new LinkedHashSet<>(left.subList(0, Math.min(k, left.size())));
        Set<String> r = new LinkedHashSet<>(right.subList(0, Math.min(k, right.size())));
        int common = 0;
        for (String key : l) {
            if (r.contains(key)) {
                common++;
            }
        }
        return (double) common / k;
    }

    private static void printUsage() {
        System.out.println("""
                用法：java -cp "<依赖 classpath>" com.pmgt.ai.tools.TopKCompareTool [选项]
                  （先生成依赖 classpath：mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt）
                  --work <目录>         AI 服务的 work 目录（含 docs/ 与 vectors/），默认 ./work
                  --url <地址>          OpenSearch 地址，默认 http://10.254.212.110:9200（生产；.111 是冷备/演练机）
                  --index <索引名>      默认 pm-ai-chunks
                  --user <用户名>       默认 admin
                  --gateway <地址>      平台网关（向量化/重排），默认 http://10.254.208.35:8090/v1
                  --eval <json>         评测集（读 cases[].question 作为 query），如 ../eval/smoke-30.json
                  --queries "q1;q2"     直接给 query（分号分隔）
                  --top-k <n>           top-k，默认 5
                  --reindex             比对前先把文档库幂等灌进索引（跳过已存在）
                  --help
                口令只从环境变量读：OPENSEARCH_PASSWORD（OpenSearch）、LLM_API_KEY（平台网关）。
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
        private String url = "http://10.254.212.110:9200";
        private String index = "pm-ai-chunks";
        private String user = "admin";
        private String gateway = "http://10.254.208.35:8090/v1";
        private int topK = 5;
        private boolean reindex;
        private boolean help;
        private final List<String> queries = new ArrayList<>();

        private static Options parse(String[] args) throws Exception {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                switch (key) {
                    case "--work" -> options.work = Path.of(args[++i]);
                    case "--url" -> options.url = args[++i];
                    case "--index" -> options.index = args[++i];
                    case "--user" -> options.user = args[++i];
                    case "--gateway" -> options.gateway = args[++i];
                    case "--top-k" -> options.topK = Integer.parseInt(args[++i]);
                    case "--reindex" -> options.reindex = true;
                    case "--help", "-h" -> options.help = true;
                    case "--eval" -> options.queries.addAll(readEval(args[++i]));
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

        /** 读评测集的 {@code cases[].question}（只取问题文本，不依赖任何真值字段）。 */
        private static List<String> readEval(String path) throws Exception {
            List<String> out = new ArrayList<>();
            Path file = Path.of(path);
            if (!java.nio.file.Files.isRegularFile(file)) {
                System.out.println("评测集不存在，忽略 --eval：" + file.toAbsolutePath());
                return out;
            }
            JsonNode root = MAPPER.readTree(java.nio.file.Files.readString(file));
            for (JsonNode item : root.path("cases")) {
                String question = item.path("question").asText("");
                if (!question.isBlank()) {
                    out.add(question.strip());
                }
            }
            return out;
        }
    }
}
