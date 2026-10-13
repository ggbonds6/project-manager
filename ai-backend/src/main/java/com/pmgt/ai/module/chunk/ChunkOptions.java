package com.pmgt.ai.module.chunk;

import com.pmgt.ai.common.config.AiSettings;

/**
 * 切分配置的不可变快照（{@code ai.chunk.*}）。
 *
 * <p>与清洗同理：切分是纯函数，配置必须抄成快照再进算法（详见 {@code CleanOptions} 的注释）。
 * 任何一项变化都要把 {@code ai.pipeline.chunk-rev} +1，否则"参数改了但切片键没变 ⇒ 旧向量配新切片"。
 */
public record ChunkOptions(boolean improved,
                           int chunkChars,
                           int maxCharLen,
                           double overlapRatio,
                           boolean structureAware,
                           boolean tablesWhole,
                           int parentSummaryChars) {

    /** 重叠窗口的硬上限（设计 §3.4.3：最多回退 {@code 0.15 × chunk-chars}）。 */
    public static final double MAX_OVERLAP_RATIO = 0.15;

    public static ChunkOptions of(AiSettings settings) {
        AiSettings.Chunk chunk = settings == null ? new AiSettings.Chunk() : settings.getChunk();
        int chunkChars = chunk.getChunkChars() > 0 ? chunk.getChunkChars() : 500;
        int maxCharLen = chunk.getMaxCharLen() > 0 ? chunk.getMaxCharLen() : 2000;
        return new ChunkOptions(chunk.isImproved(), chunkChars, Math.max(maxCharLen, chunkChars),
                clampRatio(chunk.getOverlapRatio()), chunk.isStructureAware(), chunk.isTablesWhole(),
                Math.max(0, chunk.getParentSummaryChars()));
    }

    /** 回退口径（接入前的切法）：只按段落累积到 {@code chunk-chars}。 */
    public static ChunkOptions legacy(int chunkChars) {
        return new ChunkOptions(false, chunkChars > 0 ? chunkChars : 500,
                chunkChars > 0 ? chunkChars : 500, 0.0, false, false, 0);
    }

    private static double clampRatio(double ratio) {
        if (ratio <= 0) {
            return 0.0;
        }
        return Math.min(MAX_OVERLAP_RATIO, ratio);
    }

    /** 重叠窗口的字符数（按句回退的上限）。 */
    public int overlapChars() {
        return (int) Math.round(chunkChars * overlapRatio);
    }
}
