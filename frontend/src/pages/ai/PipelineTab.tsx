/**
 * 「解析流水线」页签（任务流视图的列表层）
 * ----------------------------------------------------------------
 * 位置选择（为什么不改造现有「任务队列」签）：
 *  - 「任务队列」用的是**既有冻结契约** `GET /api/ai/tasks`（`QUEUED/RUNNING/DONE/FAILED` 四态 + 重试），
 *    它现在就在用、还有人在依赖，**本轮不动它**（改它的数据源等于把既有页签换成半成品）；
 *  - 任务流视图用的是**新的** `GET /api/ai/pipeline/runs`（阶段/产物/事件、八态含 PARTIAL/DEAD），
 *    两者的主键（`task_id` vs `run_id`）、状态枚举、可得字段都不一样，硬拼成一张表只能靠
 *    `attachment_id` 猜关联——那会造出一个"看起来对、其实可能错"的视图；
 *  - 所以**新增「解析流水线」签**，并在签标签上挂**进行中角标**（借鉴参考项目"顶栏常驻入口 + 角标"，
 *    见 `usePipelineActiveCount`）。两段式列表（进行中 / 历史）、"只看失败"、行内详情都按参考项目做。
 *
 * 数据纪律（§4.9.2⑥、任务书轮询一节）：
 *  - 列表只当"表头"看：一行一个 run，**不带**阶段明细与产物 content；
 *  - 点行/详情 → `PipelineFlowDrawer`，**打开即重新取一次全量详情**；
 *  - 列表 3s 轮询（仅当有进行中的 run）；详情 1.5s 轮询（抽屉内）；终态即停；页面不可见即暂停；
 *  - 失败行整行红底 + "只看失败"开关 + 失败数角标（参考项目 `JobCenterModal` 的做法）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Checkbox, Empty, Progress, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { aiApi } from '@/api/ai';
import PipelineFlowDrawer from '@/components/ai/PipelineFlowDrawer';
import PipelineQualityBoard from '@/components/ai/PipelineQualityBoard';
import { fmtDuration, hasValue } from '@/components/ai/pipelineFormat';
import { useVisiblePolling } from '@/components/ai/useVisiblePolling';
import { fmtDateTime } from '@/utils/format';
import {
  AiPipelineRun,
  AiPipelineRunDetail,
  aiPipelineStageText,
  aiPipelineStatusMeta,
  aiPipelineTaskTypeText,
  isPipelineActive,
  isPipelineFailed,
} from '@/types/ai';

/** 列表每页条数（列表只含表头字段，50 条开销很小；"进行中/历史"两段都在这一页里切） */
const LIST_PAGE_SIZE = 50;
/** 列表轮询间隔（参考项目 JobCenterModal 是 3s） */
const LIST_POLL_MS = 3000;
/** 历史段的前端分页大小（参考项目历史任务 10 条/页） */
const HISTORY_PAGE_SIZE = 10;

