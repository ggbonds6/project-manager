package com.pmgt.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.ai.client.AiJson;
import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.client.AiUnavailableException;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiHealthVO;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 服务自检（§9 #1）。
 *
 * <p>关键约定：<b>这个接口永远返回 code=0</b>，包括 AI 服务连不上的时候——
 * 它要回答的问题正是「能不能用」，如果连不上就抛异常，前端只能拿到一个通用错误，
 * 反而看不出「是 AI 服务没起来，还是主系统开关关了」。
 * 所以不可用时通过 {@code available=false + message} 表达。
 *
 * <p>不做健康结果的缓存：自检是低频动作（用户点一下、运维查一次），
 * 缓存只会让"刚重启完还是显示不可用"变成新的困惑来源。
 */
@Service
public class AiHealthService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 网关模型 id 里与「向量化」同类的关键字（AI 服务没给 embed_model 时的兜底匹配）。 */
    private static final List<String> EMBED_HINTS = List.of("embed", "bge-m3");
    /** 网关模型 id 里与「重排」同类的关键字。 */
    private static final List<String> RERANK_HINTS = List.of("rerank");

    private final AiProperties props;
    private final AiServiceClient ai;
    private final AttachmentAiTaskMapper taskMapper;

    public AiHealthService(AiProperties props, AiServiceClient ai, AttachmentAiTaskMapper taskMapper) {
        this.props = props;
        this.ai = ai;
        this.taskMapper = taskMapper;
    }

    /** 快速自检（默认）：只探 OCR，等价于「平台网关是否可达」。 */
    public AiHealthVO health() {
        return health(false);
    }

    /**
     * 服务自检。
     *
     * @param deep {@code false}（默认）= 只探 OCR 的快速探活，页面一打开就能出结论；
     *             {@code true} = 额外真发请求探活<b>对话模型</b>（{@code with_llm}）与
     *             <b>平台向量能力</b>（{@code with_vec}，向量化与重排共用），
     *             代价是几秒到十几秒，所以只由用户点「深度自检」时触发，不做默认。
     *             两种模式的响应字段完全一致，只有三个模型的字段从 null（未探测）变成 true/false。
     */
    public AiHealthVO health(boolean deep) {
        AiHealthVO vo = new AiHealthVO();
        vo.setAiServiceBaseUrl(props.getBaseUrl());
        vo.setCheckedAt(TS.format(LocalDateTime.now()));
        vo.setPendingTaskCount(pendingTaskCount());

        if (!props.isEnabled()) {
            vo.setAvailable(false);
            vo.setMessage(props.disabledReason() + "；如需启用请设置 pm.ai.enabled=true");
            return vo;
        }

        Map<String, Object> body;
        try {
            // 只探 OCR（等价于"平台网关是否可达"）：llm/vec 探活要真发请求，慢且没必要——
            // 这是默认的快速自检，不是质检；要真探这三个模型就用 deep=true（前端「深度自检」按钮）。
            body = ai.health(true, deep, deep);
        } catch (AiUnavailableException e) {
            vo.setAvailable(false);
            vo.setMessage("无法连接 AI 能力服务（" + props.getBaseUrl() + "）：" + e.getReason()
                    + "；请确认服务已启动且网络可达");
            return vo;
        }

        Map<String, Object> ocr = AiJson.asMap(body.get("ocr"));
        boolean ocrOk = AiJson.bool(ocr, "ok", false);
        vo.setPlatformReachable(ocrOk);

        Map<String, Object> config = AiJson.asMap(body.get("config"));
        vo.setVectorBackend(AiJson.nullableText(config, "vec_backend"));

        AiHealthVO.Models models = new AiHealthVO.Models();
        models.setOcr(ocrOk);
        if (deep) {
            models.setChat(deepBool(body, "llm"));
            Map<String, Object> vec = AiJson.asMap(body.get("vec"));
            Boolean vecOk = deepBool(body, "vec");
            models.setEmbedding(vecModelOk(vec, AiJson.nullableText(vec, "embed_model"), EMBED_HINTS, vecOk));
            models.setReranker(vecModelOk(vec, AiJson.nullableText(vec, "rerank_model"), RERANK_HINTS, vecOk));
        } else {
            // 本次没探的模型给 null（前端显示"未探测"），不能因为"没探"就写 false，
            // 那会让人以为模型坏了
            models.setChat(null);
            models.setEmbedding(null);
            models.setReranker(null);
        }
        vo.setModels(models);

        try {
            vo.setDocumentCount(ai.listDocuments().size());
        } catch (RuntimeException e) {
            vo.setDocumentCount(null);
        }

        // 可用 = 主系统开关打开 + AI 服务可达 + 平台探针通过。
        // 平台不可达时不算"可用"：解析与问答都要走平台网关，硬说可用会误导运维。
        vo.setAvailable(ocrOk);
        vo.setMessage(ocrOk
                ? "AI 能力服务正常"
                : "AI 服务可达，但平台网关探针未通过（OCR 不可用）：" + AiJson.text(ocr, "detail"));
        return vo;
    }

    /**
     * 读深探块（{@code llm} / {@code vec}）的 {@code ok}。
     *
     * <p>只在该块<b>真的返回了</b>时才给结论；块缺失（AI 服务版本较老、不认识
     * {@code with_llm}/{@code with_vec}）时给 {@code null}（未探测）——
     * 与 OCR 之外的既有口径一致：没探过就说"未探测"，绝不写成 false 让人以为模型坏了。
     */
    private static Boolean deepBool(Map<String, Object> body, String key) {
        return AiJson.has(body, key) ? AiJson.bool(AiJson.asMap(body.get(key)), "ok", false) : null;
    }

    /**
     * 向量化 / 重排模型是否可用。
     *
     * <p><b>取舍说明</b>：AI 服务的 {@code /health?with_vec=true} 只给一个 {@code vec} 段——
     * 它探的是平台网关的 {@code GET /models}（一次调用，向量化与重排共用同一向量能力与同一网关），
     * 所以"重排模型"没有独立的探针。但该段同时带上了 {@code models}（网关实际提供的模型 id 列表）
     * 与 {@code embed_model} / {@code rerank_model}（AI 服务配置里要用的两个模型名），
     * 于是可以按名字分别判定：
     * <ol>
     *   <li>网关模型列表里<b>找得到</b>配置的那个模型名 → true；列表非空但<b>找不到</b> → false。
     *       这正是"网关可达，但模型不在列表里"的情形——AI 服务此时 {@code vec.ok} 仍是 true
     *       （只把原因写在 detail 里），只看 {@code ok} 会把"重排模型没上线"报成"可用"；</li>
     *   <li>列表里没有配置的那个名字时，再按类别关键字兜底匹配（embedding：embed / bge-m3；
     *       reranker：rerank），适配 AI 服务将来只回模型名列表的情况；</li>
     *   <li>既没有模型列表、也没有可区分的模型名 → <b>回落到 {@code vec.ok}</b>
     *       （即"向量化与重排共用平台向量能力"的既有口径）。</li>
     * </ol>
     */
    private static Boolean vecModelOk(Map<String, Object> vec, String configuredModel,
                                      List<String> hints, Boolean vecOk) {
        List<String> names = modelNames(vec.get("models"));
        if (!names.isEmpty()) {
            boolean configured = configuredModel != null && !configuredModel.isBlank();
            if (configured && names.contains(configuredModel)) {
                return Boolean.TRUE;
            }
            for (String name : names) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (String hint : hints) {
                    if (lower.contains(hint)) {
                        return Boolean.TRUE;
                    }
                }
            }
            // 列表非空且认不出该类模型：只有当 AI 服务告诉了我们它要用的模型名时，
            // 才能断定"这个模型不在列表里"；名字都没有时无法区分，交给下面的回落
            if (configured) {
                return Boolean.FALSE;
            }
        }
        return vecOk;
    }

    /** 取网关模型列表里的模型名：字符串数组（真实契约：{@code ["id", ...]}）与对象数组都兼容。 */
    private static List<String> modelNames(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            String name;
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> m = AiJson.asMap(map);
                name = AiJson.text(m, "id");
                if (name.isBlank()) {
                    name = AiJson.text(m, "name");
                }
                if (name.isBlank()) {
                    name = AiJson.text(m, "model");
                }
            } else {
                name = item == null ? "" : String.valueOf(item);
            }
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 主系统侧未结束的解析任务数——不依赖 AI 服务，永远有值。 */
    private Long pendingTaskCount() {
        return taskMapper.selectCount(new LambdaQueryWrapper<AttachmentAiTask>()
                .in(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING));
    }
}
