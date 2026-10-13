package com.pmgt.ai.module.admin.controller;

import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.module.admin.ReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全量重建索引（含重新向量化）：<b>可中断、可续跑</b>。
 *
 * <p>用法（运维手册口径，也可以用 curl 直接粘）：
 * <pre>
 *   # ① 全量重建（默认 skip_existing：已完整的文档整份跳过、已存在的切片按 chunk_key 跳过）
 *   curl -XPOST 'http://127.0.0.1:8100/admin/reindex'
 *
 *   # ② 只看某几份文档（改元数据后局部重建）
 *   curl -XPOST 'http://127.0.0.1:8100/admin/reindex?docIds=abc123,def456'
 *
 *   # ③ 强制重算（忽略"已存在"，把全部切片重写一遍；用于换了 embedding 模型/维度）
 *   curl -XPOST 'http://127.0.0.1:8100/admin/reindex?force=true'
 *
 *   # ④ 看进度（processed/total/failed/ETA）
 *   curl 'http://127.0.0.1:8100/admin/reindex/&lt;job_id&gt;'
 *
 *   # ⑤ 中断（协作式：在文档边界生效；已写入的切片不回滚，下次续跑会跳过）
 *   curl -XPOST 'http://127.0.0.1:8100/admin/reindex/&lt;job_id&gt;/cancel'
 * </pre>
 *
 * <p>为什么放 {@code /admin/**} 而不是 {@code /documents/**}：它不是文档库的 CRUD，
 * 而是"重建检索索引"这类运维动作；单独一段路径便于防火墙/网关按前缀收紧（本服务当前无入站鉴权，
 * 见 ai-backend/README.md 的运维边界）。
 */
@RestController
@RequestMapping("/admin/reindex")
@RequiredArgsConstructor
public class ReindexController {

    private final ReindexService reindexService;

    /**
     * 启动一次重建。
     *
     * @param docIds 只重建这些文档（逗号分隔）；为空 = 全库
     * @param force  {@code true} = 忽略"已存在"，全部切片重写；默认 {@code false}（断点续跑）
     */
    @PostMapping
    public Map<String, Object> start(
            @RequestParam(name = "docIds", required = false) String docIds,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        List<String> targets = new ArrayList<>();
        if (docIds != null && !docIds.isBlank()) {
            for (String item : docIds.split(",")) {
                if (!item.isBlank()) {
                    targets.add(item.strip());
                }
            }
        }
        try {
            return ApiResponse.ok(reindexService.start(targets, force).toMap());
        } catch (IllegalStateException e) {
            // 已有任务在跑：409 而不是 500（"现在不行"与"服务坏了"是两件事）
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 全部重建任务（倒序）。 */
    @GetMapping
    public Map<String, Object> list() {
        return ApiResponse.ok(reindexService.list());
    }

    /**
     * <b>重建计划（只读）</b>：按向量缓存的版本头算出"哪些文档需要重算"。
     *
     * <p>设计 §3.3.3 的 L3：{@code clean_rev/chunk_rev} 变化后，
     * 切片键（L1）与向量缓存（L2）都失效——但"要不要现在重建、影响多少份文档"必须看得见，
     * 而不是靠运维猜。本端点<b>不发请求、不写任何东西</b>（{@code opensearch} 索引里的键
     * 需要连集群才能核对，所以返回里明写 {@code index_checked=false}）。
     *
     * <pre>
     *   curl 'http://127.0.0.1:8100/admin/reindex/plan'
     * </pre>
     */
    @GetMapping("/plan")
    public Map<String, Object> plan() {
        return ApiResponse.ok(reindexService.plan());
    }

    /** 单个任务的进度（含 ETA、失败数、跳过数）。 */
    @GetMapping("/{jobId}")
    public Map<String, Object> get(@PathVariable String jobId) {
        ReindexService.Job job = reindexService.get(jobId);
        if (job == null) {
            throw ApiException.notFound("重建任务不存在：" + jobId);
        }
        return ApiResponse.ok(job.toMap());
    }

    /** 请求中断（协作式）。 */
    @PostMapping("/{jobId}/cancel")
    public Map<String, Object> cancel(@PathVariable String jobId) {
        if (!reindexService.cancel(jobId)) {
            throw ApiException.badRequest("任务不存在或已结束：" + jobId);
        }
        return ApiResponse.ok(Map.of("cancelled", jobId));
    }
}
