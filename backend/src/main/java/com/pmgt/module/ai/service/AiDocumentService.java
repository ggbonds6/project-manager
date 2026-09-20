package com.pmgt.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pmgt.common.security.AuthContext;
import com.pmgt.module.ai.client.AiJson;
import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiDocumentVO;
import com.pmgt.module.ai.dto.AiPageVO;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 文档库（§9 #2 列表 / #3 删除）。
 *
 * <h2>两个数据源怎么合</h2>
 * 「有哪些文档」的<b>事实源在 AI 服务</b>（切片与向量只在它那边），
 * 而「这个文档对应主系统的哪个附件、哪个项目、谁能看」在主系统。
 * 于是列表 = 以 AI 服务的文档列表为骨架，用 {@code doc_id} 反查
 * {@code attachment.ai_doc_id} 补齐业务字段。
 *
 * <h2>为什么不把主系统侧 READY 但 AI 服务没有的文档也伪造出来</h2>
 * 那等于在主系统里维护一份「假文档」列表，用户点进去必然报错。
 * 反过来（AI 有、主系统没关联附件）要展示——那是真实存在的可检索文档，
 * 只是关联信息缺失（历史数据或手工入库），管理页正需要看到这种不一致。
 */
@Service
public class AiDocumentService {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** AI 服务的切片目标字数（{@code DocStore.DEFAULT_CHUNK_CHARS}），用于估算切片数。 */
    private static final int CHUNK_CHARS = 500;

    private final AiProperties props;
    private final AiServiceClient ai;
    private final AttachmentMapper attachmentMapper;
    private final AiScopeResolver scopeResolver;
    private final AiTaskService taskService;
    private final OperationLogService operationLogService;

    public AiDocumentService(AiProperties props,
                             AiServiceClient ai,
                             AttachmentMapper attachmentMapper,
                             AiScopeResolver scopeResolver,
                             AiTaskService taskService,
                             OperationLogService operationLogService) {
        this.props = props;
        this.ai = ai;
        this.attachmentMapper = attachmentMapper;
        this.scopeResolver = scopeResolver;
        this.taskService = taskService;
        this.operationLogService = operationLogService;
    }

    /**
     * §9 #2 文档库列表。
     *
     * <p>{@code keyword} <b>同时匹配 {@code filename} 与 {@code docId} 的子串</b>（大小写不敏感）：
     * 前端在引用里拿不到 {@code attachmentId} 时会用 {@code keyword=<docId>} 反查一次列表来兜底，
     * 若这里只匹配文件名，该兜底路径永远查不到（这是刻意保留的兼容点，不是巧合）。
     */
    public AiPageVO<AiDocumentVO> list(String keyword, Long projectId, int page, int size) {
        if (!props.isEnabled()) {
            // 关闭开关时给空列表而不是报错：管理页/附件页会调它，报错会让整个页面变红
            return new AiPageVO<>(List.of());
        }
        List<Map<String, Object>> docs = ai.listDocuments();

        // 主系统侧的关联信息：按 doc_id 反查附件
        Map<String, Attachment> byDocId = new LinkedHashMap<>();
        Map<Long, AttachmentAiTask> latestTask = new LinkedHashMap<>();
        if (!docs.isEmpty()) {
            List<String> docIds = docs.stream().map(d -> AiJson.text(d, "doc_id"))
                    .filter(StringUtils::hasText).distinct().toList();
            if (!docIds.isEmpty()) {
                List<Attachment> linked = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                        .in(Attachment::getAiDocId, docIds));
                for (Attachment a : linked) {
                    byDocId.putIfAbsent(a.getAiDocId(), a);
                }
            }
            // 失败原因来自任务表（文档条目本身不记录上次为什么失败）
            latestTask = taskService.latestTaskByAttachment(
                    byDocId.values().stream().map(Attachment::getId).toList());
        }

        Set<Long> projectIds = new LinkedHashSet<>();
        Map<String, Long> projectIdByDoc = new LinkedHashMap<>();
        for (Map.Entry<String, Attachment> e : byDocId.entrySet()) {
            Long pid = scopeResolver.projectIdOf(e.getValue());
            if (pid != null) {
                projectIds.add(pid);
                projectIdByDoc.put(e.getKey(), pid);
            }
        }
        Map<Long, String> projectNames = scopeResolver.projectNames(projectIds);

