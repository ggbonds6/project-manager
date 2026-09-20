package com.pmgt.module.ai.service;

import com.pmgt.common.exception.BizException;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.project.service.ContractLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiScopeResolver} 的权限与作用域测试。
 *
 * <p>这是本次集成里最该被测试钉死的一段：它决定了「用户能问哪些文档」。
 * 重点覆盖三条：
 * <ol>
 *   <li>不可访问 / 不存在的附件 id <b>被拒绝</b>（不是静默过滤）；</li>
 *   <li>未解析入库的附件被点名时给出可操作的错误；</li>
 *   <li>换算出的 docIds 只包含允许的附件，且绝不返回「空 → 检索全部」的语义。</li>
 * </ol>
 */
class AiScopeResolverTest {

    private AttachmentMapper attachmentMapper;
    private AttachmentAiTaskMapper taskMapper;
    private ProjectMapper projectMapper;
    private ProjectPhaseMapper phaseMapper;
    private PaymentMapper paymentMapper;
    private ContractLinkService contractLinkService;
    private AiScopeResolver resolver;

    @BeforeEach
    void setUp() {
        attachmentMapper = mock(AttachmentMapper.class);
        taskMapper = mock(AttachmentAiTaskMapper.class);
        projectMapper = mock(ProjectMapper.class);
        phaseMapper = mock(ProjectPhaseMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        contractLinkService = mock(ContractLinkService.class);
        resolver = new AiScopeResolver(attachmentMapper, taskMapper, projectMapper,
                phaseMapper, paymentMapper, contractLinkService);
    }

    private static Attachment attachment(long id, String name, String status, String docId) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setFileName(name);
        a.setAiIndexStatus(status);
        a.setAiDocId(docId);
        a.setBizType("PROJECT");
        a.setBizId(1L);
        return a;
    }

    @Test
    void 点名的不可访问附件id被拒绝而不是静默过滤() {
        // 数据库里只有 1 号附件，前端却要问 99 号
        when(attachmentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                attachment(1L, "可检索.pdf", AiScopeResolver.STATUS_READY, "d1")));

        AiScopeDeniedException e = assertThrows(AiScopeDeniedException.class,
                () -> resolver.resolveForChat(null, List.of(1L, 99L)));

        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("99"), "错误里要指出被拒绝的 id：" + e.getMessage());
        assertTrue(e.getMessage().contains("不存在或已删除"), e.getMessage());
    }

    @Test
    void 点名未解析的附件被拒绝并给出可操作提示() {
        when(attachmentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                attachment(5L, "未解析.pdf", AiScopeResolver.STATUS_NOT_PARSED, null)));

        AiScopeDeniedException e = assertThrows(AiScopeDeniedException.class,
                () -> resolver.resolveForChat(null, List.of(5L)));

        assertTrue(e.getMessage().contains("尚未解析入库"), e.getMessage());
        assertTrue(e.getMessage().contains("已可检索"), "要告诉用户下一步怎么做：" + e.getMessage());
    }

    @Test
    void 解析失败的附件被拒绝并提示先重试() {
        when(attachmentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                attachment(6L, "失败.pdf", AiScopeResolver.STATUS_FAILED, null)));

        AiScopeDeniedException e = assertThrows(AiScopeDeniedException.class,
                () -> resolver.resolveForChat(null, List.of(6L)));

        assertTrue(e.getMessage().contains("重试解析"), e.getMessage());
    }

    @Test
    void 已可检索的附件换算成docIds() {
        when(attachmentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                attachment(1L, "a.pdf", AiScopeResolver.STATUS_READY, "docA"),
                attachment(2L, "b.pdf", AiScopeResolver.STATUS_READY, "docB")));

        AiScopeResolver.ChatScope scope = resolver.resolveForChat(null, List.of(1L, 2L));

        assertEquals(List.of("docA", "docB"), scope.docIds());
        assertEquals(List.of(1L, 2L), scope.allowedAttachmentIds());
        assertEquals(List.of(1L, 2L), scope.requestedAttachmentIds());
    }

    @Test
    void 项目作用域下附件不属于该项目时拒绝() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "项目一"));
        Attachment other = attachment(3L, "别的项目.pdf", AiScopeResolver.STATUS_READY, "docC");
        other.setBizType("PROJECT");
        other.setBizId(2L);
        when(attachmentMapper.selectBatchIds(anyList())).thenReturn(List.of(other));

        AiScopeDeniedException e = assertThrows(AiScopeDeniedException.class,
                () -> resolver.resolveForChat(1L, List.of(3L)));

        assertTrue(e.getMessage().contains("不属于项目 1"), e.getMessage());
    }

    @Test
    void 项目不存在时报404() {
        when(projectMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> resolver.resolveForChat(404L, null));

        assertEquals(404, e.getCode());
        verify(attachmentMapper, never()).selectBatchIds(anyList());
    }

    @Test
    void 项目范围内静默跳过未解析附件而不是拒绝() {
        // 项目里既有已可检索的、也有没解析的：全项目提问不该因为后者而失败
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "项目一"));
        when(attachmentMapper.selectList(any())).thenReturn(List.of(
                attachment(1L, "已入库.pdf", AiScopeResolver.STATUS_READY, "docA"),
                attachment(2L, "没解析.pdf", AiScopeResolver.STATUS_NOT_PARSED, null)));

        AiScopeResolver.ChatScope scope = resolver.resolveForChat(1L, null);

        assertEquals(List.of("docA"), scope.docIds());
        assertEquals(List.of(1L), scope.allowedAttachmentIds());
        assertEquals("项目一", scope.projectName());
    }

    @Test
    void 无已可检索附件时docIds为空且不回落全库() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "项目一"));
        when(attachmentMapper.selectList(any())).thenReturn(List.of(
                attachment(2L, "没解析.pdf", AiScopeResolver.STATUS_NOT_PARSED, null)));

        AiScopeResolver.ChatScope scope = resolver.resolveForChat(1L, null);

        assertTrue(scope.docIds().isEmpty(), "空范围必须显式为空，绝不能变成『检索全部』");
    }

    @Test
    void 状态查询里不存在的附件id被拒绝() {
        when(attachmentMapper.selectBatchIds(anyList())).thenReturn(List.of(
                attachment(1L, "a.pdf", AiScopeResolver.STATUS_READY, "docA")));

        AiScopeDeniedException e = assertThrows(AiScopeDeniedException.class,
                () -> resolver.requireAccessibleAll(List.of(1L, 2L)));

        assertTrue(e.getMessage().contains("附件 2 不存在或已删除"), e.getMessage());
    }

    @Test
    void 合同附件的项目归属可解析() {
        Attachment a = attachment(9L, "合同.pdf", AiScopeResolver.STATUS_READY, "docX");
        a.setBizType("CONTRACT");
        a.setBizId(30L);
        // V12 起「合同挂哪些项目」以关联表为准，由 ContractLinkService 回答
        when(contractLinkService.projectIdsOfContract(30L)).thenReturn(List.of(7L));

        assertEquals(7L, resolver.projectIdOf(a));
    }

    private static Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }
}
