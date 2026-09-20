package com.pmgt.ai.module.retrieval;

/**
 * 重排结果里的一行。
 *
 * <p>⚠️ {@code index} 是<b>入参 documents 里的下标</b>，不是排名——上游按原顺序返回
 * {@code results}，调用方必须用它回查自己的候选列表（见 {@link VecClient#rerank}）。
 *
 * @param index          入参下标（回查候选用它）
 * @param relevanceScore 相关性分数，越大越相关
 * @param rank           按分数降序后的名次（0 起），仅用于展示与排错
 */
public record RerankHit(int index, double relevanceScore, int rank) {
}
