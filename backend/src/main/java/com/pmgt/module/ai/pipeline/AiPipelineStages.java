package com.pmgt.module.ai.pipeline;

import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段目录：{@code stage_key} ↔ 顺序 / 中文名 / 产物类型的<b>唯一映射点</b>。
 *
 * <p>为什么要有这个类：阶段键在四个地方被用到（路由路径、上报报文、DB 列、前端阶段条），
 * 中文名与顺序如果各写一份，就会出现"接口里叫『向量化』、前端写死『向量』"这类不一致。
 * 这里一处定义，接口把它整份给前端。
 *
 * <p><b>关于 RETRIEVE/GENERATE</b>：冻结契约的 stage_key 枚举里有 7 个，而 V15 的列注释写的是
 * "PARSE→CLEAN→CHUNK→EMBED→INDEX（RETRIEVE/GENERATE 是在线阶段，不进本表）"。
 * 本批的处理是：<b>7 个都接受并落库</b>（契约优先，AI 侧不该因为上报了在线阶段而 400），
 * 但顺序把它们排在 6/7，前端"解析任务流"阶段条只画前 5 个（{@code PARSE..INDEX}）——
 * 在线阶段的观测口径是问答链路，不是"这份文档解析到哪了"。
 */
public final class AiPipelineStages {

    /** 解析入库侧五阶段（前端阶段条画这五个）。 */
    public static final List<String> INGEST_STAGE_KEYS =
            List.of("PARSE", "CLEAN", "CHUNK", "EMBED", "INDEX");

    /** 契约允许的全部 7 个阶段键（顺序 = 阶段条顺序）。 */
    public static final List<String> ALL_STAGE_KEYS = List.of(
            "PARSE", "CLEAN", "CHUNK", "EMBED", "INDEX", "RETRIEVE", "GENERATE");

    /** 产物类型 → 产出它的阶段（V15 的五类产物与五阶段一一对应）。 */
    private static final Map<String, String> ARTIFACT_STAGE = Map.of(
            AiPipelineArtifact.PAGE_DIGEST, "PARSE",
            AiPipelineArtifact.CLEAN_DIFF, "CLEAN",
            AiPipelineArtifact.CHUNK_MANIFEST, "CHUNK",
            AiPipelineArtifact.EMBED_BATCH, "EMBED",
            AiPipelineArtifact.INDEX_RESULT, "INDEX");

    /** 合法的产物类型（顺序 = 生成顺序）。 */
    public static final List<String> ARTIFACT_TYPES = List.of(
            AiPipelineArtifact.PAGE_DIGEST, AiPipelineArtifact.CLEAN_DIFF,
            AiPipelineArtifact.CHUNK_MANIFEST, AiPipelineArtifact.EMBED_BATCH,
            AiPipelineArtifact.INDEX_RESULT);

    private static final Map<String, Stage> BY_KEY = byKey();

    private static Map<String, Stage> byKey() {
        Map<String, Stage> m = new LinkedHashMap<>();
        m.put("PARSE", new Stage("PARSE", "识别", 1));
        m.put("CLEAN", new Stage("CLEAN", "清洗", 2));
        m.put("CHUNK", new Stage("CHUNK", "切分", 3));
        m.put("EMBED", new Stage("EMBED", "向量化", 4));
        m.put("INDEX", new Stage("INDEX", "入库", 5));
        m.put("RETRIEVE", new Stage("RETRIEVE", "召回", 6));
        m.put("GENERATE", new Stage("GENERATE", "生成", 7));
        return Map.copyOf(m);
    }

    private AiPipelineStages() {
    }

    /** 阶段定义（key / 中文名 / 顺序）。 */
    public record Stage(String key, String name, int seq) {
    }

    /** 归一化阶段键（大写去空白）；不合法的返回 null，由调用方翻成 400。 */
    public static String normalize(String stageKey) {
        if (stageKey == null) {
            return null;
        }
        String s = stageKey.trim().toUpperCase();
        return BY_KEY.containsKey(s) ? s : null;
    }

    /** 取阶段定义；未知键返回 null。 */
    public static Stage of(String stageKey) {
        String key = normalize(stageKey);
        return key == null ? null : BY_KEY.get(key);
    }

    /** 阶段顺序（未知键返回 0，排最后而不是抛异常——DB 里的脏值不该让详情接口 500）。 */
    public static int seqOf(String stageKey) {
        Stage stage = of(stageKey);
        return stage == null ? 0 : stage.seq();
    }

    /** 阶段中文名（未知键原样回显，让界面至少能看到那个值）。 */
    public static String nameOf(String stageKey) {
        Stage stage = of(stageKey);
        return stage == null ? stageKey : stage.name();
    }

    /** 产物类型 → 产出阶段；未知类型返回 null。 */
    public static String stageOfArtifact(String artifactType) {
        if (artifactType == null) {
            return null;
        }
        return ARTIFACT_STAGE.get(artifactType.trim().toLowerCase());
    }

    /** 校验产物类型（非法 → 返回 null，调用方给 400 文案）。 */
    public static String normalizeArtifactType(String artifactType) {
        if (artifactType == null) {
            return null;
        }
        String s = artifactType.trim().toLowerCase();
        return ARTIFACT_TYPES.contains(s) ? s : null;
    }
}
