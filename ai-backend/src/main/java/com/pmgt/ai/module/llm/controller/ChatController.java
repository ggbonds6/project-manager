package com.pmgt.ai.module.llm.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmgt.ai.common.gate.GateChannel;
import com.pmgt.ai.common.gate.GateResult;
import com.pmgt.ai.common.gate.PipelineGate;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.module.llm.BizQuerySpec;
import com.pmgt.ai.module.llm.QaService;
import com.pmgt.ai.module.retrieval.SearchScope;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 文档问答：基于已入库文档，用工具调用（检索 → 读页 → 计算）产出**带来源页码**的答案。
 *
 * <p>请求体字段：{@code question} / {@code doc_ids} / {@code history}
 * （契约记录见 {@code ai-backend/docs/迁移方案与对照表.md} §3，curl 示例也用的是 {@code question}）；
 * 另可带 {@code biz_query}（可选，见下）。
 *
 * <p>响应 {@code data}：{@code answer / trace / scope / llm / stopped_reason / error / citations}，
 * 其中 {@code citations} 是本次改造新增的<b>结构化引用表</b>
 * （{@code index / doc_id / filename / page_no / snippet / score}，顺序 = 编号顺序），
 * 前端据此把答案里的 {@code [1]} 变成"跳到该附件第 N 页"的链接；
 * 既有字段一个都没动（只增不改）。
 *
 * <h2>L1 交互式准入（设计 §5.2，本批新增）</h2>
 * <p>{@code /chat} 走 L1 的 {@code INTERACTIVE} 通道：<b>1.5s 内拿不到准入就 503 快速失败</b>
 * （等待上限 = {@code ai.gate.interactive.wait-ms}，默认沿用 {@code MODEL_ACQUIRE_TIMEOUT_MS}）。
 * 这条纪律<b>必须保住</b>：交互式绝不排在几十分钟的解析任务后面，
 * 也绝不"排到前端 180s 超时"——宁可明确 503 让用户重试。
 * 拿到准入后，L1 permit <b>在整个问答期间持有</b>（H1：先 L1 后 L2，且 L1 不提前释放）。
 */
@RestController
public class ChatController {

    private final QaService qaService;
    /** 可为 null（单测里的"只有 L2"装配）；生产恒为非空。 */
    private final PipelineGate gate;

