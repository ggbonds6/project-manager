package com.pmgt.module.ai.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.ai.query.AiQueryEntity.FieldSpec;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.entity.Contract;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.service.ContractLinkService;
import com.pmgt.module.project.service.ProjectMetricsService;
import com.pmgt.module.stats.service.StatsQuery;
import com.pmgt.module.stats.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P2 受控查询（结构化问答）的四个实体实现——§11.4。
 *
 * <h2>三条铁律（每一处实现都在守它）</h2>
 * <ol>
 *   <li><b>不收 SQL / 表达式 / 字段名拼接</b>：{@code filters} 先过
 *       {@link AiQueryEntity} 的白名单（字段名 + 类型 + 枚举值），非法一律 400 并回列支持字段；</li>
 *   <li><b>只认 scope_token 里的 projects</b>：请求体的 {@code projectId} 若不在范围内一律 403，
 *       且<b>在发出任何查询之前</b>就拒绝（不查、不返回、不留数据）；</li>
 *   <li><b>口径与既有页面逐位一致</b>：预算/合同/实付/阶段/进度复用
 *       {@link ProjectMetricsService}（从项目详情与列表抽出的同一份实现），
 *       统计复用 {@link StatsService}（只加"范围收窄"输入），绝不另写一套 SQL 口径。</li>
 * </ol>
 *
 * <h2>为什么每次查询都写 operate_log</h2>
 * <p>§11.6 的硬要求：受控查询会碰到"预算/付款"这类敏感事实，必须能倒查
 * 「谁、何时、查哪个 entity、什么条件、返回几行、花了多久」。被拒绝的调用（400/403）
 * 同样留痕——"有人在试范围外的项目"恰恰是审计最需要看到的那一条。
 */
@Service
public class AiQueryService {

    private static final Logger log = LoggerFactory.getLogger(AiQueryService.class);

    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** §11.3：limit 默认 20、上限 100。 */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** 留痕里 filters 摘要的截断长度（日志列宽有限，且摘要不该完整备份参数）。 */
    private static final int FILTER_DIGEST_CHARS = 300;

    /** 作用域说明里最多列几个项目名（再多就只写总数，避免把 scope 文字撑爆）。 */
    private static final int SCOPE_NAME_LIMIT = 10;

    /**
     * 统计类口径必须写明的一条：数字只覆盖本次授权范围。
     *
     * <p>为什么值得单独写：统计页的"核算单元"是按<b>全部</b>项目算的（有子项目的顶层项目只作容器）。
     * 受控查询把范围收窄后，若某个容器项目的子项目不在范围内，它会按"单项目"计入——
     * 这是"只看范围内"的必然结果，但必须让模型能原话转述，否则用户拿到的数字与统计页对不上时无从解释。
     */
    private static final String SCOPE_RESTRICT_NOTE =
            "统计只覆盖本次授权范围内的项目（容器项目的子项目不在范围内时，该容器按单项目计入）。";

    /** 越权提示：§11.6 定死的原话，且不得携带任何数据。 */
    static final String OUT_OF_SCOPE_MESSAGE = "该项目不在本次授权范围（scope）内";

    /** stats.kind=phase_attachment_count 的计数口径（模型要能原话转述，故写成常量复用）。 */
    static final String PHASE_ATTACHMENT_CALIBER =
            "附件数按 attachment.biz_type='PROJECT_PHASE' 且 biz_id=project_phase.id 统计，"
                    + "已排除逻辑删除的附件（attachment.deleted=1）；不含合同附件、付款凭证与项目级附件；"
                    + "阶段顺序按 project_phase.sort_no。";

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final PaymentMapper paymentMapper;
    private final ProjectMetricsService metrics;
    private final StatsService statsService;
    private final ContractLinkService contractLinkService;
    private final OperationLogService operationLogService;
    private final AiQueryUsageTracker usageTracker;

