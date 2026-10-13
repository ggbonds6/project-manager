/**
 * 质量看板（轻量，任务流视图顶部）
 * ----------------------------------------------------------------
 * 为什么是"轻量"而不是 §4.6 的六项聚合看板：设计文档里的聚合口径是 API-6
 * （`GET /api/ai/quality?projectId=&from=&to=`，按项目/时间窗 AVG+P95），**本轮冻结契约里没有它**。
 * 因此这里**不发明聚合接口**，只把**当前这一条流水线**已经上报的字段摆出来：
 *
 * | 卡片 | 字段来源（全部来自详情接口） |
 * | --- | --- |
 * | 页级空页率 | `artifacts[type=page_digest].content.empty_pages / .page_count`（后端给了 `empty_page_ratio` 就用它） |
 * | 清洗剔除字符比 | `artifacts[type=clean_diff].content.removed_chars_ratio`（或 `removed_chars / total_chars`） |
 * | 切片数 / 平均长度 | `artifacts[type=chunk_manifest].content.chunk_count / .avg_char_len` |
 * | 向量化成功率 | `artifacts[type=embed_batch].content.embedded / .failed`（后端给了 `embed_success_rate` 就用它） |
 * | 失败阶段分布 | 详情的 `stages[].status` 逐行计数 |
 *
 * 两条纪律：
 *  ① **后端没给的字段一律显示"暂无数据"**，不用 0/"—"/推算值冒充（任务书明确"不要伪造计算"）；
 *  ② 由"分子+分母"算出的比率会在卡片来源里**写明是哪两个字段算的**（可对账），
 *     并且**不做阈值判定/红黄着色**——阈值口径属于后端（§4.6 的建议阈值只在 tooltip 里作为参考引用），
 *     前端自己判"是否告警"会与后端看板分叉。
 */
import { Button, Card, Col, Row, Space, Tag, Tooltip, Typography } from 'antd';
import { InfoCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import type { AiPipelineArtifact, AiPipelineRunDetail } from '@/types/ai';
import { aiPipelineStatusMeta, isPipelineActive } from '@/types/ai';
import { fmtDateTime } from '@/utils/format';

interface Props {
  /** 当前口径的流水线详情（为 null = 还没取到，全部显示"暂无数据"） */
  detail: AiPipelineRunDetail | null;
  /** 正在取详情（首次加载/手动刷新） */
  loading?: boolean;
  /** 手动重取（看板是"快照"，不参与列表轮询，见组件注释末段） */
  onRefresh?: () => void;
}

/** 详情里的产物按类型取"最新版本"那一行（产物只 INSERT、rev 递增） */
function latestArtifact(detail: AiPipelineRunDetail | null, type: string): AiPipelineArtifact | null {
  if (!detail) return null;
  const rows = detail.artifacts.filter((a) => a.artifactType === type);
  if (rows.length === 0) return null;
  return rows.reduce((acc, cur) => ((cur.artifactRev ?? 0) >= (acc.artifactRev ?? 0) ? cur : acc), rows[0]);
}

/**
 * 从摘要里取数值：同时看顶层与 `summary` 子对象（两种后端形态都遇到过），
 * 只认真正的数值/数字字符串；取不到返回 null（调用方据此写"暂无数据"）。
 */
function numFrom(content: unknown, ...keys: string[]): number | null {
  if (!content || typeof content !== 'object') return null;
  const root = content as Record<string, unknown>;
  const scopes = [root, root.summary && typeof root.summary === 'object' ? (root.summary as Record<string, unknown>) : {}];
  for (const scope of scopes) {
    for (const k of keys) {
      const v = scope[k];
      if (typeof v === 'number' && Number.isFinite(v)) return v;
      if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
    }
  }
  return null;
}

/** 比率展示：后端可能给 0~1 的小数，也可能给 0~100 的百分数（两种都在契约讨论里出现过） */
function ratioText(v: number): string {
  if (v >= 0 && v <= 1) return `${(v * 100).toFixed(1)}%`;
  return `${v.toFixed(1)}%`;
}

const NONE = <Typography.Text type="secondary">暂无数据</Typography.Text>;

/** 单张指标卡：值 + 来源说明（来源必须可见，否则看板无法对账） */
function MetricCard({
  title,
  value,
  sub,
  source,
}: {
  title: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  source: string;
}) {
  return (
    <Card size="small" style={{ height: '100%' }}>
      <Space size={4} align="center">
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {title}
        </Typography.Text>
        <Tooltip title={source}>
          <InfoCircleOutlined style={{ color: '#bfbfbf', fontSize: 12 }} />
        </Tooltip>
      </Space>
      <div style={{ fontSize: 20, fontWeight: 600, marginTop: 2, lineHeight: 1.3 }}>{value}</div>
      {sub ? <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 2 }}>{sub}</div> : null}
    </Card>
  );
}