        List<AiDocumentVO> records = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            String docId = AiJson.text(doc, "doc_id");
            Attachment att = byDocId.get(docId);
            Long pid = projectIdByDoc.get(docId);
            // 项目过滤：关联不到项目的文档在「按项目筛」时不展示（无法证明它属于该项目）
            if (projectId != null && !projectId.equals(pid)) {
                continue;
            }
            if (!matchesKeyword(keyword, docId, AiJson.text(doc, "filename"))) {
                continue;
            }
            records.add(toVO(doc, att, pid, projectNames.get(pid), latestTask));
        }
        // total 必须是「过滤后的总数」，不是当前页条数：前端分页组件要靠它算总页数
        return AiPageVO.of(records.size(), slice(records, page, size));
    }

    /**
     * §9 #3 删除文档（ADMIN/MANAGER）。
     *
     * <p>先校验主系统侧的可访问性：{@code docId} 若对应一个主系统附件，
     * 就必须（且只能）由能访问该附件的人删；主系统里没有任何附件引用该 docId 时
     * 视为清理孤儿文档（例如附件已被删、或人工入库的调试文档），允许管理员清理。
     */
    @Transactional
    public void delete(String docId) {
        if (!props.isEnabled()) {
            throw new com.pmgt.common.exception.BizException(503, props.disabledReason() + "，无法删除文档");
        }
        if (!StringUtils.hasText(docId)) {
            throw new com.pmgt.common.exception.BizException(400, "docId 不能为空");
        }
        Attachment att = attachmentMapper.selectOne(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getAiDocId, docId)
                .orderByDesc(Attachment::getId)
                .last("FETCH FIRST 1 ROW ONLY"));
        if (att != null && scopeResolver.projectIdOf(att) == null) {
            // 关联到了附件但算不出项目：说明归属数据异常，此时宁可拒绝也不要盲删
            throw new AiScopeDeniedException("文档 " + docId + " 关联的附件归属异常，无法确认权限，已拒绝删除");
        }

        ai.deleteDocument(docId);

        if (att != null) {
            // 回写附件状态：doc_id 已失效，不能继续显示"已可检索"
            attachmentMapper.update(null, new LambdaUpdateWrapper<Attachment>()
                    .eq(Attachment::getId, att.getId())
                    .set(Attachment::getAiIndexStatus, AiScopeResolver.STATUS_NOT_PARSED)
                    .set(Attachment::getAiDocId, null)
                    .set(Attachment::getAiIndexedAt, null));
            operationLogService.log("PROJECT", scopeResolver.projectIdOf(att), "AI_DOC_DELETE",
                    "删除 AI 文档「" + att.getFileName() + "」(docId=" + docId + ")");
        } else {
            operationLogService.log("AI", null, "AI_DOC_DELETE", "删除无附件关联的 AI 文档 docId=" + docId);
            log.info("[ai] 删除孤儿文档 docId={} operator={}", docId, AuthContext.userName());
        }
    }

    private AiDocumentVO toVO(Map<String, Object> doc, Attachment att, Long projectId, String projectName,
                              Map<Long, AttachmentAiTask> latestTask) {
        AiDocumentVO vo = new AiDocumentVO();
        vo.setDocId(AiJson.text(doc, "doc_id"));
        vo.setFilename(AiJson.nullableText(doc, "filename"));
        vo.setAttachmentId(att == null ? null : att.getId());
        vo.setProjectId(projectId);
        // 项目名找不到（项目已删）时退回"未归属"，不要让前端显示 null 字符串
        vo.setProjectName(projectName == null && projectId != null ? "未知项目" : projectName);
        vo.setPageCount(AiJson.intValue(doc, "pages", 0));
        vo.setSizeBytes(AiJson.longValue(doc, "size_bytes", 0L));
        vo.setIndexedAt(formatUploadedAt(AiJson.text(doc, "uploaded_at")));
        // 状态以主系统为准（附件表）；没有关联附件时按 AI 服务"能列出即已入库"记为 READY
        vo.setIndexStatus(att == null ? AiScopeResolver.STATUS_READY : att.getAiIndexStatus());
        vo.setChunkCount(estimateChunks(AiJson.intValue(doc, "chars", 0), vo.getPageCount()));
        if (att != null) {
            AttachmentAiTask task = latestTask.get(att.getId());
            if (task != null && AttachmentAiTask.FAILED.equals(task.getStatus())) {
                vo.setError(task.getErrorMsg());
            }
        }
        return vo;
    }

    /**
     * 估算切片数。
     *
     * <p>AI 服务的列表元信息<b>没有</b>切片数字段（切片是现算的、只落向量缓存），
     * 逐个调 {@code /documents/{id}} 拉全文再算会让列表页产生 N 次全量传输。
     * 所以按它的切片规则（不跨页、每片约 {@value #CHUNK_CHARS} 字）估算：
     * {@code 页数 × ceil(平均每页字数 / 500)}。仅用于展示量级。
     */
    private Integer estimateChunks(int chars, int pages) {
        if (chars <= 0) {
            return 0;
        }
        if (pages <= 0) {
            return (int) Math.ceil(chars / (double) CHUNK_CHARS);
        }
        int perPage = (int) Math.ceil(chars / (double) pages);
        int chunksPerPage = Math.max(1, (int) Math.ceil(perPage / (double) CHUNK_CHARS));
        return pages * chunksPerPage;
    }

    /**
     * AI 服务的时间是 ISO {@code yyyy-MM-dd'T'HH:mm:ss}，§9 要求 {@code yyyy-MM-dd HH:mm:ss}。
     * 解析不了就原样返回（宁可显示一个奇怪的格式，也不要丢时间）。
     */
    private String formatUploadedAt(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return TS.format(LocalDateTime.parse(raw.trim().replace(' ', 'T')));
        } catch (Exception e) {
            return raw;
        }
    }

    /** keyword 同时匹配文件名与 doc_id 的子串（大小写不敏感）。 */
    private boolean matchesKeyword(String keyword, String docId, String filename) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String kw = keyword.trim().toLowerCase();
        return (filename != null && filename.toLowerCase().contains(kw))
                || (docId != null && docId.toLowerCase().contains(kw));
    }

    private static <T> List<T> slice(List<T> all, int page, int size) {
        int p = Math.max(1, page);
        // 上限 500：文档库列表只含元信息（无全文），而前端"引用兜底"路径会一次拉 500 条
        // （见 frontend/src/api/ai.ts 的 loadAllDocuments）。若这里卡到 200，
        // 附件超过 200 份时那条兜底路径就会静默查不到 → 引用点不开。
        int s = Math.min(Math.max(1, size), 500);
        int from = (p - 1) * s;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + s, all.size()));
    }
}