export default function PipelineTab() {
  const [rows, setRows] = useState<AiPipelineRun[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 只看失败（FAILED/DEAD）：与参考项目一致，在**已加载行**上过滤；正交于"进行中/历史"分段 */
  const [onlyFailed, setOnlyFailed] = useState(false);
  /** 打开抽屉的 run（点行/详情按钮都走它） */
  const [openRunId, setOpenRunId] = useState<string | null>(null);
  const [filenameHint, setFilenameHint] = useState<string | null>(null);

  /* ---- 质量看板口径：默认自动选"最新一条进行中，否则最新一条"；用户点开某条后跟着切换 ---- */
  const [boardRunId, setBoardRunId] = useState<string | null>(null);
  const [boardDetail, setBoardDetail] = useState<AiPipelineRunDetail | null>(null);
  const [boardLoading, setBoardLoading] = useState(false);
  const [boardError, setBoardError] = useState<string | null>(null);
  /** 看板已取到的详情（用 ref 判断"这条已经在手"，避免与抽屉的取数重复打一次接口） */
  const boardDetailRef = useRef<AiPipelineRunDetail | null>(null);
  const boardPickedRef = useRef(false);

  const loadBoard = useCallback(async (runId: string) => {
    setBoardLoading(true);
    try {
      const d = await aiApi.pipelineRunDetail(runId);
      boardDetailRef.current = d;
      setBoardDetail(d);
      setBoardError(null);
    } catch (e) {
      setBoardDetail(null);
      boardDetailRef.current = null;
      setBoardError((e as Error).message || '质量看板取数失败');
    } finally {
      setBoardLoading(false);
    }
  }, []);

  const load = useCallback(
    async (silent: boolean, pageNo = 1) => {
      if (!silent) setLoading(true);
      try {
        const res = await aiApi.pipelineRuns({ page: pageNo, size: LIST_PAGE_SIZE });
        setRows((prev) => (pageNo === 1 ? res.records : [...prev, ...res.records]));
        setTotal(res.total);
        setPage(pageNo);
        setError(null);
        // 看板口径自动选取：只在"用户还没选过任何一条"时选一次，避免轮询把它切走
        if (pageNo === 1 && !boardPickedRef.current) {
          const first = res.records.find((r) => isPipelineActive(r.status)) ?? res.records[0];
          if (first) {
            boardPickedRef.current = true;
            setBoardRunId(first.runId);
          }
        }
      } catch (e) {
        if (!silent) {
          setRows([]);
          setTotal(0);
          setError((e as Error).message || '流水线列表读取失败');
        }
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    void load(false, 1);
  }, [load]);

  /** 看板切口径：若这条详情已经在手（抽屉刚取过）就不重复请求 */
  useEffect(() => {
    if (!boardRunId) return;
    if (boardDetailRef.current?.runId === boardRunId) return;
    void loadBoard(boardRunId);
  }, [boardRunId, loadBoard]);

  const activeRows = useMemo(() => rows.filter((r) => isPipelineActive(r.status)), [rows]);
  const historyRows = useMemo(() => rows.filter((r) => !isPipelineActive(r.status)), [rows]);
  const failedRows = useMemo(() => rows.filter((r) => isPipelineFailed(r.status)), [rows]);
  const shownHistory = useMemo(
    () => (onlyFailed ? historyRows.filter((r) => isPipelineFailed(r.status)) : historyRows),
    [historyRows, onlyFailed],
  );

  // 列表轮询：仅当这一页里有进行中的 run（终态全部结束即自动停止）；
  // 且只在"第 1 页"时轮询——用户点过「加载更多」后处于分页浏览状态，
  // 每 3s 整页重取会把已加载的后续页截断（宁可停在手动刷新，也不悄悄丢掉用户翻出来的记录）。
  useVisiblePolling(() => load(true, 1), {
    intervalMs: LIST_POLL_MS,
    enabled: activeRows.length > 0 && page === 1,
  });

  /** 有进行中的任务：不显示页码，改成"整页重取"（避免追加模式与轮询打架） */
  const reloadFirstPage = useCallback(() => {
    boardPickedRef.current = !!boardRunId;
    void load(false, 1);
  }, [boardRunId, load]);

  const openDetail = useCallback((row: AiPipelineRun) => {
    setOpenRunId(row.runId);
    setFilenameHint(row.filename ?? null);
  }, []);

  /** 抽屉每次成功取详情都会回调：让看板跟着切到这条（也顺带把它的快照留在页面上） */
  const handleDetailLoaded = useCallback((d: AiPipelineRunDetail) => {
    boardPickedRef.current = true;
    boardDetailRef.current = d;
    setBoardDetail(d);
    setBoardError(null);
    setBoardRunId(d.runId);
  }, []);

  const columns: ColumnsType<AiPipelineRun> = [
    {
      title: '附件',
      key: 'attachment',
      width: 220,
      ellipsis: true,
      render: (_, row) => (
        <Space size={4}>
          <span>{row.filename || (row.attachmentId ? `附件 #${row.attachmentId}` : '未上报附件')}</span>
          {row.filename && row.attachmentId ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              #{row.attachmentId}
            </Typography.Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: '流水线',
      key: 'runId',
      width: 210,
      render: (_, row) => (
        <Tooltip title="点击查看任务流（阶段条 / 阶段卡片 / 产物）">
          <a onClick={() => openDetail(row)}>
            <Typography.Text code style={{ fontSize: 12 }}>
              {row.runId}
            </Typography.Text>
          </a>
        </Tooltip>
      ),
    },
    {
      title: '类型',
      dataIndex: 'taskType',
      width: 100,
      render: (v?: string | null) => <Tag>{aiPipelineTaskTypeText(v)}</Tag>,
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 80,
      align: 'right',
      render: (v?: number | null) =>
        hasValue(v) ? (v && v > 0 ? <Tag color="blue">{v}</Tag> : <span>{v}</span>) : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v: string) => {
        const meta = aiPipelineStatusMeta(v);
        const tag = <Tag color={meta.color}>{meta.text}</Tag>;
        // PARTIAL 必须说明"差在哪"：可检索但不完整，与 DONE 的绿色不是一回事
        return (v || '').toUpperCase() === 'PARTIAL' ? (
          <Tooltip title="部分成功：已入库的切片可检索，但有项需人工复核（不是全部成功，也不是失败）">
            {tag}
          </Tooltip>
        ) : (
          tag
        );
      },
    },
    {
      title: '当前阶段 / 计量',
      key: 'stage',
      width: 190,
      render: (_, row) => {
        const name = row.stageKey || row.stage ? aiPipelineStageText(row.stageKey, row.stage) : null;
        if (!name && !hasValue(row.stageTotal)) return '暂无数据';
        const done = row.stageDone;
        const all = row.stageTotal;
        return (
          <div style={{ fontSize: 12 }}>
            <div>{name || '阶段名未上报'}</div>
            {all !== null && all !== undefined && all > 0 ? (
              <Progress
                percent={Math.min(100, Math.round(((done ?? 0) / all) * 100))}
                size="small"
                style={{ marginBottom: 0 }}
              />
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                计量 {done ?? '暂无数据'}
                {all === 0 ? '（总量未知，显示为不确定进度）' : ''}
              </Typography.Text>
            )}
          </div>
        );
      },
    },
    {
      title: '耗时',
      dataIndex: 'elapsedMs',
      width: 90,
      render: (v?: number | null) => fmtDuration(v) ?? '-',
    },
    {
      title: '失败原因',
      dataIndex: 'errorMsg',
      ellipsis: true,
      render: (v?: string | null) =>
        v ? (
          <Tooltip title={v}>
            <Typography.Text type="danger" style={{ fontSize: 12 }}>
              {v}
            </Typography.Text>
          </Tooltip>
        ) : (
          '-'
        ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (v?: string | null) => fmtDateTime(v),
    },
    {
      title: '操作',
      key: 'op',
      width: 90,
      fixed: 'right',
      render: (_, row) => (
        <Button size="small" type="link" icon={<SyncOutlined />} onClick={() => openDetail(row)}>
          详情
        </Button>
      ),
    },
  ];

  const tableProps = {
    size: 'small' as const,
    rowKey: 'runId',
    columns,
    scroll: { x: 1320 },
    // 失败行整行红底（参考项目 JobProgressModal 的 bg-danger-soft，本项目用内联样式，不新增全局 CSS）
    onRow: (row: AiPipelineRun) =>
      isPipelineFailed(row.status) ? { style: { background: '#fff1f0' } } : {},
  };

  return (
    <div>
      <PipelineQualityBoard
        detail={boardDetail}
        loading={boardLoading}
        onRefresh={boardRunId ? () => void loadBoard(boardRunId) : undefined}
      />

      {boardError ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="质量看板取数失败（流水线详情接口）"
          description={
            <Typography.Text style={{ fontSize: 12 }}>
              {boardError}
              ——看板显示"暂无数据"是<b>取不到</b>，不是"质量没问题"。
            </Typography.Text>
          }
        />
      ) : null}

      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={reloadFirstPage}>
            刷新
          </Button>
          <Tooltip title="只看有失败/死信记录的流水线（进行中段不受影响，便于对照）">
            <Checkbox checked={onlyFailed} onChange={(e) => setOnlyFailed(e.target.checked)}>
              只看失败
              {failedRows.length > 0 ? (
                <Tag color="red" style={{ marginLeft: 6 }}>
                  {failedRows.length}
                </Tag>
              ) : null}
            </Checkbox>
          </Tooltip>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            列表只给表头（无阶段明细与产物）；点「详情」打开任务流。
            {activeRows.length > 0
              ? page === 1
                ? `有 ${activeRows.length} 个在跑，每 ${LIST_POLL_MS / 1000}s 自动刷新。`
                : `有 ${activeRows.length} 个在跑；已进入分页浏览，自动刷新暂停（点「刷新」重取第一页）。`
              : '当前没有进行中的任务，已停止自动刷新。'}
          </Typography.Text>
        </Space>
      </Card>

      {error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message="流水线列表读取失败"
          description={
            <>
              <div>{error}</div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                接口是主系统 {'/api/ai/pipeline/runs'}；这里失败<b>不代表没有解析任务</b>，
                既有「任务队列」签读的是另一套接口（{'/api/ai/tasks'}），那边仍可查看与重试。
              </Typography.Text>
            </>
          }
        />
      ) : null}

      {failedRows.length > 0 && !onlyFailed ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`已加载的记录里有 ${failedRows.length} 条失败/死信流水线`}
          description="勾上「只看失败」可集中查看；失败行已整行标红，点「详情」看是哪一步失败、失败原因与尝试次数。"
          action={
            <Button size="small" onClick={() => setOnlyFailed(true)}>
              去查看
            </Button>
          }
        />
      ) : null}

      <div style={{ fontWeight: 600, marginBottom: 6 }}>
        进行中（{activeRows.length}）
      </div>
      {activeRows.length > 0 ? (
        <Table<AiPipelineRun>
          {...tableProps}
          dataSource={activeRows}
          pagination={false}
          style={{ marginBottom: 16 }}
        />
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="当前没有正在跑的解析任务"
          style={{ marginBottom: 16 }}
        />
      )}

      <div style={{ fontWeight: 600, marginBottom: 6 }}>
        历史（{shownHistory.length}
        {onlyFailed ? ` / 已加载 ${historyRows.length}` : ''}
        {rows.length < total ? `，已加载 ${rows.length} / 共 ${total}` : ''}）
      </div>
      {shownHistory.length > 0 ? (
        <Table<AiPipelineRun>
          {...tableProps}
          dataSource={shownHistory}
          pagination={{
            pageSize: HISTORY_PAGE_SIZE,
            size: 'small',
            showSizeChanger: false,
            showTotal: (t) => `共 ${t} 条`,
          }}
        />
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={onlyFailed ? '没有失败/死信的流水线' : '还没有流水线记录'}
        />
      )}

      {rows.length < total ? (
        <div style={{ textAlign: 'center', marginTop: 12 }}>
          <Button loading={loading} onClick={() => void load(false, page + 1)}>
            加载更多（已加载 {rows.length} / 共 {total}）
          </Button>
          <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
            后端按 page/size 分页；这里只是"取下一页并追加"。想看"进行中"段请点「刷新」回到第 1 页
            （排序由后端定，前端不重排）。
          </Typography.Text>
        </div>
      ) : null}

      {/* 任务流抽屉：打开即重取全量、未终态 1.5s 轮询、终态即停（页面不可见也停） */}
      <PipelineFlowDrawer
        open={!!openRunId}
        runId={openRunId}
        filenameHint={filenameHint}
        onDetailLoaded={handleDetailLoaded}
        onClose={() => setOpenRunId(null)}
      />
    </div>
  );
}
