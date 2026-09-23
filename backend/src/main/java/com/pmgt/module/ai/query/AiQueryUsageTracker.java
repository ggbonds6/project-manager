package com.pmgt.module.ai.query;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本次问答「用了几次系统查询、查了哪些 entity」的计数（§11.6）。
 *
 * <h2>为什么由主系统自己数，而不是读 AI 的 toolTrace</h2>
 * <ol>
 *   <li><b>审计不能依赖被审计方自报</b>：toolTrace 是 AI 服务产出的展示数据，
 *       它少报一次调用，主系统就再也发现不了；</li>
 *   <li>toolTrace 的字段/文案随时可能变（它是给前端看的），而留痕是硬要求；</li>
 *   <li>主系统本来就签发了 {@code scope_token}、也受理每次回调，
 *       以 token 的 {@code jti} 为键计数，天然权威且零额外成本。</li>
 * </ol>
 *
 * <h2>时序</h2>
 * <pre>
 * AiChatService.ask()
 *   ├─ issue()  ── 登记 jti（计数 0）
 *   ├─ ai.chat(...) ── AI 回调 /api/ai/query/* ── 每次 record(jti, entity)
 *   └─ usage(jti) ── 读计数写进 ai_ask_log ── clear(jti)
 * </pre>
 * 回调必然发生在 {@code /chat} 返回之前，所以读完即可清理，不会长期占用内存。
 *
 * <h2>已知边界（部署相关，写清楚以免被当成 bug）</h2>
 * <p>计数保存在<b>本 JVM 内</b>。双机 + 负载均衡时，若签发 {@code scope_token} 的节点
 * 与受理回调的节点不是同一台，本地计数会是 0。为此 {@code AiChatService} 在本地计数为 0
 * 时回落到 toolTrace 里 {@code query_business_data} 的条数（只有次数、没有 entity）——
 * 宁可少记 entity，也不要漏报"这次问答用过系统数据"。
 */
@Component
public class AiQueryUsageTracker {

    /** 未完成问答的登记项保留时长：超过它说明这轮问答异常结束了，不能让 Map 无限增长。 */
    private static final long STALE_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Usage> byJti = new ConcurrentHashMap<>();

    /** 登记一枚刚签发的作用域令牌（计数从 0 开始）。 */
    public void register(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        evictStale();
        byJti.put(jti, new Usage());
    }

    /**
     * 记一次受控查询。
     *
     * <p>jti 未登记（例如主系统在问答中途重启）时也照记：宁可多一条孤立计数，
     * 也不要因为"没有登记"把一次真实的越权/滥用痕迹丢掉。
     */
    public void record(String jti, String entity) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        Usage usage = byJti.computeIfAbsent(jti, k -> new Usage());
        usage.count.incrementAndGet();
        if (entity != null && !entity.isBlank()) {
            synchronized (usage.entities) {
                usage.entities.add(entity);
            }
        }
    }

    /**
     * 读一次问答的用量快照。
     *
     * @return 次数 + entity 清单（都为空表示这次问答没有用过系统数据）
     */
    public Snapshot snapshot(String jti) {
        if (jti == null || jti.isBlank()) {
            return Snapshot.EMPTY;
        }
        Usage usage = byJti.get(jti);
        if (usage == null) {
            return Snapshot.EMPTY;
        }
        List<String> entities;
        synchronized (usage.entities) {
            entities = List.copyOf(usage.entities);
        }
        return new Snapshot(usage.count.get(), entities);
    }

    /** 问答结束（含失败）后清理，避免长期驻留。 */
    public void clear(String jti) {
        if (jti != null && !jti.isBlank()) {
            byJti.remove(jti);
        }
    }

    /** 当前在册条目数（供测试与运维排查确认"用完即清"确实生效）。 */
    public int size() {
        return byJti.size();
    }

    private void evictStale() {
        long now = System.currentTimeMillis();
        byJti.entrySet().removeIf(e -> now - e.getValue().createdAt > STALE_MILLIS);
    }

    /**
     * 用量快照。
     *
     * @param count    调用次数
     * @param entities 去重后的 entity 清单（保持首次出现的顺序，便于留痕可读）
     */
    public record Snapshot(int count, List<String> entities) {

        public static final Snapshot EMPTY = new Snapshot(0, List.of());

        /** {@code ai_ask_log.biz_entities} 的取值：未查过为 null（不是空串）。 */
        public String entitiesText() {
            return entities == null || entities.isEmpty() ? null : String.join(",", entities);
        }
    }

    private static final class Usage {
        private final AtomicInteger count = new AtomicInteger();
        private final Set<String> entities = new LinkedHashSet<>();
        private final long createdAt = System.currentTimeMillis();
    }
}
