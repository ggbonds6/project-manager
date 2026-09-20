package com.pmgt.module.ai.service;

import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiDocumentVO;
import com.pmgt.module.ai.dto.AiPageVO;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.project.service.ContractLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiDocumentService} 的列表映射测试（§9 #2）。
 *
 * <p>重点覆盖一条容易被忽略的约定：{@code keyword} 必须<b>同时</b>匹配
 * {@code filename} 与 {@code docId}——前端在引用里拿不到 attachmentId 时，
 * 会用 {@code keyword=<docId>} 反查一次文档列表作为兜底。
 */
class AiDocumentServiceTest {

    private AiServiceClient ai;
    private AttachmentMapper attachmentMapper;
    private ProjectMapper projectMapper;
    private AiScopeResolver scopeResolver;
    private AiDocumentService documentService;

    @BeforeEach
    void setUp() {
        AiProperties props = new AiProperties();
        props.setEnabled(true);
        ai = mock(AiServiceClient.class);
        attachmentMapper = mock(AttachmentMapper.class);
        projectMapper = mock(ProjectMapper.class);
        // 用真实的 resolver（只 mock 底层依赖），把「附件→项目」的换算一起测到
        scopeResolver = new AiScopeResolver(attachmentMapper, mock(AttachmentAiTaskMapper.class),
                projectMapper, mock(ProjectPhaseMapper.class), mock(PaymentMapper.class),
                mock(ContractLinkService.class));
        AiTaskService taskService = mock(AiTaskService.class);
        // mock 的返回值默认是 null，而调用方直接对它查表 → 显式给空 Map
        when(taskService.latestTaskByAttachment(any())).thenReturn(Map.of());
        documentService = new AiDocumentService(props, ai, attachmentMapper, scopeResolver,
                taskService, mock(OperationLogService.class));
    }

    private static Map<String, Object> doc(String docId, String filename, int pages, int chars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc_id", docId);
        m.put("filename", filename);
        m.put("pages", pages);
        m.put("chars", chars);
        m.put("size_bytes", 20480);
        m.put("uploaded_at", "2026-09-20T10:11:12");
        return m;
    }

    /** 打桩：AI 侧两份文档，主系统侧只有第一份有附件关联。 */
    private void givenDocs() {
        when(ai.listDocuments()).thenReturn(List.of(
                doc("aaa111", "中标通知书.pdf", 6, 3600),
                doc("bbb222", "付款凭证.pdf", 1, 120)));
        Attachment a = new Attachment();
        a.setId(11L);
        a.setFileName("中标通知书.pdf");
        a.setAiDocId("aaa111");
        a.setBizType("PROJECT");
        a.setBizId(1L);
        a.setAiIndexStatus("READY");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(a));
        // 项目名走真实的 resolver.projectNames()：把"附件→项目"的换算也一起测到，
        // 所以这里只需要打桩 ProjectMapper
        Project project = new Project();
        project.setId(1L);
        project.setName("项目一");
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project));
    }

    @Test
    void 列表用docId反查附件补齐项目与附件id() {
        givenDocs();

        AiPageVO<AiDocumentVO> page = documentService.list(null, null, 1, 20);

        assertEquals(2, page.getTotal());
        AiDocumentVO first = page.getRecords().get(0);
        assertEquals("aaa111", first.getDocId());
        assertEquals(11L, first.getAttachmentId());
        assertEquals("中标通知书.pdf", first.getFilename());
        assertEquals(6, first.getPageCount());
        // ISO → yyyy-MM-dd HH:mm:ss（§9 的时间格式）
        assertEquals("2026-09-20 10:11:12", first.getIndexedAt());
        assertEquals("READY", first.getIndexStatus());
        assertEquals("项目一", first.getProjectName());
        // 3600 字 / 6 页 = 每页 600 字 → 每页 2 片 → 12 片（估算）
        assertEquals(12, first.getChunkCount());

        // AI 侧有、主系统侧没有关联附件的文档照样要展示（管理页需要看到这种不一致）
        AiDocumentVO second = page.getRecords().get(1);
        assertEquals("bbb222", second.getDocId());
        assertEquals(null, second.getAttachmentId());
        assertEquals(null, second.getProjectId());
    }

    @Test
    void keyword同时匹配文件名与docId() {
        givenDocs();

        // 文件名子串
        assertEquals(1, documentService.list("通知书", null, 1, 20).getTotal());
        // doc_id 子串（前端兜底路径：用 keyword=<docId> 反查 attachmentId）
        AiPageVO<AiDocumentVO> byDocId = documentService.list("bbb222", null, 1, 20);
        assertEquals(1, byDocId.getTotal());
        // 大小写不敏感
        assertEquals(1, documentService.list("BBB222", null, 1, 20).getTotal());
        // 匹配不到就是空
        assertTrue(documentService.list("不存在的东西", null, 1, 20).getRecords().isEmpty());
    }

    @Test
    void 按项目过滤时排除无法证明归属的文档() {
        givenDocs();

        AiPageVO<AiDocumentVO> page = documentService.list(null, 1L, 1, 20);

        // bbb222 在 AI 侧存在但主系统侧关联不到项目，按项目筛时不能展示
        assertEquals(1, page.getTotal());
        assertEquals("aaa111", page.getRecords().get(0).getDocId());
    }

    @Test
    void 分页按page与size切片() {
        givenDocs();

        AiPageVO<AiDocumentVO> page2 = documentService.list(null, null, 2, 1);

        assertEquals("bbb222", page2.getRecords().get(0).getDocId());
        // total 是过滤后的总数，不是当前页条数
        assertEquals(2, page2.getTotal());
    }

    @Test
    void 前端兜底路径用size500拉全量时不会被截断() {
        givenDocs();

        // 前端"引用→附件"兜底会传 size=500（loadAllDocuments），上传上限必须≥500，
        // 否则附件多了以后引用页会静默查不到对应附件
        AiPageVO<AiDocumentVO> page = documentService.list(null, null, 1, 500);

        assertEquals(2, page.getTotal());
        assertEquals(2, page.getRecords().size());
    }

    @Test
    void 开关关闭时返回空列表而不是报错() {
        AiProperties off = new AiProperties();
        off.setEnabled(false);
        AiDocumentService disabled = new AiDocumentService(off, ai, attachmentMapper, scopeResolver,
                mock(AiTaskService.class), mock(OperationLogService.class));

        AiPageVO<AiDocumentVO> page = disabled.list(null, null, 1, 20);

        assertTrue(page.getRecords().isEmpty());
        assertEquals(0, page.getTotal());
    }
}
