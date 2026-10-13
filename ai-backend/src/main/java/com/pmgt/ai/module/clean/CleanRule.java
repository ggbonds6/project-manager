package com.pmgt.ai.module.clean;

/**
 * 一条清洗规则（设计 §3.3.2 的 C1~C8，一条规则一个实现）。
 *
 * <p>为什么是"规则对象"而不是一堆 if：规则必须<b>独立可开关</b>（{@code ai.clean.rules}）、
 * 命中情况必须<b>可分条统计</b>（{@code rules_fired{rule}} 是"这条规则有没有用"的唯一依据）、
 * 而且每条规则要能<b>单独测正反例</b>（一条规则写错的代价是把正文删了，而检索只会"少召回"、
 * 不报错——所以逐条钉死是必要的）。
 *
 * <p>契约：
 * <ul>
 *   <li><b>纯函数</b>：{@link #apply(CleanWork)} 只读写传进来的 {@link CleanWork}，
 *       不碰文件、网络、时钟、静态状态 ⇒ 同输入 + 同 {@code clean_rev} 必得同输出；</li>
 *   <li><b>必须留痕</b>：删了什么、归一了什么，都要经 {@link CleanWork#remove} /
 *       {@link CleanWork#normalize} 记账（否则无法做设计 §4.6 的清洗前后对照）；</li>
 *   <li><b>不改表内文本</b>：表格块内的行不参与断行合并与空白压缩（设计 §3.3.2 C3/C4 的风险对策）。</li>
 * </ul>
 */
public interface CleanRule {

    /** 规则 id（小写，进配置与 counters 的键）：{@code c1}~{@code c8}。 */
    String id();

    /** 人读标签（写进产物与日志，便于人工复核"这条删的是什么"）。 */
    String label();

    /** 配置清单里不写这条规则时，它是否是默认开启的。 */
    boolean defaultEnabled();

    /** 应用规则（就地修改 {@code work} 里的页行状态，并通过 remove/normalize 记账）。 */
    void apply(CleanWork work);
}
