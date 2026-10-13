package com.pmgt.ai.common.gate;

import com.pmgt.ai.common.config.AiSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 准入结果（设计 §5.4 的"诚实失败"载体）。
 *
 * <p>为什么不是"返回 boolean + 抛异常"：诚实失败要求拒绝时能一次性说清
 * <b>当前深度 / 上限 / 原因 / 建议</b>，而异常只够带一句话；同时"准入成功"要能拿到一个
 * <b>必须归还</b>的 permit（{@link #close()} 幂等），否则任何一条 return 路径漏了 release
 * 都会变成 permit 泄漏（V5 的验收口径就是"压测后 inflight 全部回 0"）。
 */
public sealed interface GateResult extends AutoCloseable {

    /** 是否准入（成功才持有 permit）。 */
    boolean admitted();

    /** 打给调用方看的完整说明（含深度/上限/原因/建议），失败时用它当 HTTP 错误文案。 */
    String describe();

    /** 归还 permit：<b>幂等</b>（重复调用只生效一次），失败结果调用它是空操作。 */
    @Override
    void close();

    /** 准入成功。 */
    record Admitted(GateChannel channel, String projectId, int priority, long waitedMs,
                    AutoCloseable permit) implements GateResult {

        @Override
        public boolean admitted() {
            return true;
        }

        @Override
        public String describe() {
            return "已准入：通道 " + channel.key() + "，项目 " + projectId
                    + "，有效优先级 " + priority + "，等待 " + waitedMs + "ms";
        }

        @Override
        public void close() {
            if (permit != null) {
                try {
                    permit.close();
                } catch (Exception e) {
                    throw new IllegalStateException("归还 L1 permit 失败", e);
                }
            }
        }
    }

    /**
     * 拒绝（诚实失败）：{@code reason} 是机器可读的原因（{@code full|timeout|paused}，与指标同源），
     * 文案里必须带深度与上限。
     */
    record Rejected(GateChannel channel, String reason, String message, int depth, int limit,
                    int queueLimit, long waitedMs) implements GateResult {

        @Override
        public boolean admitted() {
            return false;
        }

        @Override
        public String describe() {
            return message;
        }

        @Override
        public void close() {
            // 未准入 ⇒ 没有 permit 可还；显式空实现（调用方可以无条件 close）
        }

        /** 供自检/日志的结构化视图（不含任何凭据）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("queue", channel.key());
            out.put("reason", reason);
            out.put("depth", depth);
            out.put("limit", limit);
            out.put("queue_limit", queueLimit);
            out.put("waited_ms", waitedMs);
            out.put("message", message);
            return out;
        }
    }

    /** 把所有通道的拒绝原因与建议集中在这里，避免每个调用方各写一套文案。 */
    final class Messages {

        private Messages() {
        }

        static String busy(GateChannel channel, String reason, int depth, int limit, int queueLimit,
                           long waitedMs, int inFlight, List<String> suggestions) {
            StringBuilder sb = new StringBuilder();
            switch (reason) {
                case "full" -> sb.append(channel.label()).append("队列已满");
                case "timeout" -> sb.append(channel.label()).append("等待超时");
                case "paused" -> sb.append(channel.label()).append("已暂停派发");
                default -> sb.append(channel.label()).append("暂不可用");
            }
            sb.append("（当前排队 ").append(depth).append('/').append(queueLimit < 0 ? "不限" : queueLimit)
                    .append("，在飞 ").append(inFlight).append('/').append(limit)
                    .append("，等待 ").append(waitedMs).append("ms）");
            if (suggestions != null && !suggestions.isEmpty()) {
                sb.append("；").append(String.join("；", suggestions));
            }
            return sb.toString();
        }
    }
}
