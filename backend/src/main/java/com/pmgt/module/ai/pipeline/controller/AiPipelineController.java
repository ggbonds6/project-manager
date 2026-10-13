package com.pmgt.module.ai.pipeline.controller;

import com.pmgt.common.api.R;
import com.pmgt.module.ai.pipeline.dto.ArtifactAppendRequest;
import com.pmgt.module.ai.pipeline.dto.ArtifactVO;
import com.pmgt.module.ai.pipeline.dto.ClaimRequest;
import com.pmgt.module.ai.pipeline.dto.ClaimVO;
import com.pmgt.module.ai.pipeline.dto.EventReportRequest;
import com.pmgt.module.ai.pipeline.dto.EventVO;
import com.pmgt.module.ai.pipeline.dto.FinishRequest;
import com.pmgt.module.ai.pipeline.dto.FinishVO;
import com.pmgt.module.ai.pipeline.dto.HeartbeatRequest;
import com.pmgt.module.ai.pipeline.dto.HeartbeatVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunDetailVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunPageVO;
import com.pmgt.module.ai.pipeline.dto.ReleaseRequest;
import com.pmgt.module.ai.pipeline.dto.ReleaseVO;
import com.pmgt.module.ai.pipeline.dto.StageReportRequest;
import com.pmgt.module.ai.pipeline.dto.StageReportVO;
import com.pmgt.module.ai.pipeline.service.AiPipelineClaimService;
import com.pmgt.module.ai.pipeline.service.AiPipelineQueryService;
import com.pmgt.module.ai.pipeline.service.AiPipelineReportService;
import com.pmgt.module.ai.pipeline.service.AiPipelineTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>AI 服务回写流水线的 9 个端点</b>（契约冻结，见 docs/RAG流水线设计与并发管理.md §4.4.3
 * 方案 B 与本轮任务书）。第 10 条"投影兜底"不是独立端点，而是 {@code GET} 详情里的只读标记。
 *
 * <h2>鉴权（与其它前缀都不同）</h2>
 * <p>整条前缀由 {@link com.pmgt.module.ai.pipeline.AiPipelineTokenFilter} 用
 * {@code X-AI-Service-Token} 把关：用户 JWT 在这一前缀上<b>不</b>被接受，
 * 缺头/值不对一律真实 HTTP 401。另有可选的 {@code X-AI-Pipeline-Token}
 * （claim 响应里的 {@code pipeline_token}）：<b>不带就不校验</b>，带了就必须属于本 run（否则 403）。
 *
 * <h2>状态码语义（调用方按它分流）</h2>
 * <ul>
 *   <li>400 报文/状态迁移非法（<b>不要重试</b>，按提示改）；</li>
 *   <li>401 令牌问题（先修配置）；403 pipeline_token 不属于本 run；</li>
 *   <li>404 run 不存在，<b>或 claim 时队列为空</b>（"没领到"必须与"领到了"一眼可分）；</li>
 *   <li>409 租约失效/状态被并发改写（<b>停下并放回</b>）；</li>
 *   <li>500 内部错误。</li>
 * </ul>
 * 成功一律 HTTP 200 + 仓库统一信封 {@code {code,message,data}}。
 */
@RestController
@RequestMapping("/api/ai/pipeline")
public class AiPipelineController {

    /** 可选的运行级令牌头（见 {@code AiPipelineTokenService} 的说明）。 */
    public static final String PIPELINE_TOKEN_HEADER = "X-AI-Pipeline-Token";

    private final AiPipelineClaimService claimService;
    private final AiPipelineReportService reportService;
    private final AiPipelineQueryService queryService;
    private final AiPipelineTokenService tokens;

    public AiPipelineController(AiPipelineClaimService claimService,
                                AiPipelineReportService reportService,
                                AiPipelineQueryService queryService,
                                AiPipelineTokenService tokens) {
        this.claimService = claimService;
        this.reportService = reportService;
        this.queryService = queryService;
        this.tokens = tokens;
    }

    // ── 1) 抢占式领取 ────────────────────────────────────────────────

    @PostMapping("/claim")
    public R<ClaimVO> claim(@RequestBody(required = false) ClaimRequest request) {
        return R.ok(claimService.claim(request == null ? new ClaimRequest() : request));
    }

