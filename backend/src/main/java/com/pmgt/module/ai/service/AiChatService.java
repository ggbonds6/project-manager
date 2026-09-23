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
import com.pmgt.module.ai.query.AiQueryEntity;
import com.pmgt.module.ai.query.AiQueryUsageTracker;
import com.pmgt.module.ai.query.ScopeTokenService;
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
 *
 * <h2>P2：受控查询通道（§11.2）</h2>
 * <p>调用 AI 前签发一枚短时效 {@code scope_token}（范围 = 该用户可访问的项目），
 * 连同回调地址一起放进 {@code biz_query}——AI 侧需要业务事实时回调主系统的
 * {@code /api/ai/query/{entity}}。用了几次、查了哪些 entity 由
 * {@link AiQueryUsageTracker} 按 token 的 jti 统计，问答结束后写进 {@code ai_ask_log}（§11.6）。
 * <b>配置为空则完全不发该字段</b>，AI 侧不注册工具，行为与引入 P2 前逐字一致。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    /** 答案摘要入库长度（列宽 500，留余量）。 */
    private static final int DIGEST_CHARS = 200;

    /** §11.2：主系统给出的 entity 清单（四个受控查询实体）。 */
    private static final List<String> BIZ_ENTITIES = List.of(
            AiQueryEntity.PROJECTS.key(), AiQueryEntity.CONTRACTS.key(),
            AiQueryEntity.PAYMENTS.key(), AiQueryEntity.STATS.key());

    /** AI 侧受控查询工具名：仅用于"本地计数为 0 时从 toolTrace 兜底计数"。 */
    private static final String BIZ_QUERY_TOOL = "query_business_data";

    private final AiProperties props;
    private final AiServiceClient ai;
    private final AiAnswerAdapter adapter;
    private final AiScopeResolver scopeResolver;
    private final AiAskLogMapper askLogMapper;
    private final AttachmentMapper attachmentMapper;
    private final OperationLogService operationLogService;
    private final ScopeTokenService scopeTokens;
    private final AiQueryUsageTracker queryUsage;

    public AiChatService(AiProperties props,
                         AiServiceClient ai,
                         AiAnswerAdapter adapter,
                         AiScopeResolver scopeResolver,
                         AiAskLogMapper askLogMapper,
                         AttachmentMapper attachmentMapper,
                         OperationLogService operationLogService,
                         ScopeTokenService scopeTokens,
                         AiQueryUsageTracker queryUsage) {
        this.props = props;
        this.ai = ai;
        this.adapter = adapter;
        this.scopeResolver = scopeResolver;
        this.askLogMapper = askLogMapper;
        this.attachmentMapper = attachmentMapper;
        this.operationLogService = operationLogService;
        this.scopeTokens = scopeTokens;
        this.queryUsage = queryUsage;
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
            empty.setLogId(recordAskLog(request, scope, empty, System.currentTimeMillis() - started,
                    AiQueryUsageTracker.Snapshot.EMPTY));
            return empty;
        }

        // 作用域内附件（按 doc_id 索引），用于回填引用的 attachmentId
        Map<Long, Attachment> attachmentsById = new LinkedHashMap<>();
        for (Attachment a : scopeResolver.selectByIds(scope.allowedAttachmentIds())) {
            attachmentsById.put(a.getId(), a);
        }

        ScopeTokenService.IssuedScope issued = issueScope(scope);
        Map<String, Object> data;
        AiQueryUsageTracker.Snapshot usage;
        try {
            data = ai.chat(question, scope.docIds(), null, request.getTopK(), bizQuery(issued));
            usage = usageOf(issued, data);
        } catch (RuntimeException e) {
            // 失败也留痕：审计上「问过但失败了」同样是必须能查的事实
            recordAskLog(request, scope, null, System.currentTimeMillis() - started, usageOf(issued, null));
            throw e;
        } finally {
            // 一次问答一枚令牌，用完即清（回调必然发生在 /chat 返回之前）
            if (issued != null) {
                queryUsage.clear(issued.jti());
            }
        }

        long elapsedMs = System.currentTimeMillis() - started;
        AiChatResponse response = adapter.adapt(data, attachmentsById, conversationId(request), elapsedMs);
        response.setLogId(recordAskLog(request, scope, response, elapsedMs, usage));
        operationLogService.log("PROJECT", scope.projectId(), "AI_ASK",
                "AI 问答：" + truncate(question, 60)
                        + "（作用域 docIds=" + scope.docIds().size() + "，引用 " + response.getCitations().size() + " 条"
                        + "，系统查询 " + usage.count() + " 次）");
        return response;
    }

    // ══════════════════════════════════════════════════════════════════
    // P2 受控查询通道（§11.2）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 为本次问答签发作用域令牌（范围 = 该用户可访问的项目）。
     *
     * <p>三种情况不签发：① 配置里没给回调地址（P2 未启用）；② 没有登录用户（无法授权，
     * {@code /chat} 本就要求登录，这里只是兜底）；③ 签发异常（不因此让整次问答失败——
     * 没有系统数据的问答仍是有价值的，AI 侧会如实说"系统数据没查到"）。
     *
     * <p>范围收窄策略：用户点名了某个项目就只授权那一个；否则授权"可访问的全部项目"
     * （本系统角色不按项目分权，见 {@link AiScopeResolver#accessibleProjectIds()}）。
     */
    private ScopeTokenService.IssuedScope issueScope(AiScopeResolver.ChatScope scope) {
        if (!StringUtils.hasText(props.getQueryCallbackUrl())) {
            // 空配置＝不带 biz_query：AI 侧不注册工具（§11.2 的可分批发版开关）
            return null;
        }
        AuthContext.Current current = AuthContext.get();
        if (current == null || current.userId() == null) {
            log.warn("[ai-chat] 无登录用户，跳过受控查询通道（scope_token 无法签发）");
            return null;
        }
        try {
            List<Long> projects = scope.projectId() != null
                    ? List.of(scope.projectId())
                    : scopeResolver.accessibleProjectIds();
            return scopeTokens.issue(current.userId(), current.name(), projects);
        } catch (RuntimeException e) {
            log.warn("[ai-chat] 作用域令牌签发失败，本次问答不带受控查询通道：{}", e.toString());
            return null;
        }
    }

    /**
     * 组装 §11.2 的 {@code biz_query}；令牌为空（通道未启用/签发失败）时返回 null，
     * 调用方据此<b>不下发该字段</b>。
     *
     * <p>地址末尾的斜杠先去掉：AI 侧是 {@code POST {url}/{entity}} 拼接，
     * 留着会变成 {@code //stats}（多数框架能容忍，但没必要赌）。
     */
    private Map<String, Object> bizQuery(ScopeTokenService.IssuedScope issued) {
        if (issued == null) {
            return null;
        }
        String url = props.getQueryCallbackUrl().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        Map<String, Object> bizQuery = new LinkedHashMap<>();
        bizQuery.put("url", url);
        bizQuery.put("scope_token", issued.token());
        bizQuery.put("entities", BIZ_ENTITIES);
        return bizQuery;
    }

    /**
     * 本次问答「用了几次系统查询、查了哪些 entity」。
     *
     * <p>以主系统自己的计数为准（{@link AiQueryUsageTracker}）；本地计数为 0 时从
     * AI 的 {@code toolTrace} 兜底数一次工具调用条数——双机 + 负载均衡下签发与回调可能
     * 落在不同节点，那时只有次数、没有 entity，也好过把"用过系统数据"记成 0。
     */
    private AiQueryUsageTracker.Snapshot usageOf(ScopeTokenService.IssuedScope issued,
                                                Map<String, Object> aiData) {
        AiQueryUsageTracker.Snapshot snapshot = issued == null
                ? AiQueryUsageTracker.Snapshot.EMPTY
                : queryUsage.snapshot(issued.jti());
        if (snapshot.count() > 0 || aiData == null) {
            return snapshot;
        }
        int fromTrace = 0;
        for (Map<String, Object> item : AiJson.asList(aiData.get("trace"))) {
            if (BIZ_QUERY_TOOL.equals(AiJson.text(item, "name"))) {
                fromTrace++;
            }
        }
        return fromTrace > 0 ? new AiQueryUsageTracker.Snapshot(fromTrace, List.of()) : snapshot;
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
     *
     * @param usage 本次问答的受控查询用量（§11.6）：次数 + entity 清单
     */
    private Long recordAskLog(AiChatRequest request, AiScopeResolver.ChatScope scope,
                              AiChatResponse response, long elapsedMs,
                              AiQueryUsageTracker.Snapshot usage) {
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
        // P2 留痕（V14）：这次问答用了几次系统查询、查了哪些 entity
        AiQueryUsageTracker.Snapshot used = usage == null ? AiQueryUsageTracker.Snapshot.EMPTY : usage;
        log.setBizQueryCount(used.count());
        log.setBizEntities(used.entitiesText());
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
