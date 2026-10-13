/**
 * 任务流抽屉（PipelineFlowDrawer）—— 解析流水线「阶段条 + 阶段卡片」
 * ----------------------------------------------------------------
 * 交互借鉴 `data-classification/src/components/JobProgressModal.tsx`（**只读参考、未改它**）：
 *  ① **打开即重新取一次全量详情**：列表接口只给表头（§4.9.2⑥），已结束的任务后面也不再有轮询，
 *     只靠轮询会永远看不到明细；
 *  ② 未结束 **1.5s 轮询**；终态/失败立即停止（`useVisiblePolling` 的 `enabled` 由 `isPipelineTerminal` 决定）；
 *  ③ 顶部统计卡 + 进度 + 项级明细，失败行红底、"只看失败"开关；
 *  ④ 与参考项目的差异（本项目改进）：**页面不可见时暂停轮询**；**连续失败自动停**（避免后台一直打接口）。
 *
 * 组件层级（§4.9.3）：`PipelineTab`（列表）→ 本抽屉（宽 920）→ `Steps`（阶段条）
 *   → 展开 `Collapse` 阶段卡片（计数/耗时/尝试次数/错误/输入输出摘要）→ `PipelineArtifactDrawer`（产物）。
 *
 * 只读说明：冻结契约里只有两个 GET（列表/详情），**没有流水线级的重试/取消端点**，
 * 因此这里不摆"点不动的按钮"，只在底部用文字写明待接的端点（详见 `api/ai.ts` 末尾注释）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Collapse,
  Descriptions,
  Drawer,
  Empty,
  Progress,
  Row,
  Space,
  Statistic,
  Steps,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleFilled,
  ExclamationCircleFilled,
  FileSearchOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { aiApi } from '@/api/ai';
import PipelineArtifactDrawer, { ContentKV } from '@/components/ai/PipelineArtifactDrawer';
import { fmtDuration, hasValue, kvEntries } from '@/components/ai/pipelineFormat';
import { useVisiblePolling } from '@/components/ai/useVisiblePolling';
import { fmtDateTime } from '@/utils/format';
import {
  AI_PIPELINE_QA_STAGE_ORDER,
  AI_PIPELINE_STAGE_ORDER,
  AiPipelineRunDetail,
  AiPipelineStage,
  aiPipelineStageText,
  aiPipelineStatusMeta,
  aiPipelineTaskTypeText,
  isPipelineFailed,
  isPipelineTerminal,
} from '@/types/ai';

interface Props {
  open: boolean;
  /** 要看的流水线（`run_id`）；为 null 时抽屉不取数 */
  runId: string | null;
  /** 列表行已知的文件名（可选）：让标题不只有 run_id */
  filenameHint?: string | null;
  /** 每次成功取到详情后回调：列表页用它把质量看板切到当前 run */
  onDetailLoaded?: (detail: AiPipelineRunDetail) => void;
  onClose: () => void;
}

/** 详情轮询间隔：与参考项目 `JobProgressModal` 一致（列表 3s、详情 1.5s） */
const DETAIL_POLL_MS = 1500;

/** 连续失败多少次后停止自动刷新（参考项目是"一失败就停"；这里给 3 次容错，避免一次抖动就停） */
const MAX_POLL_FAILURES = 3;

/** 是否五段主链路里的阶段 */
function isMainStage(key: string): boolean {
  return (AI_PIPELINE_STAGE_ORDER as string[]).includes(key);
}

/** 阶段状态 → antd `Steps` 的内置状态（决定节点连线与标题配色） */
function stepsStatus(status?: string | null): 'wait' | 'process' | 'finish' | 'error' {
  const k = (status || '').toUpperCase();
  if (k === 'DONE' || k === 'PARTIAL') return 'finish';
  if (k === 'RUNNING') return 'process';
  if (k === 'FAILED' || k === 'DEAD') return 'error';
  return 'wait';
}