    // ── 2) 续约 ─────────────────────────────────────────────────────

    @PostMapping("/runs/{run_id}/heartbeat")
    public R<HeartbeatVO> heartbeat(@PathVariable("run_id") String runId,
                                    @RequestBody(required = false) HeartbeatRequest request,
                                    HttpServletRequest http) {
        verifyPipelineToken(http, runId);
        return R.ok(claimService.heartbeat(runId, request == null ? new HeartbeatRequest() : request));
    }

    // ── 3) 放回队列 ─────────────────────────────────────────────────

    @PostMapping("/runs/{run_id}/release")
    public R<ReleaseVO> release(@PathVariable("run_id") String runId,
                               @RequestBody(required = false) ReleaseRequest request,
                               HttpServletRequest http) {
        verifyPipelineToken(http, runId);
        return R.ok(claimService.release(runId, request == null ? new ReleaseRequest() : request));
    }

    // ── 4) 阶段上报（幂等） ─────────────────────────────────────────

    @PostMapping("/runs/{run_id}/stages/{stage_key}")
    public R<StageReportVO> reportStage(@PathVariable("run_id") String runId,
                                        @PathVariable("stage_key") String stageKey,
                                        @RequestBody(required = false) StageReportRequest request,
                                        HttpServletRequest http) {
        verifyPipelineToken(http, runId);
        return R.ok(reportService.reportStage(runId, stageKey, request == null ? new StageReportRequest() : request));
    }

    // ── 5) 产物追加（只 INSERT，rev 自增） ──────────────────────────

    @PostMapping("/runs/{run_id}/artifacts")
    public R<ArtifactVO> appendArtifact(@PathVariable("run_id") String runId,
                                        @RequestBody(required = false) ArtifactAppendRequest request,
                                        HttpServletRequest http) {
        verifyPipelineToken(http, runId);
        return R.ok(reportService.appendArtifact(runId, request == null ? new ArtifactAppendRequest() : request));
    }

    // ── 6) 状态迁移审计事件 ─────────────────────────────────────────

    @PostMapping("/runs/{run_id}/events")
    public R<EventVO> reportEvent(@PathVariable("run_id") String runId,
                                  @RequestBody(required = false) EventReportRequest request,
                                  HttpServletRequest http) {
        verifyPipelineToken(http, runId);
        return R.ok(reportService.reportEvent(runId, request == null ? new EventReportRequest() : request));
    }

    // ── 7) 终态（任务终态以主系统为准） ─────────────────────────────

    @PostMapping("/runs/{run_id}/finish")
    public R<FinishVO> finish(@PathVariable("run_id") String runId,
                              @RequestBody(required = false) FinishRequest request,
                              HttpServletRequest http) {
        verifyPipelineToken(http, runId);
        return R.ok(reportService.finish(runId, request == null ? new FinishRequest() : request));
    }

    // ── 8) 详情（含投影兜底标记） ───────────────────────────────────

    @GetMapping("/runs/{run_id}")
    public R<PipelineRunDetailVO> detail(@PathVariable("run_id") String runId) {
        return R.ok(queryService.detailByRunId(runId));
    }

    // ── 9) 列表 ─────────────────────────────────────────────────────

    @GetMapping("/runs")
    public R<PipelineRunPageVO> runs(@RequestParam(name = "attachment_id", required = false) Long attachmentId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return R.ok(queryService.list(attachmentId, status, page, size));
    }

    /**
     * 可选校验：带了 {@code X-AI-Pipeline-Token} 就必须属于本 run。
     *
     * <p>为什么"不带就不拦"：契约写死的门票是 {@code X-AI-Service-Token}，
     * 强制要求 pipeline_token 会把"两侧发版不同步"变成 403 故障；而一旦调用方带了它，
     * 校验就几乎没有成本，还能挡住"服务令牌配对了但 run_id 写错"的串写。
     */
    private void verifyPipelineToken(HttpServletRequest http, String runId) {
        tokens.verifyForRun(http.getHeader(PIPELINE_TOKEN_HEADER), runId);
    }
}
