package com.pmgt.ai.module.retrieval;

import java.util.List;

/**
 * 向量能力的抽象接缝：{@link VecClient} 是真实 HTTP 实现，测试注入假实现——
 * 检索链路的单测必须<b>完全离线</b>（真实模型在政务内网，开发机连不上），
 * 所以测试要钉的是"接线与顺序"，不是模型质量。
 */
public interface VecApi {

    /**
     * 批量向量化文本，返回顺序与入参一致。
     *
     * @param instruction 自定义指令（{@code messages[0].role=system}）；{@code null}/空表示走
     *                    {@code input} 形式用模型默认指令
     */
    List<float[]> embedTexts(List<String> texts, String instruction);

    /** 重排候选：返回 {@code (入参下标, 分数)}，分数降序。 */
    List<RerankHit> rerank(String query, List<String> documents, Integer topK);

    /** 探活（{@code GET /models}，不消耗配额）。 */
    VecHealth health(double timeoutSeconds);

    /** 余弦相似度；长度不同或为空返回 0。 */
    double cosine(float[] a, float[] b);
}
