package com.pmgt.module.ai.pipeline.controller;

import com.pmgt.common.api.R;
import com.pmgt.module.ai.pipeline.dto.ArtifactContentVO;
import com.pmgt.module.ai.pipeline.dto.ArtifactVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunDetailVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunPageVO;
import com.pmgt.module.ai.pipeline.service.AiPipelineQueryService;
import com.pmgt.module.ai.service.AiScopeResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 前端<b>只读</b>的任务流接口（登录即可）。
 *
 * <h2>为什么不复用 {@code /api/ai/pipeline/**}</h2>
 * <p>那条前缀的门票是 {@code X-AI-Service-Token}，且<b>明确不接受用户 JWT</b>
 * （契约第 10 条与鉴权要求：用户令牌不能调那条前缀）。而"任务流视图"是给浏览器用的，
 * 用户带的是 JWT。所以这里按仓库既有方式另开一组用户侧只读接口，
 * 数据来自同一个 {@link AiPipelineQueryService}——<b>一套读逻辑、两种门票</b>，
 * 不复制实现，也不给那条服务间前缀开后门。
 *
 * <p>与设计文档 §4.5.2 的 API-1/API-2 对应（路径按本仓库的既有风格落在 {@code /api/ai} 下）：
 * <ul>
 *   <li>{@code GET /api/ai/runs/{run_id}/pipeline} → <b>冻结契约同形状的详情入口</b>（前端用 run_id 直取）；</li>
 *   <li>{@code GET /api/ai/runs?attachment_id=&status=&page=&size=} → <b>冻结契约同形状的列表入口</b>；</li>
 *   <li>{@code GET /api/ai/tasks/{taskId}/pipeline} → 按任务的等价入口（手上有 taskId 时更直接）；</li>
 *   <li>{@code GET /api/ai/tasks/{taskId}/pipeline/artifacts} → 产物清单（含 {@code chunk_manifest}）；</li>
 *   <li>{@code GET /api/ai/tasks/{taskId}/pipeline/artifacts/{artifactType}} → API-2 产物详情
 *       （<b>「答案 [n] → chunk_key」的映射入口</b>：切片清单在 chunk_manifest 的 content 里）；</li>
 *   <li>{@code GET /api/ai/attachments/{attachmentId}/pipeline} → 该附件最近一次运行的任务流；</li>
 *   <li>{@code GET /api/ai/attachments/{attachmentId}/pipeline/runs} → 该附件的运行历史（分页）。</li>
 * </ul>
 *
 * <p>权限口径与既有 {@code GET /api/ai/tasks/{id}} 一致：登录即可见（本系统角色不按项目分权，
 * 见 {@code AiScopeResolver} 的类注释）；按附件入口额外做一次"附件存在且未删除"的校验，
 * 而按任务入口<b>刻意不</b>校验附件是否还在——V15 的注释写明"附件删除后仍可追溯"。
 */
@RestController
@RequestMapping("/api/ai")
public class AiPipelineViewController {

    private final AiPipelineQueryService queryService;
    private final AiScopeResolver scopeResolver;

    public AiPipelineViewController(AiPipelineQueryService queryService, AiScopeResolver scopeResolver) {
        this.queryService = queryService;
        this.scopeResolver = scopeResolver;
    }

    /** API-1：任务流视图主接口（任务 + 阶段条 + 产物摘要 + 事件 + 投影提示）。 */
    @GetMapping("/tasks/{taskId}/pipeline")
    public R<PipelineRunDetailVO> taskPipeline(@PathVariable Long taskId) {
        return R.ok(queryService.detailByTaskId(taskId, String.valueOf(taskId)));
    }

    /**
     * 与冻结契约<b>同形状</b>的用户侧详情入口：{@code GET /api/ai/runs/{run_id}/pipeline}。
     *
     * <h2>为什么需要这个"别名"</h2>
     * <p>契约里 {@code GET /api/ai/pipeline/runs/{run_id}} 那一组是 <b>AI 服务回调前缀</b>
     * （门票 {@code X-AI-Service-Token}，且明确不接受用户 JWT）；而任务流视图是浏览器在读。
     * 两个要求同时成立，就只能有两条路径：<b>同一份读逻辑、两种门票</b>。
     * 这里把 {@code run_id} 形状（{@code run-12}）与主系统派生规则对齐，
     * 前端从列表里拿到的 {@code run_id} 可以<b>原样</b>用在这条路径上，不需要换算成 taskId。
     */
    @GetMapping("/runs/{runId}/pipeline")
    public R<PipelineRunDetailVO> runPipeline(@PathVariable String runId) {
        return R.ok(queryService.detailByRunId(runId));
    }

    /**
     * 与冻结契约<b>同形状</b>的用户侧列表入口：{@code GET /api/ai/runs?attachment_id=&status=&page=&size=}。
     *
     * <p>与 {@code /api/ai/attachments/{id}/pipeline/runs} 的唯一差别是
     * <b>不校验附件是否存在</b>：这里查的是"任务历史"，而 V15 的边界写明
     * "附件删除后仍可追溯"。要"必须是活附件"的语义就用附件路径那条。
     *
     * <p>{@code status} 只认**任务级** 7 状态（{@code QUEUED/RUNNING/DONE/PARTIAL/FAILED/CANCELLED/DEAD}），
     * 且支持**逗号分隔多值**（{@code QUEUED,RUNNING} = 进行中）；阶段级独有的
     * {@code PENDING}/{@code SKIPPED} 会被拒绝并提示"这是阶段级状态"（见
     * {@code AiPipelineQueryService#parseStatuses}）。
     */
    @GetMapping("/runs")
    public R<PipelineRunPageVO> runs(@RequestParam(name = "attachment_id", required = false) Long attachmentId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return R.ok(queryService.list(attachmentId, status, page, size));
    }

    /** 产物清单（不含 content）：前端产物抽屉的列表数据。 */
    @GetMapping("/tasks/{taskId}/pipeline/artifacts")
    public R<List<ArtifactVO>> taskArtifacts(@PathVariable Long taskId,
                                             @RequestParam(name = "artifact_type", required = false)
                                             String artifactType) {
        return R.ok(queryService.artifacts(taskId, artifactType));
    }

    /**
     * API-2：产物详情（取 {@code content}；{@code ?rev=} 指定版本，缺省最新版）。
     *
     * <p>{@code artifact_type=chunk_manifest} 就是"切片清单/ chunk_key 映射"的读取入口。
     */
    @GetMapping("/tasks/{taskId}/pipeline/artifacts/{artifactType}")
    public R<ArtifactContentVO> taskArtifactContent(@PathVariable Long taskId,
                                                    @PathVariable String artifactType,
                                                    @RequestParam(required = false) Integer rev) {
        return R.ok(queryService.artifactContent(taskId, artifactType, rev));
    }

    /** 该附件最近一次运行的完整任务流（附件列表/文档列表点进来的入口）。 */
    @GetMapping("/attachments/{attachmentId}/pipeline")
    public R<PipelineRunDetailVO> attachmentPipeline(@PathVariable Long attachmentId) {
        // 附件必须存在且未删除（与其它附件接口同一口径）；任务历史本身的"附件已删仍可追溯"
        // 走 /tasks/{taskId}/pipeline 那条路
        scopeResolver.requireAccessible(attachmentId);
        return R.ok(queryService.detailByAttachmentId(attachmentId));
    }

    /** 该附件的运行历史（一个附件可能被解析多次：首解析 + 重试 + 重建）。 */
    @GetMapping("/attachments/{attachmentId}/pipeline/runs")
    public R<PipelineRunPageVO> attachmentRuns(@PathVariable Long attachmentId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        scopeResolver.requireAccessible(attachmentId);
        return R.ok(queryService.list(attachmentId, status, page, size));
    }

    /**
     * 便于前端一次性拿到"阶段键 → 中文名/顺序"的字典（避免前端写死映射）。
     *
     * <p>路径刻意<b>不</b>落在 {@code /api/ai/pipeline/**} 之下：那条前缀由服务间令牌把关、
     * 不接受用户 JWT（见类注释），把给浏览器的字典挂进去会直接 401。
     */
    @GetMapping("/stage-dict")
    public R<List<Map<String, Object>>> stageDict() {
        return R.ok(com.pmgt.module.ai.pipeline.AiPipelineStages.ALL_STAGE_KEYS.stream()
                .map(key -> {
                    com.pmgt.module.ai.pipeline.AiPipelineStages.Stage stage =
                            com.pmgt.module.ai.pipeline.AiPipelineStages.of(key);
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("stage_key", stage.key());
                    item.put("stageKey", stage.key());
                    item.put("stage_name", stage.name());
                    item.put("stageName", stage.name());
                    item.put("seq", stage.seq());
                    item.put("ingest", com.pmgt.module.ai.pipeline.AiPipelineStages
                            .INGEST_STAGE_KEYS.contains(key));
                    return item;
                })
                .toList());
    }
}
