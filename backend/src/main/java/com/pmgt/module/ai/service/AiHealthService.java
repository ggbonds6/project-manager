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

    private final AiProperties props;
    private final AiServiceClient ai;
    private final AttachmentAiTaskMapper taskMapper;

    public AiHealthService(AiProperties props, AiServiceClient ai, AttachmentAiTaskMapper taskMapper) {
        this.props = props;
        this.ai = ai;
        this.taskMapper = taskMapper;
    }

    public AiHealthVO health() {
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
            // 这是服务自检，不是质检；需要时用 with_llm/with_vec 手工探。
            body = ai.health(true, false, false);
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
        // 只有 OCR 是刚探过的；chat/embedding/reranker 本次没探 → null（"未知"），
        // 不能因为"没探"就写 false，那会让人以为模型坏了
        models.setOcr(ocrOk);
        models.setChat(null);
        models.setEmbedding(null);
        models.setReranker(null);
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

    /** 主系统侧未结束的解析任务数——不依赖 AI 服务，永远有值。 */
    private Long pendingTaskCount() {
        return taskMapper.selectCount(new LambdaQueryWrapper<AttachmentAiTask>()
                .in(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING));
    }
}