    public AiQueryService(ProjectMapper projectMapper,
                          ContractMapper contractMapper,
                          PaymentMapper paymentMapper,
                          ProjectMetricsService metrics,
                          StatsService statsService,
                          ContractLinkService contractLinkService,
                          OperationLogService operationLogService,
                          AiQueryUsageTracker usageTracker) {
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
        this.paymentMapper = paymentMapper;
        this.metrics = metrics;
        this.statsService = statsService;
        this.contractLinkService = contractLinkService;
        this.operationLogService = operationLogService;
        this.usageTracker = usageTracker;
    }

    // ══════════════════════════════════════════════════════════════════
    // 入口
    // ══════════════════════════════════════════════════════════════════

    /**
     * 执行一次受控查询。
     *
     * @param rawEntity 路径里的 entity（{@code projects|contracts|payments|stats}）
     * @param request   请求体 {@code {filters, limit}}
     * @throws AiQueryException 401 无范围上下文 / 400 filters 非法 / 403 越权
     */
    public AiQueryData query(String rawEntity, AiQueryRequest request) {
        AiQueryScope scope = AiQueryScopeContext.get();
        if (scope == null) {
            // 过滤器没放行就走到这里：说明有别的入口绕过了 ScopeTokenFilter，
            // 宁可拒绝也不能"当成没有范围限制"
            throw AiQueryException.unauthorized("缺少作用域令牌：受控查询必须在 Authorization: Bearer <scope_token> 下调用");
        }
        long started = System.currentTimeMillis();
        Map<String, Object> rawFilters = request == null || request.getFilters() == null
                ? Map.of() : request.getFilters();

        AiQueryEntity entity = AiQueryEntity.of(rawEntity).orElse(null);
        if (entity == null) {
            String message = "不支持的 entity「" + (rawEntity == null ? "" : rawEntity)
                    + "」；支持的 entity：" + AiQueryEntity.allKeys();
            audit(scope, "AI_QUERY", null, rawEntity, rawFilters, rawProjectId(rawFilters),
                    0, System.currentTimeMillis() - started, "拒绝：entity 非法");
            throw AiQueryException.badRequest(message);
        }

        try {
            FilterValues filters = validate(entity, rawFilters);
            List<Long> projectIds = resolveProjectIds(scope, filters.projectId());
            int limit = normalizeLimit(request == null ? null : request.getLimit());
            // 留痕归属：优先用请求里点名的项目（哪怕它越权——"有人问过 99 号项目"正是审计要看的），
            // 否则范围恰好只有一个项目时归到该项目，便于在项目详情页的操作日志里看到
            Long auditProjectId = filters.projectId() != null
                    ? filters.projectId()
                    : (projectIds.size() == 1 ? projectIds.get(0) : null);

            AiQueryData data = switch (entity) {
                case PROJECTS -> projects(projectIds, filters, limit);
                case CONTRACTS -> contracts(projectIds, filters, limit);
                case PAYMENTS -> payments(projectIds, filters, limit);
                case STATS -> stats(projectIds, filters, limit);
            };
            audit(scope, "AI_QUERY_" + entity.key().toUpperCase(Locale.ROOT), entity.key(), entity.key(),
                    rawFilters, auditProjectId, data.getRows().size(),
                    System.currentTimeMillis() - started, null);
            // 计一次"本次问答用过一次系统查询"（成功与空结果都算）
            usageTracker.record(scope.jti(), entity.key());
            return data;
        } catch (AiQueryException e) {
            // 被拒的调用也留痕 + 计数：越权试探必须在审计里看得见
            audit(scope, "AI_QUERY_" + entity.key().toUpperCase(Locale.ROOT), entity.key(), entity.key(),
                    rawFilters, rawProjectId(rawFilters), 0, System.currentTimeMillis() - started,
                    "拒绝(" + e.getStatus() + ")：" + e.getMessage());
            usageTracker.record(scope.jti(), entity.key());
            throw e;
        } catch (RuntimeException e) {
            // 数据库/内部错误：必须以 5xx 交给调用方，让它把"这次没查到"如实说出来
            // （§11.6 红线：绝不能变成"系统里没有"）。错误细节只进日志，不回给调用方。
            log.error("[ai-query] entity={} 执行失败", entity.key(), e);
            audit(scope, "AI_QUERY_" + entity.key().toUpperCase(Locale.ROOT), entity.key(), entity.key(),
                    rawFilters, rawProjectId(rawFilters), 0, System.currentTimeMillis() - started,
                    "失败：主系统查询执行异常（" + e.getClass().getSimpleName() + "）");
            usageTracker.record(scope.jti(), entity.key());
            throw AiQueryException.internal("主系统查询执行失败，本次没有取到任何业务数据"
                    + "（内部错误已记录日志，请如实说明系统数据这次没查到）");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 四个实体
    // ══════════════════════════════════════════════════════════════════

    /** {@code projects}：项目事实 + 每阶段附件数。 */
    private AiQueryData projects(List<Long> projectIds, FilterValues filters, int limit) {
        String scopeText = scopeText(projectIds);
        String caliber = "预算=项目立项预算（project.budget_amount）；合同金额=该项目所挂合同金额合计（含变更），"
                + "总项目容器为子项目之和；已付=实付金额合计（叶子项目为自身付款，容器为子项目之和）；"
                + "进度=按阶段权重加权后取整（0~100）；合同数=该项目可见合同数（含父级总项目共享，V12 关联表口径）；"
                + PHASE_ATTACHMENT_CALIBER
                + "口径与项目详情页、统计页一致。";
        if (projectIds.isEmpty()) {
            return AiQueryData.of(List.of(), "个", caliber, now(), scopeText);
        }

        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<Project>()
                .in(Project::getId, projectIds)
                .orderByAsc(Project::getId);
        if (StringUtils.hasText(filters.name())) {
            qw.like(Project::getName, filters.name());
        }
        if (StringUtils.hasText(filters.status())) {
            qw.eq(Project::getStatus, filters.status());
        }
        if (StringUtils.hasText(filters.type())) {
            qw.eq(Project::getType, filters.type());
        }
        if (filters.year() != null) {
            // 与项目列表/统计同一口径：立项年度 = approve_date 的年份
            qw.apply("EXTRACT(YEAR FROM approve_date) = {0}", filters.year());
        }
        qw.last(fetchFirst(limit));

        List<Project> projects = projectMapper.selectList(qw);
        Map<Long, List<ProjectPhase>> phasesByProject = metrics.phasesByProjects(
                projects.stream().map(Project::getId).toList());
        Map<Long, Long> attachmentCounts = metrics.attachmentCountByPhase(
                phasesByProject.values().stream().flatMap(Collection::stream).map(ProjectPhase::getId).toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project p : projects) {
            List<ProjectPhase> phases = phasesByProject.getOrDefault(p.getId(), List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", p.getId());
            row.put("code", p.getCode());
            row.put("name", p.getName());
            row.put("type", p.getType());
            row.put("status", p.getStatus());
            row.put("parentId", p.getParentId());
            row.put("currentPhaseName", metrics.currentPhaseName(phases));
            row.put("overallProgress", metrics.overallProgress(phases));
            row.put("budgetAmount", zero(p.getBudgetAmount()));
            row.put("contractAmount", metrics.contractAmount(p, true));
            row.put("paidAmount", metrics.paidAmount(p));
            row.put("contractCount", metrics.contractCount(p.getId()));
            row.put("approveDate", p.getApproveDate());
            row.put("phases", phases.stream().map(ph -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("phaseId", ph.getId());
                item.put("phaseName", ph.getPhaseName());
                item.put("status", ph.getStatus());
                item.put("percent", ph.getPercent());
                item.put("attachmentCount", attachmentCounts.getOrDefault(ph.getId(), 0L));
                return item;
            }).toList());
            rows.add(row);
        }
        return AiQueryData.of(cap(rows, limit), "个", caliber, now(), scopeText);
    }

    /** {@code contracts}：合同清单（含覆盖项目）。 */
    private AiQueryData contracts(List<Long> projectIds, FilterValues filters, int limit) {
        String scopeText = scopeText(projectIds);
        String caliber = "合同范围=本次范围内各项目自身及其父级总项目共享的合同（V12 project_contract 关联表口径，"
                + "含施工主合同与监理/测评等副合同）；金额为合同金额（不含变更），变更金额单列（changeAmount）；"
                + "覆盖项目按关联表列出（业务规则：一份合同只挂一个子项目）。";
        if (projectIds.isEmpty()) {
            return AiQueryData.of(List.of(), "个", caliber, now(), scopeText);
        }
        Set<Long> contractIds = visibleContractIds(projectIds);
        if (contractIds.isEmpty()) {
            return AiQueryData.of(List.of(), "个", caliber, now(), scopeText);
        }

        LambdaQueryWrapper<Contract> qw = new LambdaQueryWrapper<Contract>()
                .in(Contract::getId, contractIds)
                .orderByAsc(Contract::getId);
        if (StringUtils.hasText(filters.vendorName())) {
            qw.like(Contract::getVendorName, filters.vendorName());
        }
        qw.last(fetchFirst(limit));

        List<Contract> contracts = contractMapper.selectList(qw);
        Map<Long, String> projectNames = projectNames(contracts.stream()
                .flatMap(c -> contractLinkService.projectIdsOfContract(c.getId()).stream()).toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Contract c : contracts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractId", c.getId());
            row.put("name", c.getName());
            row.put("contractNo", c.getContractNo());
            row.put("vendorName", c.getVendorName());
            row.put("contractAmount", zero(c.getContractAmount()));
            row.put("changeAmount", zero(c.getChangeAmount()));
            row.put("contractStatus", c.getContractStatus());
            row.put("contractType", c.getContractType());
            row.put("signDate", c.getSignDate());
            row.put("projects", contractLinkService.projectIdsOfContract(c.getId()).stream()
                    .map(pid -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("projectId", pid);
                        item.put("projectName", projectNames.get(pid));
                        return item;
                    }).toList());
            rows.add(row);
        }
        return AiQueryData.of(cap(rows, limit), "个", caliber, now(), scopeText);
    }

    /** {@code payments}：付款记录。 */
    private AiQueryData payments(List<Long> projectIds, FilterValues filters, int limit) {
        String scopeText = scopeText(projectIds);
        String caliber = "付款范围=本次范围内项目自身登记的付款 + 这些项目可见合同（含父级总项目共享合同）上的付款，"
                + "与项目详情页「资金情况」同口径；"
                + "金额单位为元，取 payment.plan_amount / payment.paid_amount 的登记原值（主系统不做事后换算）；"
                + "状态 UNPAID 未付 / PART 部分付款 / PAID 已付。";
        if (projectIds.isEmpty()) {
            return AiQueryData.of(List.of(), "个", caliber, now(), scopeText);
        }
        Set<Long> contractIds = visibleContractIds(projectIds);

        LambdaQueryWrapper<Payment> qw = new LambdaQueryWrapper<>();
        qw.and(w -> {
            w.in(Payment::getProjectId, projectIds);
            if (!contractIds.isEmpty()) {
                w.or(o -> o.in(Payment::getContractId, contractIds));
            }
        });
        if (StringUtils.hasText(filters.nodeCode())) {
            qw.eq(Payment::getNodeCode, filters.nodeCode());
        }
        if (StringUtils.hasText(filters.status())) {
            qw.eq(Payment::getStatus, filters.status());
        }
        qw.orderByAsc(Payment::getId).last(fetchFirst(limit));

        List<Payment> payments = paymentMapper.selectList(qw);
        Map<Long, String> projectNames = projectNames(payments.stream().map(Payment::getProjectId).toList());
        Map<Long, String> contractNames = contractNames(payments.stream().map(Payment::getContractId).toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Payment p : payments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", p.getId());
            row.put("projectId", p.getProjectId());
            row.put("projectName", projectNames.get(p.getProjectId()));
            row.put("nodeCode", p.getNodeCode());
            row.put("nodeName", p.getNodeName());
            row.put("planAmount", zero(p.getPlanAmount()));
            row.put("paidAmount", zero(p.getPaidAmount()));
            row.put("planDate", p.getPlanDate());
            row.put("paidDate", p.getPaidDate());
            row.put("status", p.getStatus());
            row.put("contractId", p.getContractId());
            row.put("contractName", p.getContractId() == null ? null : contractNames.get(p.getContractId()));
            rows.add(row);
        }
        return AiQueryData.of(cap(rows, limit), "个", caliber, now(), scopeText);
    }

    /** {@code stats}：三种聚合口径。 */
    private AiQueryData stats(List<Long> projectIds, FilterValues filters, int limit) {
        String kind = filters.kind();
        return switch (kind) {
            case AiQueryEntity.KIND_PHASE_ATTACHMENT_COUNT -> phaseAttachmentCount(projectIds, limit);
            case AiQueryEntity.KIND_TYPE_DISTRIBUTION -> typeDistribution(projectIds, limit);
            case AiQueryEntity.KIND_YEAR_AMOUNT -> yearAmount(projectIds, limit);
            default -> throw AiQueryException.badRequest(
                    "entity=stats 的 kind 取值非法：「" + kind + "」。" + AiQueryEntity.STATS.supportHint());
        };
    }

    /**
     * {@code kind=phase_attachment_count}：某（些）项目各阶段的附件数——本轮最重要的一条。
     *
     * <p>它就是"某项目某阶段有几个附件"这类问题的正解：AI 侧只看得见已入库文档，
     * 数出来永远偏小，只有回到主系统按业务归属计数才是对的。
     */
    private AiQueryData phaseAttachmentCount(List<Long> projectIds, int limit) {
        String scopeText = scopeText(projectIds);
        String caliber = "每个阶段一行（project_phase.sort_no 升序）；" + PHASE_ATTACHMENT_CALIBER
                + "阶段状态 NOT_STARTED 未开始 / IN_PROGRESS 进行中 / DONE 已完成 / SKIPPED 已跳过。";
        if (projectIds.isEmpty()) {
            return AiQueryData.of(List.of(), "个", caliber, now(), scopeText);
        }
        Map<Long, List<ProjectPhase>> phasesByProject = metrics.phasesByProjects(projectIds);
        Map<Long, Long> counts = metrics.attachmentCountByPhase(
                phasesByProject.values().stream().flatMap(Collection::stream).map(ProjectPhase::getId).toList());
        Map<Long, String> projectNames = projectNames(projectIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long pid : projectIds) {
            for (ProjectPhase ph : phasesByProject.getOrDefault(pid, List.of())) {
                if (rows.size() >= limit) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectId", pid);
                row.put("projectName", projectNames.get(pid));
                row.put("phaseId", ph.getId());
                row.put("phaseName", ph.getPhaseName());
                row.put("status", ph.getStatus());
                row.put("percent", ph.getPercent());
                row.put("attachmentCount", counts.getOrDefault(ph.getId(), 0L));
                rows.add(row);
            }
        }
        return AiQueryData.of(cap(rows, limit), "个", caliber, now(), scopeText);
    }

    /** {@code kind=type_distribution}：项目类型分布（复用统计页的核算单元口径）。 */
    private AiQueryData typeDistribution(List<Long> projectIds, int limit) {
        String scopeText = scopeText(projectIds);
        String caliber = "按核算单元计数（核算单元=子项目，或无子项目的顶层项目；有子项目的顶层项目只作容器、不重复计数），"
                + "与统计页「构成分布 → 类型」一致；HW 硬件 / SW 软件；"
                + SCOPE_RESTRICT_NOTE;
        if (projectIds.isEmpty()) {
            return AiQueryData.of(List.of(), "个", caliber, now(), scopeText);
        }
        StatsQuery q = new StatsQuery();
        q.setProjectIds(projectIds);
        Map<String, Object> result = statsService.distributions(q);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : asRowList(result.get("type"))) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dimension", "type");
            row.put("name", item.get("name"));
            row.put("value", item.get("value"));
            rows.add(row);
        }
        return AiQueryData.of(rows, "个", caliber, now(), scopeText);
    }

    /** {@code kind=year_amount}：年度资金（复用统计页的年度归集口径）。 */
    private AiQueryData yearAmount(List<Long> projectIds, int limit) {
        String scopeText = scopeText(projectIds);
        String caliber = "预算按核算单元立项年度归集；合同金额（含变更）与已付金额按合同覆盖项目的立项年度各归集一次（不重复）；"
                + "金额单位为元；与统计页「年度资金」一致；"
                + SCOPE_RESTRICT_NOTE;
        if (projectIds.isEmpty()) {
            return AiQueryData.of(List.of(), "元", caliber, now(), scopeText);
        }
        StatsQuery q = new StatsQuery();
        q.setProjectIds(projectIds);
        Map<String, Object> result = statsService.yearMoney(q);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : asRowList(result.get("rows"))) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", item.get("year"));
            row.put("budget", item.get("budget"));
            row.put("contract", item.get("contract"));
            row.put("paid", item.get("paid"));
            rows.add(row);
        }
        return AiQueryData.of(rows, "元", caliber, now(), scopeText);
    }

    // ══════════════════════════════════════════════════════════════════
    // 校验与范围
    // ══════════════════════════════════════════════════════════════════

    /**
     * filters 白名单校验（§11.3「不收 SQL/表达式/字段名拼接」的落地）。
     *
     * <p>每条错误都带 {@link AiQueryEntity#supportHint()}：模型最需要的不是"错在哪"，
     * 而是"改成什么能过"（§11.6）。
     */
    private FilterValues validate(AiQueryEntity entity, Map<String, Object> raw) {
        List<String> unknown = raw.keySet().stream()
                .filter(k -> !entity.supports(k))
                .toList();
        if (!unknown.isEmpty()) {
            throw AiQueryException.badRequest("entity=" + entity.key()
                    + " 不支持查询字段 " + quoteAll(unknown)
                    + "（本接口只接受结构化字段，不接受 SQL / 表达式 / 字段名拼接）。" + entity.supportHint());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, FieldSpec> e : entity.fields().entrySet()) {
            String name = e.getKey();
            FieldSpec spec = e.getValue();
            Object value = raw.get(name);
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (spec.required()) {
                    throw AiQueryException.badRequest("entity=" + entity.key() + " 必须提供字段 " + name
                            + "。" + entity.supportHint());
                }
                continue;
            }
            if (value instanceof Map || value instanceof Collection || value.getClass().isArray()) {
                throw AiQueryException.badRequest("entity=" + entity.key() + " 的字段 " + name
                        + " 只能是单个值（字符串/数字），不能是对象或数组。");
            }
            String text = String.valueOf(value).trim();
            if (spec.isEnum()) {
                // 大小写不敏感地匹配，但只接受清单里的<b>规范写法</b>（kind 是 snake_case 小写，
                // status/nodeCode 是大写枚举）：模型把 "PHASE_ATTACHMENT_COUNT" 写成大写也要能过，
                // 但存进 filters 的一律是规范值，下游 switch 不必再做一次转换
                String canonical = spec.allowedValues().stream()
                        .filter(v -> v.equalsIgnoreCase(text))
                        .findFirst()
                        .orElse(null);
                if (canonical == null) {
                    throw AiQueryException.badRequest("entity=" + entity.key() + " 的 " + name
                            + " 取值非法：「" + text + "」。" + entity.supportHint());
                }
                values.put(name, canonical);
            } else if ("整数".equals(spec.type())) {
                values.put(name, toLong(entity, name, text));
            } else {
                values.put(name, text);
            }
        }
        return new FilterValues(values);
    }

    private static Long toLong(AiQueryEntity entity, String name, String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            // 容忍 "12.0" 这种小数形式的整数（模型偶尔把整数写成小数），但 12.7 一律拒绝：
            // 悄悄截断会让"2026.9 年"这种明显错误的输入变成一个看似正常的查询
            try {
                double d = Double.parseDouble(text);
                if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)
                        && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                    return (long) d;
                }
            } catch (NumberFormatException ignored) {
                // 落到下面的统一报错
            }
            throw AiQueryException.badRequest("entity=" + entity.key() + " 的 " + name
                    + " 必须是整数，当前是「" + text + "」。");
        }
    }

    /** 从原始 filters 里尽力取一个 projectId（只用于留痕归属，不参与任何查询判断）。 */
    private static Long rawProjectId(Map<String, Object> filters) {
        Object value = filters.get("projectId");
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 把请求里的 {@code projectId} 收窄到授权范围内。
     *
     * @throws AiQueryException 403 请求的项目不在 scope_token 的 projects 里（不查询、不返回任何数据）
     */
    private List<Long> resolveProjectIds(AiQueryScope scope, Long filterProjectId) {
        if (filterProjectId == null) {
            return scope.projects();
        }
        if (!scope.contains(filterProjectId)) {
            // 提示语按 §11.6 定死；不回显项目名/编号等信息（越权请求不该得到任何业务数据）
            throw AiQueryException.forbidden(OUT_OF_SCOPE_MESSAGE);
        }
        return List.of(filterProjectId);
    }

    /** limit 归一：缺省/非正 → 20；超上限 → 100（契约值，见 §11.3）。 */
    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 崖山/Oracle 方言（与 ProjectService、AttachmentController 的既有写法一致）。 */
    private static String fetchFirst(int limit) {
        return "FETCH FIRST " + limit + " ROWS ONLY";
    }

    /**
     * 行数上限的<b>代码侧</b>保证。
     *
     * <p>{@code FETCH FIRST n ROWS ONLY} 已经把上限压到数据库，但那是一条"SQL 提示"：
     * 一旦哪天换成别的方言/分页插件而没同步改，limit 契约就静默失效了。
     * 这里再兜一层（list 已按 id 升序，截断即"取前 n 条"），
     * 保证「limit 上限 100」是接口行为而不仅是 SQL 细节。
     */
    private static List<Map<String, Object>> cap(List<Map<String, Object>> rows, int limit) {
        return rows.size() <= limit ? rows : new ArrayList<>(rows.subList(0, limit));
    }

    // ══════════════════════════════════════════════════════════════════
    // 留痕
    // ══════════════════════════════════════════════════════════════════

    /**
     * 写 {@code operate_log}（谁 / entity / filters 摘要 / 返回行数 / 耗时）。
     *
     * <p>范围只落在一个项目上时记 {@code bizType=PROJECT} + {@code bizId=项目 id}：
     * 这样它会出现在项目详情页的「操作日志」页签里（与既有留痕同一条查询口径），
     * 运维排查"某个项目的数字是怎么被问出来的"时最顺手。多项目范围记 {@code bizType=AI}。
     *
     * <p>留痕失败不能影响查询结果：{@code operate_log} 是旁路审计，
     * 为了写日志而让一次合法查询失败（甚至把 403 掩盖成 500）是更糟的选择。
     */
    private void audit(AiQueryScope scope, String action, String entity, String rawEntity,
                       Map<String, Object> filters, Long projectId, int rowCount, long elapsedMs,
                       String rejectReason) {
        try {
            String bizType = projectId == null ? "AI" : "PROJECT";
            StringBuilder detail = new StringBuilder("AI 受控查询：entity=")
                    .append(entity == null ? String.valueOf(rawEntity) : entity)
                    .append("，filters=").append(truncate(filters.toString(), FILTER_DIGEST_CHARS))
                    .append("，范围=").append(scope.describe())
                    .append("，返回 ").append(rowCount).append(" 行")
                    .append("，耗时 ").append(elapsedMs).append("ms");
            if (rejectReason != null) {
                detail.append("，").append(rejectReason);
            }
            operationLogService.log(bizType, projectId, action, detail.toString(),
                    scope.userId(), scope.userName());
        } catch (Exception e) {
            log.warn("[ai-query] 留痕失败（不影响查询结果）：{}", e.toString());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String quoteAll(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append('「').append(values.get(i)).append('」');
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════════════

    /** 范围内项目可见的合同 id（V12 关联表口径：自身 + 各级父项目共享）。 */
    private Set<Long> visibleContractIds(Collection<Long> projectIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long pid : projectIds) {
            ids.addAll(contractLinkService.visibleContractIds(pid));
        }
        return ids;
    }

    private Map<Long, String> projectNames(Collection<Long> projectIds) {
        List<Long> ids = projectIds == null ? List.of()
                : projectIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Project p : projectMapper.selectBatchIds(ids)) {
            names.put(p.getId(), p.getName());
        }
        return names;
    }

    private Map<Long, String> contractNames(Collection<Long> contractIds) {
        List<Long> ids = contractIds == null ? List.of()
                : contractIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Contract c : contractMapper.selectBatchIds(ids)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }

    /**
     * 作用域说明（§11.4 的 {@code scope} 硬要求：用户要能看懂"这些数字是哪些项目的"）。
     *
     * <p>项目多时只列前 {@value #SCOPE_NAME_LIMIT} 个：这段文字会原样交给模型并可能被它
     * 转述给用户，几百个项目的清单既烧 token 又没人读；总数照样写明，
     * "这些数字是全部项目汇总"这一信息不丢。
     */
    private String scopeText(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return "无（本次授权未包含任何项目）";
        }
        Map<Long, String> names = projectNames(projectIds);
        if (projectIds.size() == 1) {
            Long pid = projectIds.get(0);
            String name = names.get(pid);
            return "项目 " + pid + (name == null ? "" : "「" + name + "」") + "（本次授权范围内）";
        }
        StringBuilder sb = new StringBuilder("本次授权范围内 ").append(projectIds.size()).append(" 个项目：");
        int listed = Math.min(projectIds.size(), SCOPE_NAME_LIMIT);
        for (int i = 0; i < listed; i++) {
            if (i > 0) {
                sb.append('、');
            }
            Long pid = projectIds.get(i);
            String name = names.get(pid);
            sb.append(pid).append(name == null ? "" : "「" + name + "」");
        }
        if (projectIds.size() > listed) {
            sb.append(" 等 ").append(projectIds.size()).append(" 个");
        }
        return sb.toString();
    }

    private static String now() {
        return LocalDateTime.now().format(DATA_TIME);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRowList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    /**
     * 校验后的 filters（类型已经归一到 {@code Long}/{@code String}）。
     *
     * <p>用一个小包装而不是继续传 {@code Map<String,Object>}：下游每个实体都要
     * {@code (Long) filters.get("projectId")} 这种强转，散在四处迟早有人忘记判类型；
     * 在这里一次性收敛，实体方法里读到的就是确定的类型。
     */
    static final class FilterValues {

        private final Map<String, Object> values;

        FilterValues(Map<String, Object> values) {
            this.values = values;
        }

        Long projectId() {
            return (Long) values.get("projectId");
        }

        Integer year() {
            Long y = (Long) values.get("year");
            return y == null ? null : y.intValue();
        }

        String name() {
            return (String) values.get("name");
        }

        String status() {
            return (String) values.get("status");
        }

        String type() {
            return (String) values.get("type");
        }

        String vendorName() {
            return (String) values.get("vendorName");
        }

        String nodeCode() {
            return (String) values.get("nodeCode");
        }

        String kind() {
            return (String) values.get("kind");
        }
    }
}
