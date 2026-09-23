package com.pmgt.module.ai.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.entity.Contract;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.project.service.ContractLinkService;
import com.pmgt.module.project.service.ProjectMetricsService;
import com.pmgt.module.stats.service.StatsQuery;
import com.pmgt.module.stats.service.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiQueryService} 的受控查询测试（§11.4 / §11.6 / §11.7 的验收口径）。
 *
 * <p>全部用 <b>固定数据集 + Mockito</b>，不连库：
 * <ul>
 *   <li>断言的是<b>手算出来的数字</b>（注释里写着算式），而不是"再跑一遍同样的代码"；</li>
 *   <li>口径一致性靠<b>共用实现</b>保证：{@code AiQueryService} 用的是
 *       {@link ProjectMetricsService}（项目详情/列表同一份），本测试构造的是<b>真的</b>
 *       {@code ProjectMetricsService} + 打桩 mapper，等于验证"两边算的是同一套"；</li>
 *   <li>查询条件本身（有没有按 {@code biz_type=PROJECT_PHASE} 数、有没有把范围外的
 *       合同/项目带进来）用捕获 {@code LambdaQueryWrapper} 的 SQL 片段与参数值来断言——
 *       mocked mapper 不执行 WHERE，只看返回值会漏掉口径错误。</li>
 * </ul>
 */
class AiQueryServiceTest {

    private ProjectMapper projectMapper;
    private ProjectPhaseMapper phaseMapper;
    private ContractMapper contractMapper;
    private PaymentMapper paymentMapper;
    private AttachmentMapper attachmentMapper;
    private ContractLinkService contractLinkService;
    private StatsService statsService;
    private OperationLogService operationLogService;
    private AiQueryUsageTracker usageTracker;
    private AiQueryService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析需要 MyBatis-Plus 的 TableInfo 缓存（纯单测没有启动过程）
        TestTableInfoInitializer.init();
        projectMapper = mock(ProjectMapper.class);
        phaseMapper = mock(ProjectPhaseMapper.class);
        contractMapper = mock(ContractMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        contractLinkService = mock(ContractLinkService.class);
        statsService = mock(StatsService.class);
        operationLogService = mock(OperationLogService.class);
        usageTracker = new AiQueryUsageTracker();
        ProjectMetricsService metrics = new ProjectMetricsService(projectMapper, phaseMapper, paymentMapper,
                contractMapper, attachmentMapper, contractLinkService);
        service = new AiQueryService(projectMapper, contractMapper, paymentMapper, metrics, statsService,
                contractLinkService, operationLogService, usageTracker);
    }

    @AfterEach
    void tearDown() {
        AiQueryScopeContext.clear();
    }

    // ══════════════════════════════════════════════════════════════════
    // 固定数据集
    // ══════════════════════════════════════════════════════════════════

    /** 项目 1：预算 100 万；合同金额 50 万 + 变更 5 万；已付 12 万 + 8 万；"初验"完成(权重40) / "终验"进行中 50%(权重60)。 */
    private static Project p1() {
        Project p = new Project();
        p.setId(1L);
        p.setCode("YJ-2026-001");
        p.setName("智慧城市一期");
        p.setType("HW");
        p.setStatus("RUN");
        p.setBudgetAmount(new BigDecimal("1000000"));
        p.setContractAmount(new BigDecimal("500000"));
        p.setChangeAmount(new BigDecimal("50000"));
        p.setApproveDate(LocalDate.of(2026, 3, 1));
        return p;
    }

    /** 项目 2：用于验证"不给 projectId 时只返回 token 范围内的项目"。 */
    private static Project p2() {
        Project p = new Project();
        p.setId(2L);
        p.setCode("RJ-2026-002");
        p.setName("协同办公平台");
        p.setType("SW");
        p.setStatus("DONE");
        p.setBudgetAmount(new BigDecimal("200000"));
        p.setContractAmount(new BigDecimal("200000"));
        p.setChangeAmount(BigDecimal.ZERO);
        p.setApproveDate(LocalDate.of(2026, 5, 1));
        return p;
    }