    public ChatController(QaService qaService) {
        this(qaService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(QaService qaService, PipelineGate gate) {
        this.qaService = qaService;
        this.gate = gate;
    }

    /**
     * 问答入口。
     *
     * <p>⚠️ 为什么按 {@code biz_query} 分两个分支调 {@code QaService}：
     * <b>不传 {@code biz_query} 的请求必须走原来的四参入口</b>——那条路径的行为、工具清单、
     * 提示词都与本版本前逐字节一致（§11.2 的兼容性要求，也是"分批发版"的前提）。
     * 传了才走带通道的五参入口（多注册一个工具、多一段业务路由提示词）。
     *
     * <p>{@code scope}（可选）在两条分支上都生效：它是检索的<b>硬上界</b>
     * （文档/附件/项目/密级四维），与模型给的 {@code doc_id} 取交集，越界即空。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatIn payload) {
        BizQuerySpec bizQuery = payload.getBizQuery();
        SearchScope scope = payload.searchScope();
        String projectId = projectIdOf(scope);

        // L1 交互式准入：拿不到就 503（快速失败），绝不排队到用户超时（设计 §5.2/§5.4）
        GateResult admission = null;
        if (gate != null) {
            admission = gate.admitInteractive(projectId);
            if (!admission.admitted()) {
                throw ApiException.unavailable(admission.describe());
            }
        }
        try {
            QaService.QaResult result = bizQuery == null
                    ? qaService.ask(payload.getQuestion(), payload.getDocIds(), payload.getHistory(), null,
                            null, scope)
                    : qaService.ask(
                            payload.getQuestion(), payload.getDocIds(), payload.getHistory(), null, bizQuery,
                            scope);
            return ApiResponse.ok(result.toDict());
        } finally {
            // L1 permit 覆盖整个问答期间（H1）；失败/异常路径也必须归还（否则就是 permit 泄漏）
            if (admission != null) {
                admission.close();
            }
        }
    }

    /** 项目维度（按项目公平调度的输入）：作用域里给了唯一 project_id 就用它，否则归默认桶。 */
    private static String projectIdOf(SearchScope scope) {
        if (scope == null || scope.projectIds() == null || scope.projectIds().size() != 1) {
            return null;
        }
        return scope.projectIds().iterator().next();
    }

    /** 让 L1 通道常量在编译期可见（自检/文档引用它，避免"字符串散落"）。 */
    public static GateChannel channel() {
        return GateChannel.INTERACTIVE;
    }

    /**
     * {@code /chat} 的请求体。
     *
     * <p>⚠️ <b>字段名的事实描述（2026-09-20 修正）</b>：本类原先的注释声称
     * "请求体字段与 Python 版一致（{@code question} / {@code doc_ids} / {@code history}）"，
     * 但 DTO 字段名是 {@code docIds}、服务也没有配 {@code SNAKE_CASE} 命名策略
     * （见 {@code application.yml}：只有 {@code default-property-inclusion}），
     * 即<b>线上实际只认 camelCase 的 {@code docIds}</b>，与文档/历史契约写的 {@code doc_ids} 不符——
     * 主系统按老实契约发 {@code doc_ids} 时，这个字段会被静默丢弃（表现为"检索范围没生效、答了全库"，
     * 而不是报错，最难排查）。
     *
     * <p>修法是<b>两种写法都收</b>：序列化主名用 snake_case（对齐文档契约），
     * 同时用 {@link JsonAlias} 兼容 camelCase（老调用方已经这么发了，不能把它们的请求打挂）。
     */
    @Data
    public static class ChatIn {

        private String question = "";

        /** 限定检索范围；{@code doc_ids}（主名，见文档契约）与 {@code docIds}（别名）都接受。 */
        @JsonProperty("doc_ids")
        @JsonAlias({"docIds"})
        private List<String> docIds;

        private List<Map<String, Object>> history;

        /**
         * P2 受控查询通道（{@code docs/AI前端与集成方案.md} §11.2，**可选**）。
         *
         * <pre>
         * "biz_query": { "url": "http://…/api/ai/query",
         *                "scope_token": "&lt;短时效 JWT&gt;",
         *                "entities": ["projects","contracts","payments","stats"] }
         * </pre>
         *
         * <p><b>不传＝不注册 {@code query_business_data} 工具</b>，行为与本版本前完全一致；
         * 传了才把该工具（及对应提示词段落）装进这次问答。{@code scope_token} 是唯一授权凭据，
         * 只用于回调主系统的 Authorization Header，AI 侧不记录、不回显。
         *
         * <p>主名 {@code biz_query} 对齐契约，同时兼容 {@code bizQuery}（同 {@code docIds} 的处理）。
         */
        @JsonProperty("biz_query")
        @JsonAlias({"bizQuery"})
        private BizQuerySpec bizQuery;

        /**
         * 请求级作用域（**可选**，检索的硬上界）。
         *
         * <pre>
         * "scope": { "doc_ids": ["…"], "attachment_ids": ["…"],
         *            "project_ids": ["…"], "security_levels": ["内部","秘密"] }
         * </pre>
         *
         * <p>为什么不复用 {@code doc_ids}：{@code doc_ids} 是"本次要问哪些文档"（历史契约，语义不变），
         * 而 {@code scope} 是<b>四维硬约束</b>（多了附件/项目/密级），并且两处给了 doc 清单时
         * 取<b>交集</b>（见 {@code SearchScope.intersect}）。
         *
         * <p><b>缺失元数据一律不算命中</b>：一旦给了密级/项目维度，缺这些字段的文档会被排除——
         * 缺失 ≠ 通配，否则权限过滤形同虚设。
         */
        @JsonProperty("scope")
        @JsonAlias({"search_scope", "searchScope"})
        private ScopeIn scope;

        /** 把请求里的作用域转成检索层的作用域对象（未给 = 无约束）。 */
        public SearchScope searchScope() {
            return scope == null
                    ? SearchScope.empty()
                    : SearchScope.of(scope.getDocIds(), scope.getAttachmentIds(),
                            scope.getProjectIds(), scope.getSecurityLevels());
        }
    }

    /**
     * 请求里的作用域四维（与 {@link SearchScope} 一一对应；全部可选）。
     *
     * <p>只收名字对齐的 snake_case 主名 + camelCase 别名（与 {@code doc_ids} 同一处理，
     * 避免"按文档发 snake_case 被静默丢弃"那类最难排查的问题）。
     */
    @Data
    public static class ScopeIn {

        /** 限定文档（与顶层 {@code doc_ids} 取交集）。 */
        @JsonProperty("doc_ids")
        @JsonAlias({"docIds"})
        private List<String> docIds;

        /** 限定主系统附件 id。 */
        @JsonProperty("attachment_ids")
        @JsonAlias({"attachmentIds"})
        private List<String> attachmentIds;

        /** 限定项目 id（文档元数据里的 project_id）。 */
        @JsonProperty("project_ids")
        @JsonAlias({"projectIds"})
        private List<String> projectIds;

        /**
         * 限定密级（文档元数据里的 {@code security_level}）。
         *
         * <p>给了值就只在该密级集合内检索；文档没有密级元数据 ⇒ <b>不在范围内</b>（缺失 ≠ 通配）。
         */
        @JsonProperty("security_levels")
        @JsonAlias({"securityLevels"})
        private List<String> securityLevels;
    }
}