export default function PipelineQualityBoard({ detail, loading = false, onRefresh }: Props) {
  const pageDigest = latestArtifact(detail, 'page_digest');
  const cleanDiff = latestArtifact(detail, 'clean_diff');
  const chunkManifest = latestArtifact(detail, 'chunk_manifest');
  const embedBatch = latestArtifact(detail, 'embed_batch');

  /* ---- ① 页级空页率：优先用后端给的比率，否则用 empty_pages / page_count（可对账） ---- */
  const emptyRatio = numFrom(pageDigest?.content, 'empty_page_ratio', 'empty_ratio');
  const emptyPages = numFrom(pageDigest?.content, 'empty_pages');
  const pageCount = numFrom(pageDigest?.content, 'page_count', 'pages');
  const emptyPageValue =
    emptyRatio !== null
      ? ratioText(emptyRatio)
      : emptyPages !== null && pageCount !== null && pageCount > 0
        ? ratioText(emptyPages / pageCount)
        : NONE;
  const emptyPageSub =
    emptyPages !== null || pageCount !== null
      ? `空页 ${emptyPages ?? '暂无数据'} / 共 ${pageCount ?? '暂无数据'} 页`
      : '未上报 page_digest 产物';

  /* ---- ② 清洗剔除字符比 ---- */
  const removedRatio = numFrom(cleanDiff?.content, 'removed_chars_ratio', 'removed_ratio');
  const removedChars = numFrom(cleanDiff?.content, 'removed_chars', 'removed_char_count');
  const totalChars = numFrom(cleanDiff?.content, 'total_chars', 'chars_before');
  const removedLines = numFrom(cleanDiff?.content, 'removed_lines');
  const removedValue =
    removedRatio !== null
      ? ratioText(removedRatio)
      : removedChars !== null && totalChars !== null && totalChars > 0
        ? ratioText(removedChars / totalChars)
        : NONE;
  const removedSub =
    removedChars !== null || removedLines !== null
      ? `剔除 ${removedChars ?? '暂无数据'} 字符 / ${removedLines ?? '暂无数据'} 行`
      : '未上报 clean_diff 产物';

  /* ---- ③ 切片数 / 平均长度 ---- */
  const chunkCount = numFrom(chunkManifest?.content, 'chunk_count', 'chunks');
  const avgLen = numFrom(chunkManifest?.content, 'avg_char_len', 'avg_len', 'avg_chunk_len');
  const maxLen = numFrom(chunkManifest?.content, 'max_char_len');
  const chunkValue =
    chunkCount !== null || avgLen !== null ? (
      <Space size={6} wrap>
        <span>{chunkCount !== null ? `${chunkCount} 片` : '切片数 暂无数据'}</span>
        <Typography.Text type="secondary" style={{ fontSize: 14 }}>
          /
        </Typography.Text>
        <span>{avgLen !== null ? `平均 ${Math.round(avgLen)} 字` : '平均长度 暂无数据'}</span>
      </Space>
    ) : (
      NONE
    );
  const chunkSub = maxLen !== null ? `最长切片 ${maxLen} 字` : '未上报 chunk_manifest 产物';

  /* ---- ④ 向量化成功率 ---- */
  const embedRate = numFrom(embedBatch?.content, 'embed_success_rate', 'success_rate');
  const embedded = numFrom(embedBatch?.content, 'embedded', 'success', 'ok');
  const embedFailed = numFrom(embedBatch?.content, 'failed');
  const embedTotal =
    embedded !== null && embedFailed !== null ? embedded + embedFailed : numFrom(embedBatch?.content, 'total');
  const embedValue =
    embedRate !== null
      ? ratioText(embedRate)
      : embedded !== null && embedTotal !== null && embedTotal > 0
        ? ratioText(embedded / embedTotal)
        : NONE;
  const embedSub =
    embedded !== null || embedFailed !== null
      ? `成功 ${embedded ?? '暂无数据'} / 失败 ${embedFailed ?? '暂无数据'}`
      : '未上报 embed_batch 产物';

  /* ---- ⑤ 失败阶段分布（按 stages[].status 逐行计数） ---- */
  const stageStatusCount = new Map<string, number>();
  (detail?.stages ?? []).forEach((s) => {
    const k = (s.status || 'UNKNOWN').toUpperCase();
    stageStatusCount.set(k, (stageStatusCount.get(k) ?? 0) + 1);
  });
  const failedStageCount = (stageStatusCount.get('FAILED') ?? 0) + (stageStatusCount.get('DEAD') ?? 0);
  const stageDistValue =
    stageStatusCount.size === 0 ? (
      NONE
    ) : (
      <Space size={4} wrap>
        {Array.from(stageStatusCount.entries()).map(([status, count]) => {
          const meta = aiPipelineStatusMeta(status);
          return (
            <Tag key={status} color={meta.color} style={{ marginInlineEnd: 0 }}>
              {meta.text} {count}
            </Tag>
          );
        })}
      </Space>
    );

  return (
    <Card
      size="small"
      style={{ marginBottom: 12 }}
      title={
        <Space size={8} wrap>
          <span>质量看板</span>
          <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
            口径：当前这一条流水线（后端暂未提供跨项目聚合接口 API-6）
          </Typography.Text>
        </Space>
      }
      extra={
        onRefresh ? (
          <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={onRefresh}>
            重取
          </Button>
        ) : null
      }
    >
      <Space size={8} wrap style={{ marginBottom: 8 }}>
        {detail ? (
          <>
            <Typography.Text style={{ fontSize: 12 }}>
              run <Typography.Text code>{detail.runId}</Typography.Text>
            </Typography.Text>
            <Tag color={aiPipelineStatusMeta(detail.status).color}>{aiPipelineStatusMeta(detail.status).text}</Tag>
            {isPipelineActive(detail.status) ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                该流水线还在跑（此处是快照，点右侧「重取」刷新）
              </Typography.Text>
            ) : null}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              快照：{detail.createdAt ? `创建于 ${fmtDateTime(detail.createdAt)}` : '创建时间未上报'}
            </Typography.Text>
          </>
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            还没有取到任何流水线详情 —— 下方列表里点任意一条记录查看任务流，看板就会跟着切到那一条。
          </Typography.Text>
        )}
      </Space>

      <Row gutter={[8, 8]}>
        <Col xs={24} sm={12} lg={8} xl={4}>
          <MetricCard
            title="页级空页率"
            value={emptyPageValue}
            sub={emptyPageSub}
            source={'字段：page_digest 产物的 empty_pages / page_count（或后端直接给的 empty_page_ratio）。'
              + '文档 §4.6 的参考阈值：> 10% 需关注（建议阈值，前端不据此判红黄）'}
          />
        </Col>
        <Col xs={24} sm={12} lg={8} xl={5}>
          <MetricCard
            title="清洗剔除字符比"
            value={removedValue}
            sub={removedSub}
            source={'字段：clean_diff 产物的 removed_chars_ratio，或 removed_chars / total_chars。'
              + '文档 §4.6 的参考阈值：> 15% 说明清洗规则可能过头'}
          />
        </Col>
        <Col xs={24} sm={12} lg={8} xl={5}>
          <MetricCard
            title="切片数 / 平均长度"
            value={chunkValue}
            sub={chunkSub}
            source={'字段：chunk_manifest 产物的 chunk_count / avg_char_len / max_char_len。'
              + '文档 §4.6 的参考区间：平均 350~600 字'}
          />
        </Col>
        <Col xs={24} sm={12} lg={8} xl={5}>
          <MetricCard
            title="向量化成功率"
            value={embedValue}
            sub={embedSub}
            source={'字段：embed_batch 产物的 embedded / failed（或后端直接给的 embed_success_rate）。'
              + 'README 口径：lazy_backfill 正常应为 0'}
          />
        </Col>
        <Col xs={24} sm={12} lg={8} xl={5}>
          <MetricCard
            title="失败阶段分布"
            value={stageDistValue}
            sub={`本 run 共 ${detail?.stages.length ?? 0} 个阶段行，其中失败/死信 ${failedStageCount} 个`}
            source={'字段：详情 stages[].status 逐行计数（后端未提供聚合字段，这是"数行数"而不是估算）。'
              + 'FAILED = 该阶段失败；DEAD = 重试耗尽'}
          />
        </Col>
      </Row>

      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
        本看板只反映<b>当前这一条</b>流水线：冻结契约里只有列表与详情两个接口，跨项目/时间窗的聚合
        （§4.6 的 API-6）还没有实现，所以这里不放"全局质量"图表——宁可显示"暂无数据"，也不在前端自算一个
        与后端对不上的比率。
      </Typography.Paragraph>
    </Card>
  );
}
