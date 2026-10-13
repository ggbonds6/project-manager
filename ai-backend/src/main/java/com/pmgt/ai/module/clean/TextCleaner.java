package com.pmgt.ai.module.clean;

/**
 * 清洗阶段（设计 §3.3.1 的接口）。
 *
 * <p><b>纯函数</b>：同输入 + 同 {@code clean_rev} ⇒ 同输出。这条不变量是全阶段的信用的基础——
 * 有了它才能"缓存清洗结果、对比两次清洗、按页重算、把清洗当成可续跑阶段"。
 *
 * <p>两层接口（设计明确要求）：
 * <ol>
 *   <li>{@link #cleanDocument(CleanContext)}：<b>整份文档</b>入口，先做跨页检测（页眉/页脚、水印、段级去重），
 *       再逐页应用逐行规则；</li>
 *   <li>{@link #enabled()} / {@link #options()}：调用方据此决定 {@code CLEAN} 阶段是
 *       {@code DONE/PARTIAL} 还是 {@code SKIPPED}——<b>清洗开关不在这里判死</b>，
 *       "关"也要如实上报（SKIPPED + 原因 + counters 不变）。</li>
 * </ol>
 */
public interface TextCleaner {

    /** 清洗整份文档（关掉时返回 {@link CleanResult#enabled()} 为 false 的"逐字不变"结果）。 */
    CleanResult cleanDocument(CleanContext context);

    /** 清洗是否启用（{@code ai.clean.enabled}）。 */
    boolean enabled();

    /** 本次生效的配置快照（规则清单、阈值、是否删重复段）。 */
    CleanOptions options();
}