/**
 * 阶段状态 → 图标。
 * **PARTIAL（部分成功）用橙色感叹号**，与 DONE 的绿色对勾一眼可分——这是任务书的硬要求
 * （部分成功的切片可检索但不完整，混进"已完成"里会让用户以为一切正常）。
 */
function stageIcon(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'DONE':
      return <CheckCircleFilled style={{ color: '#52c41a' }} />;
    case 'PARTIAL':
      return <ExclamationCircleFilled style={{ color: '#fa8c16' }} />;
    case 'RUNNING':
      return <LoadingOutlined style={{ color: '#1677ff' }} />;
    case 'FAILED':
    case 'DEAD':
      return <CloseCircleFilled style={{ color: '#ff4d4f' }} />;
    case 'SKIPPED':
      return <MinusCircleOutlined style={{ color: '#bfbfbf' }} />;
    case 'CANCELLED':
      return <StopOutlined style={{ color: '#bfbfbf' }} />;
    default:
      return <ClockCircleOutlined style={{ color: '#bfbfbf' }} />;
  }
}

/** 阶段条里的计量文案：`item_count / item_failed`（后端没给就不显示 0） */
function stageCountText(stage: AiPipelineStage): string | null {
  const parts: string[] = [];
  if (hasValue(stage.itemCount)) parts.push(`${stage.itemCount} 项`);
  if (hasValue(stage.itemFailed) && (stage.itemFailed ?? 0) > 0) parts.push(`失败 ${stage.itemFailed} 项`);
  return parts.length ? parts.join(' · ') : null;
}

/** SKIPPED 的跳过原因：后端可能放 `error_msg`，也可能放 counters 里的 reason/skip_reason */
function skipReason(stage: AiPipelineStage): string | null {
  if (stage.errorMsg) return stage.errorMsg;
  const counters = stage.counters || {};
  for (const key of ['skip_reason', 'reason', 'skipped_reason']) {
    const v = counters[key];
    if (typeof v === 'string' && v.trim() !== '') return v;
  }
  return null;
}

