package com.pmgt.module.ai.service;

import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.AuthContext;
import com.pmgt.module.ai.client.AiJson;
import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.client.AiUnavailableException;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiChatRequest;
import com.pmgt.module.ai.dto.AiChatResponse;
import com.pmgt.module.ai.entity.AiAskLog;
import com.pmgt.module.ai.mapper.AiAskLogMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 问答（§9 #9）：<b>权限解析 → 代理 → 适配 → 留痕</b>。
 *
 * <p>这是整个 P0 的主链路，四步各自都有"不能省"的理由：
 * <ol>
 *   <li><b>作用域解析</b>：用户传的是「附件/项目」，主系统换算成 AI 服务的 {@code doc_ids}；
 *       用户传了不属于自己范围的 id 时<b>直接拒绝</b>（{@link AiScopeResolver}）；</li>
 *   <li><b>代理</b>：AI 服务不可用时抛 {@link AiUnavailableException}，
 *       <b>绝不</b>降级成「未找到」——否则用户会以为文档里真的没有答案（方案 §6 红线）；</li>
 *   <li><b>适配</b>：{@link AiAnswerAdapter} 把 AI 的 snake_case 转成 §9 契约；</li>
 *   <li><b>留痕</b>：写 {@code ai_ask_log}（审计硬要求）+ 沿用既有 {@code operate_log}。</li>
 * </ol>
 *
 * <p>留痕写在<b>拿到答案之后</b>（成功与失败都写）：失败时也要留下"谁问过、问到哪一步失败"，
 * 否则审计上会出现「用户说问过、系统里查不到」的空洞。为此失败路径也落一条日志再抛异常。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    /** 答案摘要入库长度（列宽 500，留余量）。 */
    private static final int DIGEST_CHARS = 200;

    private final AiProperties props;
    private final AiServiceClient ai;
    private final AiAnswerAdapter adapter;
    private final AiScopeResolver scopeResolver;
    private final AiAskLogMapper askLogMapper;
    private final AttachmentMapper attachmentMapper;
    private final OperationLogService operationLogService;

    public AiChatService(AiProperties props,
                         AiServiceClient ai,
                         AiAnswerAdapter adapter,
                         AiScopeResolver scopeResolver,
                         AiAskLogMapper askLogMapper,
                         AttachmentMapper attachmentMapper,
                         OperationLogService operationLogService) {
        this.props = props;
        this.ai = ai;
        this.adapter = adapter;
        this.scopeResolver = scopeResolver;
        this.askLogMapper = askLogMapper;
        this.attachmentMapper = attachmentMapper;
        this.operationLogService = operationLogService;
    }

    public AiChatResponse ask(AiChatRequest request) {
        long started = System.currentTimeMillis();
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw new BizException(400, "问题不能为空");
        }
        if (!props.isEnabled()) {
            throw new AiUnavailableException(props.disabledReason());
        }

        AiScopeResolver.ChatScope scope = scopeResolver.resolveForChat(
                request.getProjectId(), request.getAttachmentIds());

        // 作用域内没有任何已可检索的文档：这是「合法的空结果」，不是错误。
        // 但绝不能把空 docIds 传给 AI 服务——它对空值的语义是「检索全部文档」，
        // 那等于绕开主系统的权限收口（见 AiServiceClient.chat 的注释）。
        if (scope.docIds().isEmpty()) {
            AiChatResponse empty = emptyAnswer(request, scope);
            empty.setLogId(recordAskLog(request, scope, empty, System.currentTimeMillis() - started));
            return empty;
        }

        // 作用域内附件（按 doc_id 索引），用于回填引用的 attachmentId
        Map<Long, Attachment> attachmentsById = new LinkedHashMap<>();
        for (Attachment a : scopeResolver.selectByIds(scope.allowedAttachmentIds())) {
            attachmentsById.put(a.getId(), a);
        }

        Map<String, Object> data;
        try {
            data = ai.chat(question, scope.docIds(), null, request.getTopK());
        } catch (RuntimeException e) {
            // 失败也留痕：审计上「问过但失败了」同样是必须能查的事实
            recordAskLog(request, scope, null, System.currentTimeMillis() - started);
            throw e;
        }

        long elapsedMs = System.currentTimeMillis() - started;
        AiChatResponse response = adapter.adapt(data, attachmentsById, conversationId(request), elapsedMs);
        response.setLogId(recordAskLog(request, scope, response, elapsedMs));
        operationLogService.log("PROJECT", scope.projectId(), "AI_ASK",
                "AI 问答：" + truncate(question, 60)
                        + "（作用域 docIds=" + scope.docIds().size() + "，引用 " + response.getCitations().size() + " 条）");
        return response;
    }

    /**
     * 作用域内没有可检索文档时的答复。
     *
     * <p>刻意返回 200 + 明确文案，而不是抛错：这是"合法的没东西可查"，
     * 用户看到「当前范围内还没有已解析的文档，请先触发解析」才是可操作的；
     * 抛错会让人以为系统坏了。
     */
    private AiChatResponse emptyAnswer(AiChatRequest request, AiScopeResolver.ChatScope scope) {
        AiChatResponse out = new AiChatResponse();
        out.setConversationId(conversationId(request));
        String scopeText = scope.projectId() == null
                ? "当前作用域"
                : "项目「" + (StringUtils.hasText(scope.projectName()) ? scope.projectName() : scope.projectId()) + "」";
        out.setAnswer("> ⚠️ **" + scopeText + "内还没有可用于问答的文档。**\n>\n"
                + "> 请先在附件列表触发 AI 解析，待状态变为「已可检索」后再提问。");
        out.setDegraded(false);
        out.setNotice("当前作用域内没有已可检索的文档");
        return out;
    }

    /**
     * 写问答留痕，返回日志 id。
     *
     * <p>{@code response} 为 null 表示「这次问答失败了」——此时仍要落库，
     * 只是引用数记 0、摘要为空。
     */
    private Long recordAskLog(AiChatRequest request, AiScopeResolver.ChatScope scope,
                              AiChatResponse response, long elapsedMs) {
        AiAskLog log = new AiAskLog();
        AuthContext.Current current = AuthContext.get();
        if (current != null) {
            log.setUserId(current.userId());
            log.setUserName(current.name());
        }
        log.setQuestion(AiJson.truncate(request.getQuestion() == null ? "" : request.getQuestion(), 2000));
        log.setProjectId(scope.projectId());
        log.setAttachmentIds(joinIds(scope.requestedAttachmentIds()));
        // 记下"实际允许的范围"：事后复核权限判定时，这是唯一可信的依据
        log.setDocIds(joinStrings(scope.docIds()));
        log.setCitedCount(response == null ? 0 : response.getCitations().size());
        log.setElapsedMs(elapsedMs);
        log.setDegraded(response != null && response.isDegraded() ? 1 : 0);
        log.setNotice(response == null ? "问答失败" : AiJson.truncate(response.getNotice(), 500));
        log.setAnswerDigest(response == null ? null : AiJson.truncate(response.getAnswer(), DIGEST_CHARS));
        log.setCreateTime(LocalDateTime.now());
        askLogMapper.insert(log);
        return log.getId();
    }

    /** 会话 id：P0 只回显；前端不传时给一个一次性的，便于串起同一次问答的前后端日志。 */
    private String conversationId(AiChatRequest request) {
        return StringUtils.hasText(request.getConversationId())
                ? request.getConversationId()
                : UUID.randomUUID().toString().replace("-", "");
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }

    private String joinStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