    /** 项目 3：只用于 limit 用例。 */
    private static Project p3() {
        Project p = p2();
        p.setId(3L);
        p.setCode("RJ-2026-003");
        p.setName("数据治理平台");
        return p;
    }

    private static ProjectPhase phase(long id, long projectId, String name, String status,
                                      int percent, int weight, int sortNo) {
        ProjectPhase ph = new ProjectPhase();
        ph.setId(id);
        ph.setProjectId(projectId);
        ph.setPhaseName(name);
        ph.setStatus(status);
        ph.setPercent(percent);
        ph.setWeight(weight);
        ph.setSortNo(sortNo);
        return ph;
    }

    private static Payment payment(long id, long projectId, Long contractId, String nodeCode, String paid) {
        Payment p = new Payment();
        p.setId(id);
        p.setProjectId(projectId);
        p.setContractId(contractId);
        p.setNodeCode(nodeCode);
        p.setNodeName(nodeCode);
        p.setPlanAmount(new BigDecimal(paid));
        p.setPaidAmount(new BigDecimal(paid));
        p.setStatus("PAID");
        return p;
    }

    private static Contract contract(long id, String name, String no, String vendor) {
        Contract c = new Contract();
        c.setId(id);
        c.setName(name);
        c.setContractNo(no);
        c.setVendorName(vendor);
        c.setContractAmount(new BigDecimal("500000"));
        c.setChangeAmount(new BigDecimal("50000"));
        c.setContractStatus("EFFECTIVE");
        c.setSignDate(LocalDate.of(2026, 2, 1));
        return c;
    }