export default function PipelineFlowDrawer({
  open,
  runId,
  filenameHint,
  onDetailLoaded,
  onClose,
}: Props) {
  const [detail, setDetail] = useState<AiPipelineRunDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 轮询连续失败次数（达到阈值就停自动刷新，避免"关不掉的后台请求"） */
  const [pollFailures, setPollFailures] = useState(0);
  /** "只看失败/异常阶段"：排查时最常用的视图（借鉴参考项目的"只看失败项"） */
  const [onlyFailedStages, setOnlyFailedStages] = useState(false);
  /** 展开的阶段（与阶段条双向联动） */
  const [activeStage, setActiveStage] = useState<string | null>(null);
  const [artifactOpen, setArtifactOpen] = useState(false);
  /** 用户是否手动点过阶段条/卡片：手动选过之后，自动聚焦不再覆盖他的选择 */
  const userPickedRef = useRef(false);
  /** 用 ref 持有回调，避免父组件每次渲染都触发"重新取数" */
  const onDetailLoadedRef = useRef(onDetailLoaded);
  onDetailLoadedRef.current = onDetailLoaded;

  const load = useCallback(
    async (silent: boolean) => {
      if (!runId) return;
      if (!silent) setLoading(true);
      try {
        const d = await aiApi.pipelineRunDetail(runId);
        setDetail(d);
        setError(null);
        setPollFailures(0);
        onDetailLoadedRef.current?.(d);
      } catch (e) {
        if (silent) {
          setPollFailures((n) => n + 1);
        } else {
          setDetail(null);
          setError((e as Error).message || '流水线详情读取失败');
        }
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [runId],
  );

  // ① 打开（或切换 run）即**重新取一次全量**：列表只给表头，不能只依赖传入对象
  useEffect(() => {
    if (!open || !runId) return;
    setDetail(null);
    setError(null);
    setPollFailures(0);
    setOnlyFailedStages(false);
    setActiveStage(null);
    userPickedRef.current = false;
    void load(false);
  }, [open, runId, load]);

  // ② 未终态时 1.5s 轮询；终态/失败/连续失败即停（页面不可见时也停，见 useVisiblePolling）
  const pollingEnabled =
    open && !!runId && !!detail && !isPipelineTerminal(detail.status) && pollFailures < MAX_POLL_FAILURES;
  useVisiblePolling(() => load(true), { intervalMs: DETAIL_POLL_MS, enabled: pollingEnabled });

  const stageMap = useMemo(() => {
    const map = new Map<string, AiPipelineStage>();
    (detail?.stages ?? []).forEach((s) => map.set(s.stageKey, s));
    return map;
  }, [detail]);

  /** 五段主链路（顺序固定，后端没上报的阶段行显示"该阶段未上报"而不是消失） */
  const mainStages = useMemo(
    () => AI_PIPELINE_STAGE_ORDER.map((key) => ({ key: key as string, stage: stageMap.get(key) ?? null })),
    [stageMap],
  );

  /** 问答链路（RETRIEVE/GENERATE）与后端可能新增的未知阶段：折叠展示，不挤占五段阶段条 */
  const otherStages = useMemo(
    () =>
      (detail?.stages ?? []).filter(
        (s) => !isMainStage(s.stageKey) && !AI_PIPELINE_QA_STAGE_ORDER.includes(s.stageKey as never),
      ),
    [detail],
  );
  const qaStages = useMemo(
    () => (detail?.stages ?? []).filter((s) => AI_PIPELINE_QA_STAGE_ORDER.includes(s.stageKey as never)),
    [detail],
  );

  // ③ 自动聚焦"最该看的那一步"：失败/部分成功/进行中优先，其次第一个阶段
  useEffect(() => {
    if (!detail || userPickedRef.current) return;
    const focus =
      (detail.stages || []).find((s) => isPipelineFailed(s.status)) ||
      (detail.stages || []).find((s) => (s.status || '').toUpperCase() === 'RUNNING') ||
      (detail.stages || []).find((s) => (s.status || '').toUpperCase() === 'PARTIAL') ||
      (detail.stages || [])[0];
    setActiveStage(focus ? focus.stageKey : null);
  }, [detail]);

  const stats = useMemo(() => {
    const stages = detail?.stages ?? [];
    const count = (codes: string[]) =>
      stages.filter((s) => codes.includes((s.status || '').toUpperCase())).length;
    const itemFailed = stages.reduce((n, s) => n + (s.itemFailed ?? 0), 0);
    const stageElapsedSum = stages.reduce((n, s) => n + (s.elapsedMs ?? 0), 0);
    // 端到端耗时优先用后端给的 elapsed_ms；没有才回落到"各阶段耗时之和"，且必须标注这是回落
    const elapsed = hasValue(detail?.elapsedMs) ? (detail?.elapsedMs as number) : null;
    return {
      done: count(['DONE']),
      partial: count(['PARTIAL']),
      skipped: count(['SKIPPED']),
      failed: count(['FAILED', 'DEAD']),
      total: AI_PIPELINE_STAGE_ORDER.length,
      itemFailed,
      elapsed,
      stageElapsedSum,
    };
  }, [detail]);

  const visibleStages = useMemo(() => {
    const rows = mainStages.map(({ key, stage }) => ({ key, stage }));
    if (!onlyFailedStages) return rows;
    // "只看失败/异常"：未上报的阶段行也算"要看"，否则筛完就空了（排查时看不到"少了一步"）
    return rows.filter(({ stage }) => !stage || isPipelineFailed(stage.status) || (stage.status || '').toUpperCase() === 'PARTIAL');
  }, [mainStages, onlyFailedStages]);

  const stepItems = mainStages.map(({ key, stage }) => {
    const status = stage?.status ?? null;
    const meta = aiPipelineStatusMeta(status);
    const countText = stage ? stageCountText(stage) : '该阶段未上报';
    const elapsedText = stage ? fmtDuration(stage.elapsedMs) : null;
    // 进行中阶段显示任务级的 stage_done/stage_total（§4.9.3 阶段条字段）
    const isRunningHere =
      (status || '').toUpperCase() === 'RUNNING' && (!detail?.stageKey || detail.stageKey === key);
    const done = detail?.stageDone ?? null;
    const total = detail?.stageTotal ?? null;
    return {
      key,
      status: stepsStatus(status),
      icon: stageIcon(status),
      title: (
        <Space size={4} style={{ fontSize: 13 }}>
          <span>{aiPipelineStageText(key, stage?.stageName)}</span>
          <Tag color={meta.color} style={{ marginInlineEnd: 0 }}>
            {stage ? meta.text : '未上报'}
          </Tag>
        </Space>
      ),
      description: (
        <div style={{ fontSize: 12, color: '#8c8c8c', lineHeight: 1.6 }}>
          <div>
            {elapsedText ? `耗时 ${elapsedText}` : '耗时 暂无数据'}
            {countText ? ` · ${countText}` : ''}
            {stage && (stage.attempts ?? 0) > 1 ? ` · 已重试 ${stage.attempts} 次` : ''}
          </div>
          {isRunningHere ? (
            total !== null && total > 0 ? (
              <Progress percent={Math.min(100, Math.round(((done ?? 0) / total) * 100))} size="small" />
            ) : (
              <span>不确定进度（stage_total=0，后端未给总量）</span>
            )
          ) : null}
        </div>
      ),
    };
  });

  const title = detail?.filename || filenameHint || runId || '任务流';
  const detailStatusMeta = aiPipelineStatusMeta(detail?.status);

  return (
    <Drawer
      title={
        <Space size={8} wrap>
          <span>任务流 · {title}</span>
          {detail ? <Tag color={detailStatusMeta.color}>{detailStatusMeta.text}</Tag> : null}
          {detail?.taskType ? <Tag>{aiPipelineTaskTypeText(detail.taskType)}</Tag> : null}
          {runId ? (
            <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
              run {runId}
            </Typography.Text>
          ) : null}
        </Space>
      }
      open={open}
      onClose={onClose}
      width={920}
      destroyOnClose
      extra={
        <Space size={6}>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => void load(false)}
          >
            刷新
          </Button>
          {detail?.artifacts?.length ? (
            <Button size="small" icon={<FileSearchOutlined />} onClick={() => setArtifactOpen(true)}>
              产物（{detail.artifacts.length}）
            </Button>
          ) : null}
        </Space>
      }
    >
      {error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message="任务流读取失败"
          description={
            <>
              <div>{error}</div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                这里读的是主系统接口 {'/api/ai/pipeline/runs/{run_id}'}；失败<b>不代表该任务没有阶段数据</b>，
                可能只是接口尚未部署或权限不足。
              </Typography.Text>
            </>
          }
        />
      ) : null}

      {/* 投影兜底提示：主系统按 AI 任务详情补投影时给的中文说明（§4.4.3 C 方案） */}
      {detail?.projectionNote ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="这份任务流是投影兜底来的：AI 侧可能已中断"
          description={
            <>
              <div>{detail.projectionNote}</div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                某些阶段长时间停留在「进行中」却没有回调写入时，主系统会用 AI 任务详情补投影。
                它<b>不代表 AI 服务仍在正常执行</b>，请到「服务自检」页确认服务状态。
              </Typography.Text>
            </>
          }
        />
      ) : null}

      {detail?.errorMsg ? (
        <Alert
          type={isPipelineFailed(detail.status) ? 'error' : 'warning'}
          showIcon
          style={{ marginBottom: 12 }}
          message="任务级失败原因"
          description={<Typography.Text style={{ fontSize: 12 }}>{detail.errorMsg}</Typography.Text>}
        />
      ) : null}

      {pollFailures >= MAX_POLL_FAILURES && !isPipelineTerminal(detail?.status) ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`已连续 ${MAX_POLL_FAILURES} 次读取失败，自动刷新已暂停`}
          description={
            <Space>
              <Typography.Text style={{ fontSize: 12 }}>
                为避免后台一直打接口，已停止 1.5s 轮询；点「刷新」可手动重试。
              </Typography.Text>
              <Button size="small" onClick={() => void load(false)}>
                刷新
              </Button>
            </Space>
          }
        />
      ) : null}

      {!detail && !error ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loading ? '正在读取任务流…' : '暂无数据'} />
      ) : null}

      {detail ? (
        <>
          <Row gutter={[8, 8]} style={{ marginBottom: 12 }}>
            <Col xs={12} sm={8} lg={4}>
              <Card size="small">
                <Statistic title="阶段完成" value={stats.done} suffix={`/ ${stats.total}`} />
              </Card>
            </Col>
            <Col xs={12} sm={8} lg={4}>
              <Card size="small">
                <Statistic
                  title="部分成功"
                  value={stats.partial}
                  valueStyle={{ color: stats.partial ? '#fa8c16' : undefined }}
                />
              </Card>
            </Col>
            <Col xs={12} sm={8} lg={4}>
              <Card size="small">
                <Statistic title="已跳过" value={stats.skipped} />
              </Card>
            </Col>
            <Col xs={12} sm={8} lg={4}>
              <Card size="small">
                <Statistic
                  title="失败阶段"
                  value={stats.failed}
                  valueStyle={{ color: stats.failed ? '#ff4d4f' : undefined }}
                />
              </Card>
            </Col>
            <Col xs={12} sm={8} lg={4}>
              <Card size="small">
                <Statistic
                  title="失败项合计"
                  value={stats.itemFailed}
                  valueStyle={{ color: stats.itemFailed ? '#ff4d4f' : undefined }}
                />
              </Card>
            </Col>
            <Col xs={12} sm={8} lg={4}>
              <Card size="small">
                <Tooltip
                  title={
                    stats.elapsed !== null
                      ? '来自任务的 elapsed_ms（端到端耗时）'
                      : `任务未上报 elapsed_ms，此处是各阶段耗时之和（${fmtDuration(stats.stageElapsedSum) ?? '暂无数据'}）`
                  }
                >
                  <Statistic
                    title="耗时"
                    value={
                      (fmtDuration(stats.elapsed ?? stats.stageElapsedSum) ?? '暂无数据') +
                      (stats.elapsed === null && stats.stageElapsedSum > 0 ? ' *' : '')
                    }
                  />
                </Tooltip>
              </Card>
            </Col>
          </Row>

          <Card size="small" style={{ marginBottom: 12 }}>
            <div style={{ overflowX: 'auto' }}>
              <div style={{ minWidth: 760 }}>
                <Steps
                  items={stepItems}
                  labelPlacement="vertical"
                  onChange={(current) => {
                    userPickedRef.current = true;
                    setActiveStage(mainStages[current]?.key ?? null);
                  }}
                />
              </div>
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              阶段顺序固定为 识别 → 清洗 → 切分 → 向量化 → 入库（§3.2）；点节点展开该阶段卡片。
              {detail.attempts && detail.attempts > 1 ? `本任务共尝试 ${detail.attempts} 次。` : ''}
            </Typography.Text>
          </Card>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <Checkbox
              checked={onlyFailedStages}
              onChange={(e) => setOnlyFailedStages(e.target.checked)}
            >
              只看失败 / 需要关注的阶段
              {stats.failed + stats.partial > 0 ? (
                <Tag color="red" style={{ marginLeft: 6 }}>
                  {stats.failed + stats.partial}
                </Tag>
              ) : null}
            </Checkbox>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {isPipelineTerminal(detail.status)
                ? '已终态：不再自动刷新'
                : `每 ${DETAIL_POLL_MS / 1000}s 自动刷新（页面不可见时暂停）`}
            </Typography.Text>
          </div>

          {visibleStages.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有失败或需关注的阶段" />
          ) : (
            <Collapse
              accordion
              activeKey={activeStage ?? undefined}
              onChange={(key) => {
                userPickedRef.current = true;
                setActiveStage(Array.isArray(key) ? (key[0] as string) ?? null : (key as string) ?? null);
              }}
              items={visibleStages.map(({ key, stage }) => {
                const status = stage?.status ?? null;
                const meta = aiPipelineStatusMeta(status);
                return {
                  key,
                  label: (
                    <Space size={6} wrap>
                      {stageIcon(status)}
                      <span>{aiPipelineStageText(key, stage?.stageName)}</span>
                      <Tag color={meta.color} style={{ marginInlineEnd: 0 }}>
                        {stage ? meta.text : '该阶段未上报'}
                      </Tag>
                      {stage && (stage.attempts ?? 0) > 1 ? <Tag color="orange">已重试 {stage.attempts} 次</Tag> : null}
                      {stage?.errorMsg ? (
                        <Tooltip title={stage.errorMsg}>
                          <Typography.Text type="danger" style={{ fontSize: 12 }}>
                            {stage.errorMsg}
                          </Typography.Text>
                        </Tooltip>
                      ) : null}
                    </Space>
                  ),
                  children: stage ? (
                    <StageCard
                      stage={stage}
                      artifactCount={detail.artifacts.length}
                      onOpenArtifacts={() => setArtifactOpen(true)}
                    />
                  ) : (
                    <Alert
                      type="info"
                      showIcon
                      message="该阶段未上报"
                      description={
                        <Typography.Text style={{ fontSize: 12 }}>
                          主系统里没有这个阶段的阶段行（ai_pipeline_stage）。可能是该阶段还没开始，
                          也可能是阶段回调尚未落地（§4.4.3）；<b>不显示为空</b>是为了让"少了一步"看得见。
                        </Typography.Text>
                      }
                    />
                  ),
                };
              })}
            />
          )}

          {qaStages.length + otherStages.length > 0 ? (
            <Collapse
              ghost
              size="small"
              style={{ marginTop: 8 }}
              items={[
                {
                  key: 'qa',
                  label: (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      问答链路 / 其它阶段（{qaStages.length + otherStages.length}）—— 属召回与生成，不在解析五段里
                    </Typography.Text>
                  ),
                  children: (
                    <Space direction="vertical" size={4} style={{ width: '100%' }}>
                      {[...qaStages, ...otherStages].map((s) => {
                        const meta = aiPipelineStatusMeta(s.status);
                        return (
                          <Space key={s.stageKey} size={6} wrap>
                            <Tag color={meta.color}>{meta.text}</Tag>
                            <span>{aiPipelineStageText(s.stageKey, s.stageName)}</span>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              耗时 {fmtDuration(s.elapsedMs) ?? '暂无数据'} · {stageCountText(s) ?? '计数 暂无数据'}
                            </Typography.Text>
                          </Space>
                        );
                      })}
                    </Space>
                  ),
                },
              ]}
            />
          ) : null}

          {detail.events.length > 0 ? (
            <Collapse
              ghost
              size="small"
              items={[
                {
                  key: 'events',
                  label: (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      事件流（状态迁移审计，{detail.events.length} 条）
                    </Typography.Text>
                  ),
                  children: (
                    <div style={{ fontSize: 12 }}>
                      {detail.events.map((ev, i) => (
                        <div key={i} style={{ marginBottom: 3 }}>
                          <Typography.Text type="secondary">{fmtDateTime(ev.createdAt)}</Typography.Text>
                          {ev.stageKey ? ` · ${aiPipelineStageText(ev.stageKey)}` : ''}
                          {' · '}
                          <Typography.Text>
                            {(ev.fromStatus || '-') + ' → ' + (ev.toStatus || '-')}
                          </Typography.Text>
                          {ev.reason ? ` · ${ev.reason}` : ''}
                          {ev.operator ? ` · ${ev.operator}` : ''}
                        </div>
                      ))}
                    </div>
                  ),
                },
              ]}
            />
          ) : null}

          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
            重试 / 取消：本轮后端未提供流水线级端点
            （待接 {'POST /api/ai/pipeline/runs/{run_id}/retry'}、
            {' POST /api/ai/pipeline/runs/{run_id}/cancel'}），
            因此这里<b>只读展示</b>、不摆点不动的按钮。任务级重试仍可在「任务队列」签对失败任务执行。
          </Typography.Paragraph>
        </>
      ) : null}

      <PipelineArtifactDrawer
        open={artifactOpen}
        artifacts={detail?.artifacts ?? []}
        runId={detail?.runId}
        onClose={() => setArtifactOpen(false)}
      />
    </Drawer>
  );
}

