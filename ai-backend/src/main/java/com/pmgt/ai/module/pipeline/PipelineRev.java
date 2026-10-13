package com.pmgt.ai.module.pipeline;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.store.DocStore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流水线版本（{@code pipeline_rev}）与切片键版本（设计 §3.3.3，T7 的落点）。
 *
 * <h2>要解决的问题</h2>
 * <p>切片键原先是 {@code docId:pageNo:sha1(文本)[0:12]}。清洗规则一改，全部切片文本变化 ⇒
 * 全部切片键变化 ⇒ 全部向量缓存与索引失效。仅靠"文本哈希"虽然也能检测到变化，
 * 但有两个漏洞：
 * <ol>
 *   <li><b>规则改了、结果恰好没变</b>的切片，键不变 ⇒ 无法回答"这份文档是用哪版规则切的"；</li>
 *   <li>规则改了但<b>切分参数变了</b>（同样文本切成不同片）时，文本哈希完全看不出来。</li>
 * </ol>
 *
 * <h2>三层防护（本类负责 L1 与 L2）</h2>
 * <ol>
 *   <li><b>L1 切片键增补版本段</b>（{@code ai.pipeline.chunk-key-format=rev}）：
 *       {@code d=<docId>:p=<pageNo>:h=<sha1-12>:c=<cleanRev>:k=<chunkRev>}。
 *       规则变更 ⇒ 新键 ≠ 旧键 ⇒ 旧索引不会被误当命中。
 *       <b>默认是 {@code legacy}</b>（三段，与接入前逐字节一致）——因为升级不该让既有索引与向量缓存
 *       凭空失效；要吃到这层防护就显式切到 {@code rev} 并跑一次 {@code POST /admin/reindex}。</li>
 *   <li><b>L2 向量缓存有效性校验</b>：{@code work/vectors/<docId>.json} 的头部从
 *       {@code {model,dimensions}} 扩为 {@code {model,dimensions,clean_rev,chunk_rev}}，
 *       不匹配即<b>整体失效并重算</b>（与"模型名/维度不符即失效"同一写法，见
 *       {@code RetrievalService.loadVectors}）。即使 L1 没开，这一层也生效。</li>
 *   <li>L3 重建调度（按 {@code pipeline_rev} 差异只重建受影响的文档）——属于主系统侧 {@code force} 语义，
 *       本轮 AI 侧只提供 {@link #pipelineRev()} 与 {@link #taskKey(String)} 两个口径。</li>
 * </ol>
 *
 * <h2>为什么用"静态当前值"</h2>
 * <p>切片键是<b>纯函数</b>（{@code RetrievalService.chunkKey} 是 static），它的调用点遍布
 * 入库、重建、检索、与 OpenSearch 文档组装；要把 rev 一路显式传下去会改十几个签名，
 * 且很容易在某一处漏传 ⇒ 同一份切片算出两个键（比"没版本"更糟）。
 * 所以这里用"进程内唯一的当前版本"（由 Spring 在装配 {@code RetrievalService} 时写入），
 * 保证<b>同一时刻全进程只有一个切片键口径</b>。它只有两个字段、启动后不再变化
 * （"改配置要重启"与设计 §5.6 的结论一致）。
 */
public final class PipelineRev {

    /** 三段旧键（与接入前逐字节一致）：{@code docId:pageNo:sha1[0:12]}。 */
    public static final String FORMAT_LEGACY = "legacy";
    /** 五段新键：{@code d=<docId>:p=<pageNo>:h=<sha1-12>:c=<cleanRev>:k=<chunkRev>}。 */
    public static final String FORMAT_REV = "rev";

    /** 新旧两种键的解析器（兼容读，见设计 §3.3.3 L1 的"解析器同时兼容旧 3 段格式"）。 */
    private static final Pattern LEGACY_KEY =
            Pattern.compile("^(?<doc>[0-9a-fA-F]+):(?<page>\\d+):(?<hash>[0-9a-f]{4,40})$");
    private static final Pattern REV_KEY = Pattern.compile(
            "^d=(?<doc>[0-9a-fA-F]+):p=(?<page>\\d+):h=(?<hash>[0-9a-f]{4,40}):c=(?<clean>\\d+):k=(?<chunk>\\d+)$");

    private static final Object LOCK = new Object();
    private static volatile String format = FORMAT_LEGACY;
    private static volatile Revisions active = new Revisions(0, 0, 0);

    private PipelineRev() {
    }

    /**
     * 版本三元组 + 派生的 {@code pipeline_rev} 字符串。
     *
     * <p>{@code pipeline_rev} 的组合口径（设计 §3.3.3）：
     * {@code parse=<n>,clean=<n>,chunk=<n>,embed=<模型+维度 sha1 前 8 位>}。
     */
    public record Revisions(int parseRev, int cleanRev, int chunkRev, String embedRev) {

        public Revisions(int parseRev, int cleanRev, int chunkRev) {
            this(parseRev, cleanRev, chunkRev, "");
        }

        /** {@code embed_model_rev = sha1(embed_model 名 + dimensions) 前 8 位}（换模型/维度也要重建）。 */
        public static String embedRevOf(String model, int dimensions) {
            return sha1Hex((model == null ? "" : model) + "|" + dimensions).substring(0, 8);
        }

        public static Revisions of(AiSettings settings) {
            AiSettings.Pipeline pipeline = settings.getPipeline();
            return new Revisions(pipeline.getParseRev(), pipeline.getCleanRev(), pipeline.getChunkRev(),
                    embedRevOf(settings.getVec().getEmbedModel(), settings.getVec().getDimensions()));
        }

        /** {@code parse=1,clean=0,chunk=0,embed=1a2b3c4d}。 */
        public String pipelineRev() {
            return "parse=" + parseRev + ",clean=" + cleanRev + ",chunk=" + chunkRev
                    + (embedRev == null || embedRev.isBlank() ? "" : ",embed=" + embedRev);
        }

        /** 向量缓存校验用的短口径（只关心改变文本/切分的那两段）。 */
        public String cacheTag() {
            return "clean=" + cleanRev + ",chunk=" + chunkRev;
        }

        public int cleanRev() {
            return cleanRev;
        }

        public int chunkRev() {
            return chunkRev;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 当前口径（进程内唯一）
    // ══════════════════════════════════════════════════════════════

    /** 由 Spring 装配时写入（{@code RetrievalService} 构造期调用；测试可直接调用）。 */
    public static void activate(AiSettings settings) {
        AiSettings.Pipeline pipeline = settings.getPipeline();
        synchronized (LOCK) {
            String configured = pipeline.getChunkKeyFormat();
            format = FORMAT_REV.equalsIgnoreCase(configured == null ? "" : configured.strip())
                    ? FORMAT_REV : FORMAT_LEGACY;
            active = Revisions.of(settings);
        }
    }

    /** 当前切片键形态（{@code legacy} 或 {@code rev}）。 */
    public static String format() {
        return format;
    }

    /** 是否启用"版本段进切片键"（L1 防护）。 */
    public static boolean revKeyEnabled() {
        return FORMAT_REV.equals(format);
    }

    /** 当前版本三元组。 */
    public static Revisions current() {
        return active;
    }

    /** 当前 {@code pipeline_rev} 字符串（进任务幂等键、进上报体）。 */
    public static String pipelineRev() {
        return active.pipelineRev();
    }

    /** 任务级幂等键：{@code sha1(内容 sha1 + pipeline_rev)}（设计 §3.3.3）。 */
    public static String taskKey(String contentSha1) {
        return sha1Hex((contentSha1 == null ? "" : contentSha1) + "|" + pipelineRev());
    }

    // ══════════════════════════════════════════════════════════════
    // 切片键：生成与解析
    // ══════════════════════════════════════════════════════════════

    /**
     * 生成切片键（{@code RetrievalService.chunkKey} 委托到它）。
     *
     * <p><b>两种形态的取舍在 {@code ai.pipeline.chunk-key-format}</b>：默认 {@code legacy}
     * 保证升级不使既有索引/缓存失效；切到 {@code rev} 后需要跑一次重建把键统一。
     */
    public static String chunkKey(String docId, int pageNo, String textSha1Prefix) {
        String base = docId + ":" + pageNo + ":" + textSha1Prefix;
        if (!revKeyEnabled()) {
            return base;
        }
        Revisions rev = active;
        return "d=" + docId + ":p=" + pageNo + ":h=" + textSha1Prefix
                + ":c=" + rev.cleanRev() + ":k=" + rev.chunkRev();
    }

    /** 解析结果（{@code cleanRev}/{@code chunkRev} 在旧键里为 {@code -1} = "未知"）。 */
    public record ParsedKey(String docId, int pageNo, String hash, int cleanRev, int chunkRev, boolean legacy) {
    }

    /**
     * 解析切片键（<b>两种形态都认</b>，见设计 §3.3.3 L1）。无法识别时返回 {@code null}
     * ——调用方（如引用回填）应把它当"不是我们生成的键"，而不是抛错。
     */
    public static ParsedKey parseChunkKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Matcher rev = REV_KEY.matcher(key.strip());
        if (rev.matches()) {
            return new ParsedKey(rev.group("doc"), Integer.parseInt(rev.group("page")), rev.group("hash"),
                    Integer.parseInt(rev.group("clean")), Integer.parseInt(rev.group("chunk")), false);
        }
        Matcher legacy = LEGACY_KEY.matcher(key.strip());
        if (legacy.matches()) {
            return new ParsedKey(legacy.group("doc"), Integer.parseInt(legacy.group("page")),
                    legacy.group("hash"), -1, -1, true);
        }
        return null;
    }

    /** 回归测试与"灰度回退"用：把口径复位成默认（legacy + 全 0 版本）。 */
    public static void reset() {
        synchronized (LOCK) {
            format = FORMAT_LEGACY;
            active = new Revisions(0, 0, 0);
        }
    }

    /** 给 {@code RetrievalService} 用的同一份 SHA-1（避免两处实现漂移）。 */
    public static String sha1Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-1（不可能发生）", e);
        }
    }

    /** 供 {@code RetrievalService.chunkKey} 直接调用（保持老签名不变，实现委托到这里）。 */
    public static String chunkKeyOf(DocStore.Chunk chunk) {
        String text = chunk.text() == null ? "" : chunk.text();
        return chunkKey(chunk.docId(), chunk.pageNo(), sha1Hex(text).substring(0, 12));
    }
}