    private static Attachment attachment(long id, String bizType, long bizId, String name) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setBizType(bizType);
        a.setBizId(bizId);
        a.setFileName(name);
        return a;
    }

    /** 让 {@link AiQueryScopeContext} 处于"授权了这些项目"的状态（真实链路由 ScopeTokenFilter 写入）。 */
    private static void authorized(Long... projectIds) {
        AiQueryScopeContext.set(new AiQueryScope("jti-test", 9L, "张三", List.of(projectIds)));
    }

    private static AiQueryRequest request(Map<String, Object> filters, Integer limit) {
        AiQueryRequest req = new AiQueryRequest();
        req.setFilters(filters);
        req.setLimit(limit);
        return req;
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<LambdaQueryWrapper<T>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    /**
     * 打桩"项目查询"。
     *
     * <p>必须区分两种 {@code projectMapper.selectList}：
     * ① 受控查询自己的主体查询（{@code id IN (...)}）——按 scope 过滤数据集（mock 不执行 WHERE，
     *    所以这里替它过滤，才能验证"范围外项目不会出现"）；
     * ② {@link ProjectMetricsService} 为了算"容器项目 = 子项目之和"而查子项目（{@code parent_id = ?}）
     *    ——数据集里没有子项目，一律返回空；否则 mock 会把同一批数据当成子项目，
     *    让 contractAmount/paidAmount 无限递归（mocked mapper 的经典陷阱）。
     */
    private void stubProjects(List<Project> dataset) {
        when(projectMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<Project> wrapper = inv.getArgument(0);
            if (wrapper.getSqlSegment().contains("parent_id")) {
                return List.of();
            }
            Set<Long> ids = paramsOf(wrapper).values().stream()
                    .filter(v -> v instanceof Long)
                    .map(v -> (Long) v)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return dataset.stream().filter(p -> ids.contains(p.getId())).toList();
        });
    }

    /**
     * 读 {@code LambdaQueryWrapper} 的参数表。
     *
     * <p>⚠️ 必须先 {@code getSqlSegment()}：MyBatis-Plus 的条件片段是<b>惰性</b>求值的，
     * 求值时才把 {@code eq/in} 的值放进 {@code paramNameValuePairs}；
     * 直接读参数表会拿到空 Map（这正是"看起来没加条件"的假象）。
     */
    private static Map<String, Object> paramsOf(LambdaQueryWrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs();
    }

    /** 取出"主体查询"（{@code id IN (...)}）那一次调用，跳过子项目查询。 */
    private LambdaQueryWrapper<Project> captureMainProjectQuery() {
        ArgumentCaptor<LambdaQueryWrapper<Project>> captor = wrapperCaptor();
        verify(projectMapper, org.mockito.Mockito.atLeastOnce()).selectList(captor.capture());
        return captor.getAllValues().stream()
                .filter(w -> w.getSqlSegment().contains("id IN"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有捕获到主体项目查询"));
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 越权：403 且不泄漏任何数据
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 越权的projectId返回403且一个查询都不发() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class, () -> service.query("stats",
                request(Map.of("projectId", 99L, "kind", "phase_attachment_count"), 20)));

        assertEquals(403, e.getStatus());
        assertEquals("该项目不在本次授权范围（scope）内", e.getMessage());
        // 不泄漏任何数据：越权在"发查询之前"就被拒绝，一次 mapper 调用都不该发生
        verify(projectMapper, never()).selectList(any());
        verify(phaseMapper, never()).selectList(any());
        verify(attachmentMapper, never()).selectList(any());
        verify(contractMapper, never()).selectList(any());
        verify(paymentMapper, never()).selectList(any());
        // 越权尝试仍要留痕（审计要看得见"有人在问范围外的项目"）
        verify(operationLogService).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 越权的projectId传字符串时同样403() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("projects", request(Map.of("projectId", "99"), 20)));

        assertEquals(403, e.getStatus());
        verify(projectMapper, never()).selectList(any());
    }

    @Test
    void 没有作用域上下文时401() {
        // 过滤器没放行就走到业务层：宁可拒绝，也不能"当成没有范围限制"
        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("projects", request(Map.of(), 20)));

        assertEquals(401, e.getStatus());
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 合法查询：数字与项目详情/统计口径一致（固定数据集手算）
    // ══════════════════════════════════════════════════════════════════

    @Test
    void projects实体的数字与项目详情口径一致() {
        authorized(1L);
        stubProjects(List.of(p1()));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p1()));
        when(phaseMapper.selectList(any())).thenReturn(List.of(
                phase(101L, 1L, "初验", "DONE", 100, 40, 1),
                phase(102L, 1L, "终验", "IN_PROGRESS", 50, 60, 2)));
        // 阶段 101 有 2 个附件（已逻辑删除的那条由 @TableLogic 在 SQL 层被排除，mapper 不会返回它）
        when(attachmentMapper.selectList(any())).thenReturn(List.of(
                attachment(201L, "PROJECT_PHASE", 101L, "初验报告.pdf"),
                attachment(202L, "PROJECT_PHASE", 101L, "验收单.pdf")));
        when(paymentMapper.selectList(any())).thenReturn(List.of(
                payment(301L, 1L, 7L, "PREPAY", "120000"),
                payment(302L, 1L, 7L, "ARRIVAL", "80000")));
        when(contractLinkService.visibleContractIds(1L)).thenReturn(new LinkedHashSet<>(List.of(7L, 8L)));

        AiQueryData data = service.query("projects", request(Map.of("projectId", 1L), 20));

        assertEquals(1, data.getRows().size());
        Map<String, Object> row = data.getRows().get(0);
        assertEquals(1L, row.get("projectId"));
        assertEquals("智慧城市一期", row.get("name"));
        // 预算 = project.budget_amount
        assertEquals(new BigDecimal("1000000"), row.get("budgetAmount"));
        // 合同金额 = 合同金额 + 变更 = 500000 + 50000（未挂 contract_id 时取项目遗留合同列）
        assertEquals(new BigDecimal("550000"), row.get("contractAmount"));
        // 已付 = 付款实付合计 = 120000 + 80000
        assertEquals(new BigDecimal("200000"), row.get("paidAmount"));
        // 合同数 = 可见合同数（V12 关联表口径）
        assertEquals(2, row.get("contractCount"));
        // 进度 = (40×100 + 60×50%) / 100 = 70
        assertEquals(70, row.get("overallProgress"));
        // 当前阶段 = 第一个未开始/进行中的阶段
        assertEquals("终验", row.get("currentPhaseName"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phases = (List<Map<String, Object>>) row.get("phases");
        assertEquals(2, phases.size());
        assertEquals("初验", phases.get(0).get("phaseName"));
        assertEquals(2L, phases.get(0).get("attachmentCount"));
        assertEquals("终验", phases.get(1).get("phaseName"));
        assertEquals(0L, phases.get(1).get("attachmentCount"), "没有附件的阶段必须是 0，不能缺行");

        // 硬要求：口径/数据时间/范围三者都非空且可转述
        assertNotNull(data.getCaliber());
        assertTrue(data.getCaliber().contains("PROJECT_PHASE"), data.getCaliber());
        assertTrue(data.getCaliber().contains("不含合同附件"), data.getCaliber());
        assertTrue(data.getDataTime().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), data.getDataTime());
        assertTrue(data.getScope().contains("项目 1"), data.getScope());
        assertTrue(data.getScope().contains("智慧城市一期"), data.getScope());
        assertEquals("个", data.getUnit());
        // 有数据时不应出现空结果提示
        assertNull(data.getMessage());
    }

    @Test
    void 不给projectId时只查token范围内的项目() {
        authorized(2L);
        stubProjects(List.of(p1(), p2()));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p2()));
        when(phaseMapper.selectList(any())).thenReturn(List.of());
        when(paymentMapper.selectList(any())).thenReturn(List.of());
        when(contractLinkService.visibleContractIds(2L)).thenReturn(Set.of());

        AiQueryData data = service.query("projects", request(Map.of(), 20));

        // 断言查询被限定在 scope 内（而不是只看返回值）：token 只有 2L，SQL 参数里就必须只有 2L
        LambdaQueryWrapper<Project> main = captureMainProjectQuery();
        Map<String, Object> mainParams = paramsOf(main);
        assertTrue(mainParams.containsValue(2L),
                "查询必须带上 token 里的项目 id：" + mainParams);
        assertTrue(main.getSqlSegment().contains("id IN"), "查询必须按 id 收窄：" + main.getSqlSegment());
        assertEquals(1, data.getRows().size());
        assertEquals(2L, data.getRows().get(0).get("projectId"));
        assertTrue(data.getScope().contains("项目 2"), data.getScope());
        assertTrue(data.getScope().contains("协同办公平台"), data.getScope());
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ phase_attachment_count 的计数口径
    // ══════════════════════════════════════════════════════════════════

    @Test
    void phase_attachment_count只数阶段附件且排除逻辑删除() {
        authorized(1L);
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p1()));
        when(phaseMapper.selectList(any())).thenReturn(List.of(
                phase(101L, 1L, "初验", "DONE", 100, 40, 1),
                phase(102L, 1L, "终验", "IN_PROGRESS", 50, 60, 2),
                phase(103L, 1L, "质保", "NOT_STARTED", 0, 0, 3)));
        // 查出来的只有 PROJECT_PHASE 的两条：逻辑删除行由 @TableLogic 在 SQL 层排除，
        // 合同/付款/项目级附件因为 biz_type 不同也不会被这条查询取到
        when(attachmentMapper.selectList(any())).thenReturn(List.of(
                attachment(201L, "PROJECT_PHASE", 101L, "初验报告.pdf"),
                attachment(202L, "PROJECT_PHASE", 102L, "终验材料.pdf")));

        AiQueryData data = service.query("stats",
                request(Map.of("projectId", 1L, "kind", "phase_attachment_count"), 20));

        assertEquals(3, data.getRows().size(), "每个阶段一行");
        Map<String, Object> first = data.getRows().get(0);
        assertEquals(1L, first.get("projectId"));
        assertEquals("智慧城市一期", first.get("projectName"));
        assertEquals(101L, first.get("phaseId"));
        assertEquals("初验", first.get("phaseName"));
        assertEquals("DONE", first.get("status"));
        assertEquals(100, first.get("percent"));
        assertEquals(1L, first.get("attachmentCount"));
        assertEquals(1L, data.getRows().get(1).get("attachmentCount"));
        assertEquals(0L, data.getRows().get(2).get("attachmentCount"), "没有附件的阶段是 0（不是缺行）");
        assertEquals("个", data.getUnit());
        assertTrue(data.getCaliber().contains("PROJECT_PHASE"), data.getCaliber());
        assertTrue(data.getCaliber().contains("逻辑删除"), data.getCaliber());
        assertTrue(data.getCaliber().contains("不含合同附件"), data.getCaliber());

        // 查询形状：只按 biz_type=PROJECT_PHASE + 这些阶段 id 查，
        // 同一个 biz_id 上的合同/付款/项目级附件不可能被算进来
        ArgumentCaptor<LambdaQueryWrapper<Attachment>> captor = wrapperCaptor();
        verify(attachmentMapper).selectList(captor.capture());
        Map<String, Object> params = paramsOf(captor.getValue());
        assertTrue(params.containsValue("PROJECT_PHASE"), "必须限定 biz_type=PROJECT_PHASE：" + params);
        assertTrue(params.containsValue(101L) && params.containsValue(102L) && params.containsValue(103L),
                "必须限定到这些阶段 id：" + params);
        assertTrue(captor.getValue().getSqlSegment().contains("biz_type"), captor.getValue().getSqlSegment());
        assertTrue(captor.getValue().getSqlSegment().contains("biz_id"), captor.getValue().getSqlSegment());
    }

    @Test
    void 阶段附件计数依赖TableLogic排除已删附件() throws Exception {
        // mocked mapper 不执行 SQL，没法直接验证 deleted=0 的条件；这里钉住"排除逻辑删除"的
        // <b>实现方式</b>：Attachment.deleted 上有 @TableLogic，MyBatis-Plus 会自动追加 deleted=0。
        // 谁把该注解摘掉，本用例立刻变红。
        java.lang.reflect.Field deleted = Attachment.class.getDeclaredField("deleted");
        assertNotNull(deleted.getAnnotation(com.baomidou.mybatisplus.annotation.TableLogic.class),
                "Attachment.deleted 必须是 @TableLogic，否则已删除的附件会被计入阶段附件数");
    }

    // ══════════════════════════════════════════════════════════════════
    // ④ filters 非法 → 400 且提示里含支持字段
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 非法字段返回400并列出该entity支持的字段() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class, () -> service.query("stats",
                request(Map.of("projectId", 1L, "sql", "select * from project"), 20)));

        assertEquals(400, e.getStatus());
        String msg = e.getMessage();
        assertTrue(msg.contains("sql"), "要指出被拒绝的字段：" + msg);
        assertTrue(msg.contains("不支持查询字段"), msg);
        assertTrue(msg.contains("不接受 SQL"), msg);
        // 必须教会模型：支持字段 + 可选值 + 可照抄的示例
        assertTrue(msg.contains("projectId"), msg);
        assertTrue(msg.contains("kind"), msg);
        assertTrue(msg.contains("phase_attachment_count"), msg);
        assertTrue(msg.contains("示例"), msg);
        verify(projectMapper, never()).selectList(any());
    }

    @Test
    void 枚举取值非法返回400并列出可选值() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("projects", request(Map.of("status", "RUNNING"), 20)));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("取值非法"), e.getMessage());
        assertTrue(e.getMessage().contains("RUN"), e.getMessage());
        assertTrue(e.getMessage().contains("DONE"), e.getMessage());
    }

    @Test
    void 整数字段传非数字返回400() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("projects", request(Map.of("year", "今年"), 20)));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("必须是整数"), e.getMessage());
    }

    @Test
    void 嵌套对象字段返回400() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("projects", request(Map.of("name", Map.of("like", "城市")), 20)));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("不能是对象或数组"), e.getMessage());
    }

    @Test
    void stats缺少必填kind返回400() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("stats", request(Map.of("projectId", 1L), 20)));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("必须提供字段 kind"), e.getMessage());
        assertTrue(e.getMessage().contains("phase_attachment_count"), e.getMessage());
    }

    @Test
    void 不支持的entity返回400并列出四个实体() {
        authorized(1L);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("users", request(Map.of(), 20)));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("projects"), e.getMessage());
        assertTrue(e.getMessage().contains("contracts"), e.getMessage());
        assertTrue(e.getMessage().contains("payments"), e.getMessage());
        assertTrue(e.getMessage().contains("stats"), e.getMessage());
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑤ 空结果 → 200 + rows:[]（不是 404）
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 空结果是200加空数组而不是404() {
        authorized(1L);
        when(projectMapper.selectList(any())).thenReturn(List.of());

        AiQueryData data = service.query("projects", request(Map.of("name", "不存在的项目"), 20));

        assertTrue(data.getRows().isEmpty(), "空结果必须是空数组");
        assertEquals(AiQueryData.EMPTY_MESSAGE, data.getMessage(), "要能让模型区分『没数据』与『调用失败』");
        assertEquals("个", data.getUnit());
        assertNotNull(data.getCaliber());
        assertNotNull(data.getDataTime());
        assertNotNull(data.getScope());
    }

    @Test
    void 授权范围内没有项目时返回空数组且不查库() {
        AiQueryScopeContext.set(new AiQueryScope("jti-test", 9L, "张三", List.of()));

        AiQueryData data = service.query("stats", request(Map.of("kind", "phase_attachment_count"), 20));

        assertTrue(data.getRows().isEmpty());
        assertEquals(AiQueryData.EMPTY_MESSAGE, data.getMessage());
        assertTrue(data.getScope().contains("无"), data.getScope());
        verify(projectMapper, never()).selectList(any());
        verify(statsService, never()).distributions(any());
    }

    // ══════════════════════════════════════════════════════════════════
    // 其余实体（合同 / 付款 / 统计）、limit、留痕与用量计数
    // ══════════════════════════════════════════════════════════════════

    @Test
    void contracts只查范围内的可见合同() {
        authorized(1L);
        when(contractLinkService.visibleContractIds(1L)).thenReturn(new LinkedHashSet<>(List.of(7L)));
        when(contractMapper.selectList(any())).thenReturn(List.of(contract(7L, "施工合同", "HT-001", "某某公司")));
        when(contractLinkService.projectIdsOfContract(7L)).thenReturn(List.of(1L));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p1()));

        AiQueryData data = service.query("contracts", request(Map.of("projectId", 1L), 20));

        assertEquals(1, data.getRows().size());
        assertEquals("施工合同", data.getRows().get(0).get("name"));
        assertEquals(new BigDecimal("500000"), data.getRows().get(0).get("contractAmount"));
        assertEquals(new BigDecimal("50000"), data.getRows().get(0).get("changeAmount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) data.getRows().get(0).get("projects");
        assertEquals(1, projects.size());
        assertEquals("智慧城市一期", projects.get(0).get("projectName"));

        ArgumentCaptor<LambdaQueryWrapper<Contract>> captor = wrapperCaptor();
        verify(contractMapper).selectList(captor.capture());
        assertTrue(paramsOf(captor.getValue()).containsValue(7L),
                "只能查可见合同：" + paramsOf(captor.getValue()));
    }

    @Test
    void payments按项目与可见合同链查询() {
        authorized(1L);
        when(contractLinkService.visibleContractIds(1L)).thenReturn(new LinkedHashSet<>(List.of(7L)));
        when(paymentMapper.selectList(any())).thenReturn(List.of(payment(301L, 1L, 7L, "PREPAY", "120000")));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p1()));
        when(contractMapper.selectBatchIds(any())).thenReturn(List.of(contract(7L, "施工合同", "HT-001", "某某公司")));

        AiQueryData data = service.query("payments", request(Map.of("projectId", 1L, "status", "PAID"), 20));

        assertEquals(1, data.getRows().size());
        assertEquals("PREPAY", data.getRows().get(0).get("nodeCode"));
        assertEquals(new BigDecimal("120000"), data.getRows().get(0).get("paidAmount"));
        assertEquals("施工合同", data.getRows().get(0).get("contractName"));
        assertEquals("个", data.getUnit());

        ArgumentCaptor<LambdaQueryWrapper<Payment>> captor = wrapperCaptor();
        verify(paymentMapper).selectList(captor.capture());
        Map<String, Object> params = paramsOf(captor.getValue());
        assertTrue(params.containsValue(1L), "必须限定项目：" + params);
        assertTrue(params.containsValue(7L), "必须限定可见合同链：" + params);
        assertTrue(params.containsValue("PAID"), "状态过滤必须下发：" + params);
    }

    @Test
    void stats的分布与年度资金复用统计服务并传入范围() {
        authorized(1L, 2L);
        when(statsService.distributions(any())).thenReturn(Map.of(
                "type", List.of(Map.of("name", "HW", "value", 1L), Map.of("name", "SW", "value", 1L))));
        Map<String, Object> yearResult = new LinkedHashMap<>();
        yearResult.put("rows", List.of(Map.of("year", 2026, "budget", new BigDecimal("1200000"),
                "contract", new BigDecimal("700000"), "paid", new BigDecimal("200000"))));
        when(statsService.yearMoney(any())).thenReturn(yearResult);

        AiQueryData distribution = service.query("stats", request(Map.of("kind", "type_distribution"), 20));
        assertEquals(2, distribution.getRows().size());
        assertEquals("HW", distribution.getRows().get(0).get("name"));
        assertEquals("type", distribution.getRows().get(0).get("dimension"));
        assertEquals("个", distribution.getUnit());

        AiQueryData money = service.query("stats", request(Map.of("kind", "year_amount"), 20));
        assertEquals(1, money.getRows().size());
        assertEquals(2026, money.getRows().get(0).get("year"));
        assertEquals(new BigDecimal("700000"), money.getRows().get(0).get("contract"));
        assertEquals("元", money.getUnit());

        // 复用既有统计服务（而不是另写一套聚合），并且必须把范围收窄传下去
        ArgumentCaptor<StatsQuery> captor = ArgumentCaptor.forClass(StatsQuery.class);
        verify(statsService).distributions(captor.capture());
        verify(statsService).yearMoney(captor.capture());
        for (StatsQuery q : captor.getAllValues()) {
            assertEquals(List.of(1L, 2L), q.getProjectIds(), "统计必须收窄到 scope 内的项目");
        }
    }

    @Test
    void limit超上限时行数被夹到100且不超过请求值() {
        authorized(1L, 2L, 3L);
        stubProjects(List.of(p1(), p2(), p3()));
        when(phaseMapper.selectList(any())).thenReturn(List.of());
        when(paymentMapper.selectList(any())).thenReturn(List.of());
        when(contractLinkService.visibleContractIds(any())).thenReturn(Set.of());

        assertEquals(2, service.query("projects", request(Map.of(), 2)).getRows().size());
        assertEquals(3, service.query("projects", request(Map.of(), 1000)).getRows().size(),
                "上限 100 只是夹住请求值；实际只有 3 条数据");
        assertEquals(3, service.query("projects", request(Map.of(), null)).getRows().size(),
                "缺省 limit=20");
        assertEquals(AiQueryService.MAX_LIMIT, AiQueryService.normalizeLimit(1000));
        assertEquals(AiQueryService.DEFAULT_LIMIT, AiQueryService.normalizeLimit(0));
        assertEquals(20, AiQueryService.normalizeLimit(20));
    }

    @Test
    void 每次查询都写operate_log并计入本次问答用量() {
        AiQueryScopeContext.set(new AiQueryScope("jti-abc", 9L, "张三", List.of(1L)));
        when(projectMapper.selectList(any())).thenReturn(List.of());

        service.query("projects", request(Map.of("projectId", 1L), 20));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).log(eq("PROJECT"), eq(1L), eq("AI_QUERY_PROJECTS"), detail.capture(),
                eq(9L), eq("张三"));
        assertTrue(detail.getValue().contains("entity=projects"), detail.getValue());
        assertTrue(detail.getValue().contains("projectId=1"), detail.getValue());
        assertTrue(detail.getValue().contains("返回 0 行"), detail.getValue());
        assertTrue(detail.getValue().contains("耗时"), detail.getValue());

        AiQueryUsageTracker.Snapshot usage = usageTracker.snapshot("jti-abc");
        assertEquals(1, usage.count());
        assertEquals(List.of("projects"), usage.entities());
    }

    @Test
    void 被拒绝的查询也留痕并计数() {
        AiQueryScopeContext.set(new AiQueryScope("jti-rej", 9L, "张三", List.of(1L)));

        assertThrows(AiQueryException.class, () -> service.query("stats",
                request(Map.of("projectId", 99L, "kind", "phase_attachment_count"), 20)));

        verify(operationLogService).log(any(), any(), eq("AI_QUERY_STATS"), any(), any(), any());
        assertEquals(1, usageTracker.snapshot("jti-rej").count(), "越权试探也要计一次系统查询尝试");
    }

    @Test
    void 查询执行异常返回500而不是被当成空结果() {
        // §11.6 红线：数据库挂了绝不能回 rows:[]，否则模型会如实地说"系统里没有"，
        // 把"系统坏了"说成"确实没有数据"。必须是 5xx，让调用方按"调用失败"处理。
        authorized(1L);
        when(projectMapper.selectList(any())).thenThrow(new RuntimeException("ORA-01653 表空间不足"));

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service.query("projects", request(Map.of("projectId", 1L), 20)));

        assertEquals(500, e.getStatus());
        assertTrue(e.getMessage().contains("没有取到任何业务数据"), e.getMessage());
        assertFalse(e.getMessage().contains("ORA-"), "内部错误细节只进日志，不回给调用方：" + e.getMessage());
        verify(operationLogService).log(any(), any(), eq("AI_QUERY_PROJECTS"), any(), any(), any());
        assertEquals(1, usageTracker.snapshot("jti-test").count());
    }

    @Test
    void 范围外项目多时operate_log不落单项目日志() {
        // bizType=PROJECT 只用于"能明确归到一个项目"的查询：多项目范围记 AI，
        // 免得某个项目的操作日志里混进别人的查询
        AiQueryScopeContext.set(new AiQueryScope("jti-multi", 9L, "张三", List.of(1L, 2L)));
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(phaseMapper.selectList(any())).thenReturn(List.of());
        when(paymentMapper.selectList(any())).thenReturn(List.of());
        when(contractLinkService.visibleContractIds(any())).thenReturn(Set.of());

        service.query("projects", request(Map.of("status", "RUN"), 20));

        verify(operationLogService).log(eq("AI"), org.mockito.ArgumentMatchers.isNull(),
                eq("AI_QUERY_PROJECTS"), any(), eq(9L), eq("张三"));
    }

    @Test
    void 同一次问答的多次查询累计用量() {
        AiQueryScopeContext.set(new AiQueryScope("jti-2", 9L, "张三", List.of(1L)));
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p1()));
        when(phaseMapper.selectList(any())).thenReturn(List.of(phase(101L, 1L, "初验", "DONE", 100, 40, 1)));
        when(attachmentMapper.selectList(any())).thenReturn(List.of());

        service.query("projects", request(Map.of("projectId", 1L), 20));
        service.query("stats", request(Map.of("projectId", 1L, "kind", "phase_attachment_count"), 20));
        service.query("stats", request(Map.of("projectId", 1L, "kind", "phase_attachment_count"), 20));

        AiQueryUsageTracker.Snapshot usage = usageTracker.snapshot("jti-2");
        assertEquals(3, usage.count());
        assertEquals("projects,stats", usage.entitiesText(), "entity 去重且保持首次出现顺序");
    }
}