/** 阶段卡片：计数 / 耗时 / 尝试次数 / 错误 / 输入输出摘要 / 计数器明细 */
function StageCard({
  stage,
  artifactCount,
  onOpenArtifacts,
}: {
  stage: AiPipelineStage;
  artifactCount: number;
  onOpenArtifacts: () => void;
}) {
  const meta = aiPipelineStatusMeta(stage.status);
  const counters = kvEntries(stage.counters);
  const isSkipped = (stage.status || '').toUpperCase() === 'SKIPPED';
  return (
    <div>
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="状态">
          <Space size={6} wrap>
            <Tag color={meta.color}>{meta.text}</Tag>
            {stage.stageKey ? <Typography.Text code>{stage.stageKey}</Typography.Text> : null}
            {stage.seq !== null && stage.seq !== undefined ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                seq {stage.seq}
              </Typography.Text>
            ) : null}
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="耗时">{fmtDuration(stage.elapsedMs) ?? '暂无数据'}</Descriptions.Item>
        <Descriptions.Item label="计量（项）">
          {hasValue(stage.itemCount) || hasValue(stage.itemFailed)
            ? `${stage.itemCount ?? '暂无数据'} 项，其中失败 ${stage.itemFailed ?? '暂无数据'} 项`
            : '暂无数据'}
        </Descriptions.Item>
        <Descriptions.Item label="尝试次数">
          {stage.attempts === null || stage.attempts === undefined
            ? '暂无数据'
            : stage.attempts > 1
              ? `已重试 ${stage.attempts} 次（本阶段重跑会更新同一行并累加 attempts，不会出现重复节点）`
              : `${stage.attempts} 次`}
        </Descriptions.Item>
        <Descriptions.Item label="起止时间">
          {fmtDateTime(stage.startedAt)} ~ {stage.finishedAt ? fmtDateTime(stage.finishedAt) : '未结束'}
        </Descriptions.Item>
        <Descriptions.Item label="输入摘要">
          {stage.inputDigest || '该环节未上报输入摘要（input_digest 为空）'}
        </Descriptions.Item>
        <Descriptions.Item label="输出摘要">
          {stage.outputDigest || '该环节未上报输出摘要（output_digest 为空）'}
        </Descriptions.Item>
        {isSkipped ? (
          <Descriptions.Item label="跳过原因">
            {skipReason(stage) || '该环节未上报跳过原因（SKIPPED 但既没有 error_msg 也没有 counters.reason）'}
          </Descriptions.Item>
        ) : null}
        {stage.errorMsg ? (
          <Descriptions.Item label="错误原因">
            <Typography.Text type="danger">{stage.errorMsg}</Typography.Text>
          </Descriptions.Item>
        ) : null}
      </Descriptions>

      <div style={{ marginTop: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          计数器明细 counters
        </Typography.Text>
        {artifactCount > 0 ? (
          <Button size="small" icon={<FileSearchOutlined />} onClick={onOpenArtifacts}>
            查看产物（{artifactCount}）
          </Button>
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            该 run 未上报任何产物
          </Typography.Text>
        )}
      </div>
      {counters.length > 0 ? (
        <div style={{ marginTop: 4 }}>
          {counters.map(({ key, value }) => (
            <div key={key} style={{ display: 'flex', gap: 8, padding: '2px 0' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12, minWidth: 160, flexShrink: 0 }}>
                {key}
              </Typography.Text>
              <div style={{ fontSize: 12, minWidth: 0, flex: 1 }}>
                <ContentKV value={value} />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          该阶段未上报计数器（counters_json 为空）
        </Typography.Text>
      )}
    </div>
  );
}
