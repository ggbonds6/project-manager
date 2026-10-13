package com.pmgt.module.ai.pipeline;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 流水线的<b>确定性键</b>：run_id / task_key / sha1 工具。
 *
 * <h2>run_id = {@code run-<taskId>}（本批的契约口径，必须两侧一致）</h2>
 * <p>V15 的列注释写的是"与 {@code attachment_ai_task.ai_task_id} 同源"，本批<b>刻意不</b>复用
 * AI 侧 id，理由是：
 * <ol>
 *   <li><b>领取时还没有 ai_task_id</b>：抢占式领取是 AI 侧主动来要任务，此刻它的
 *       upload-task 还没创建，主系统拿不到那个 id，而 run_id 必须在 claim 响应里就给出；</li>
 *   <li><b>一次解析尝试 = 一行任务</b>：任务行的主键就是这个 run 的身份，确定性派生
 *       ⇒ 任何一方都不用额外查一次库就能校验/拼装（{@code GET /runs/run-12} 一眼可读）；</li>
 *   <li><b>主系统是唯一事实源</b>：run_id 不该由 AI 侧的 id 生成规则决定。</li>
 * </ol>
 * 代价写在这里：{@code run_id} 与 {@code ai_task_id} <b>不是同一个字符串</b>，
 * 排障时不要拿一个去搜另一个的日志；两者都能定位到同一行任务。
 *
 * <h2>task_key = {@code sha1(content_hash + pipeline_rev)}</h2>
 * <p>按设计文档的字面口径<b>直接拼接</b>（不加分隔符），两侧若要复算必须照同一写法。
 * 它<b>不落库</b>：{@code task_key} 完全由 {@code content_hash} 与 {@code pipeline_rev} 决定，
 * 再存一列只会多一个可能与之漂移的副本（新增列要走 V16，本批不需要）。
 * claim 响应与详情接口都按需实时派生，取值必然一致。
 */
public final class AiPipelineKeys {

    /** 运行 id 前缀（唯一约定点：谁来拼 run_id 都走这里）。 */
    public static final String RUN_PREFIX = "run-";

    private AiPipelineKeys() {
    }

    /** task_id → run_id（taskId 为 null 时返回 null，绝不产出 "run-null"）。 */
    public static String runIdOf(Long taskId) {
        return taskId == null ? null : RUN_PREFIX + taskId;
    }

    /**
     * run_id → task_id；形状不合法（不是 {@code run-<正整数>}）返回 null。
     *
     * <p>返回 null 而不是抛异常：调用方要把它翻成 400 并说明"run_id 形如 run-12"，
     * 在那里才能给出对 AI 侧有用的报错原文。
     */
    public static Long taskIdOf(String runId) {
        if (runId == null) {
            return null;
        }
        String s = runId.trim();
        if (!s.startsWith(RUN_PREFIX)) {
            return null;
        }
        String digits = s.substring(RUN_PREFIX.length());
        if (digits.isEmpty() || digits.length() > 18) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 幂等键：{@code sha1(content_hash + pipeline_rev)}，十六进制小写。
     *
     * <p>任一参数为空时按空串参与拼接（不抛异常）：存量任务的 {@code content_hash} 为空，
     * 详情接口仍要能显示"这一行的 task_key 是什么"，而不是 500。
     */
    public static String taskKey(String contentHash, String pipelineRev) {
        String material = (contentHash == null ? "" : contentHash) + (pipelineRev == null ? "" : pipelineRev);
        return sha1Hex(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 字节数组的 sha1（十六进制小写）。 */
    public static String sha1Hex(byte[] bytes) {
        return toHex(sha1().digest(bytes == null ? new byte[0] : bytes));
    }

    /**
     * 取 {@link #sha1Stream} 包出来的流<b>已经算好</b>的 sha1（十六进制小写）。
     *
     * <p>⚠️ 这里是本轮单测抓到的一个真缺陷的修复点：{@code MessageDigest.digest()} 返回的是
     * <b>摘要本身（20 字节）</b>，如果再把它交给 {@link #sha1Hex(byte[])}，就会算成
     * <b>sha1(sha1(内容))</b>——指纹依然是 40 位十六进制、看起来完全正常，
     * 但它和任何按"内容 sha1"算出来的值都对不上（幂等键因此永远不命中）。
     * 所以"取流指纹"这件事必须只有这一个入口，别自己拼。
     */
    public static String digestHex(DigestInputStream in) {
        return toHex(in.getMessageDigest().digest());
    }

    /**
     * 边读边算 sha1 的包装流：用来在"把附件流推给 AI 服务"的<b>同一次读取</b>里拿到
     * {@code content_hash}。
     *
     * <p>为什么不先读完再提交：附件上限 500MB，主系统侧不该为了算指纹把整份文件读进内存，
     * 也不该为了算指纹把同一份文件从 OBS 拉两遍。用完 {@link #digestHex} 取结果
     * （注意必须在流读完之后取，否则是半截指纹）。
     */
    public static DigestInputStream sha1Stream(InputStream in) {
        return new DigestInputStream(in, sha1());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然提供 SHA-1：走到这里说明运行环境坏了，直接暴露而不是静默返回空指纹
            throw new IllegalStateException("运行环境缺少 SHA-1 实现", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
