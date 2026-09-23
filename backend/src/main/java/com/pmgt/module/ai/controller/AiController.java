package com.pmgt.module.ai.controller;

import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.ai.dto.AiAttachmentStatusVO;
import com.pmgt.module.ai.dto.AiChatRequest;
import com.pmgt.module.ai.dto.AiChatResponse;
import com.pmgt.module.ai.dto.AiDocumentVO;
import com.pmgt.module.ai.dto.AiHealthVO;
import com.pmgt.module.ai.dto.AiPageVO;
import com.pmgt.module.ai.dto.AiTaskVO;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.service.AiChatService;
import com.pmgt.module.ai.service.AiDocumentService;
import com.pmgt.module.ai.service.AiHealthService;
import com.pmgt.module.ai.service.AiTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力服务代理（§9 #1~#9）。
 *
 * <p>前端<b>只</b>调这里，不直连 AI 服务（8100 仅内网可达，见方案 §4.3）：
 * 权限解析、作用域换算、留痕三件事都必须在主系统完成，所以这层不是"转发"，
 * 而是"收口"。请求/响应字段严格按 §9，AI 服务字段名变了只改适配层。
 *
 * <p>鉴权沿用仓库既有方式：登录由 {@code AuthFilter} 保证（{@code /api/**} 默认需登录），
 * 管理类动作（删除文档 / 重试 / 触发解析）用 {@code @RequireRole} 限定 ADMIN/MANAGER。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** §9 #8：attachmentIds 逗号分隔，上限 200。 */
    private static final int MAX_STATUS_IDS = 200;

    private final AiHealthService healthService;
    private final AiDocumentService documentService;
    private final AiTaskService taskService;
    private final AiChatService chatService;

    public AiController(AiHealthService healthService,
                        AiDocumentService documentService,
                        AiTaskService taskService,
                        AiChatService chatService) {
        this.healthService = healthService;
        this.documentService = documentService;
        this.taskService = taskService;
        this.chatService = chatService;
    }

    // ── #1 服务自检（登录即可） ──────────────────────────────────────

    /**
     * 服务自检。
     *
     * <p>{@code deep} 是本次<b>新增的可选参数</b>（默认 false，老前端不带它时行为与以前完全一致）：
     * {@code deep=false} 只探 OCR 的快速探活；{@code deep=true} 额外探对话模型与平台向量能力
     * （真发请求，几秒到十几秒）。响应字段与语义不变，只是深探时 {@code models} 里的
     * chat/embedding/reranker 由 null（未探测）变成 true/false。
     */
    @GetMapping("/health")
    public R<AiHealthVO> health(@RequestParam(defaultValue = "false") boolean deep) {
        return R.ok(healthService.health(deep));
    }

    // ── #2 文档库列表（登录即可；按可访问项目过滤在服务层做） ────────
    @GetMapping("/documents")
    public R<AiPageVO<AiDocumentVO>> documents(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return R.ok(documentService.list(keyword, projectId, page, size));
    }

    // ── #3 删除文档（ADMIN/MANAGER） ─────────────────────────────────
    @RequireRole({Role.ADMIN, Role.MANAGER})
    @DeleteMapping("/documents/{docId}")
    public R<Map<String, Object>> deleteDocument(@PathVariable String docId) {
        documentService.delete(docId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docId", docId);
        data.put("deleted", true);
        return R.ok(data);
    }

    // ── #4 任务列表（登录即可） ──────────────────────────────────────
    @GetMapping("/tasks")
    public R<AiPageVO<AiTaskVO>> tasks(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long projectId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(taskService.list(status, projectId, page, size));
    }

    // ── #5 任务详情（登录即可） ──────────────────────────────────────
    @GetMapping("/tasks/{taskId}")
    public R<AiTaskVO> task(@PathVariable Long taskId) {
        return R.ok(taskService.get(taskId));
    }

    // ── #6 重试任务（ADMIN/MANAGER） ─────────────────────────────────
    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PostMapping("/tasks/{taskId}/retry")
    public R<Map<String, Object>> retryTask(@PathVariable Long taskId) {
        AttachmentAiTask task = taskService.retry(taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        return R.ok(data);
    }

    // ── #7 触发附件解析（ADMIN/MANAGER） ─────────────────────────────
    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PostMapping("/attachments/{attachmentId}/parse")
    public R<Map<String, Object>> parseAttachment(@PathVariable Long attachmentId) {
        AttachmentAiTask task = taskService.parse(attachmentId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("docId", task.getDocId());
        return R.ok(data);
    }

    // ── #8 附件解析状态（登录即可；逗号分隔，上限 200） ──────────────
    @GetMapping("/attachments/status")
    public R<List<AiAttachmentStatusVO>> attachmentStatus(@RequestParam String attachmentIds) {
        return R.ok(taskService.attachmentStatus(parseIds(attachmentIds)));
    }

    // ── #9 问答（登录即可；作用域在主系统解析） ──────────────────────
    @PostMapping("/chat")
    public R<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
        return R.ok(chatService.ask(request));
    }

    /**
     * 解析 {@code attachmentIds=1,2,3}。
     *
     * <p>非数字直接报 400 而不是忽略：静默忽略会让「附件状态列表少一项」这种问题
     * 表现为"标签永远不更新"，很难查。
     */
    private List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(s));
            } catch (NumberFormatException e) {
                throw new BizException(400, "attachmentIds 含有非法 id：" + s);
            }
        }
        if (ids.isEmpty()) {
            throw new BizException(400, "attachmentIds 不能为空");
        }
        if (ids.size() > MAX_STATUS_IDS) {
            throw new BizException(400, "attachmentIds 一次最多查询 " + MAX_STATUS_IDS + " 个（当前 " + ids.size() + "）");
        }
        return ids.stream().distinct().toList();
    }
}
