/**
 * 溯源抽屉（从问答答案的 `[n]` 一路回溯到原始附件页）
 * ----------------------------------------------------------------
 * 对应《RAG流水线设计与并发管理》§4.5.1 的六跳（前端把它呈现为 4 段可读的"跳"）：
 *
 * | 跳 | 数据来源（**字段级**） | 缺数据时显示 |
 * | --- | --- | --- |
 * | ① 答案 `[n]` → 引用项 | `citations[].index` / `.score` / `.snippet` | "该环节未上报"（引用项本身缺失） |
 * | ② 引用项 → 切片 | `citations[].chunk_key`（兼容 `chunkKey`，见 `citationChunkKey`） | "该环节未上报：主系统未回填 chunk_key" |
 * | ③ 切片 → 清洗后文本 / 产物摘要 | `citations[].snippet` + 该附件流水线详情的 `artifacts[]`（page_digest / clean_diff / chunk_manifest） | 逐个产物类型分别写"该 run 未上报 X 产物" |
 * | ④ 附件 + 页码 → 原文第 N 页 | `citations[].attachment_id` + `.page_no` → 复用既有 `AttachmentPreviewModal`（PDF 走 `#page=N`） | "引用未回填 attachment_id"（仍按 docId/文件名兜底映射一次） |
 *
 * 三条刻意的设计：
 *  ① **不新增接口**：产物摘要就从冻结契约的详情接口里取（`GET /api/ai/pipeline/runs/{run_id}`），
 *     设计里的 API-4（`/api/ai/chunks/{chunkKey}` 切片全文）不在本轮冻结契约内，故不调用；
 *     因此"清洗后文本"一栏显示的是**引用片段**（`snippet`，检索时返回的），并明确标注它不是清洗后全文。
 *  ② **缺一层就写一层**：任何缺失都渲染"该环节未上报：<缺什么>"，绝不留空白，
 *     也不把 `docId + pageNo` 包装成"等价于 chunk_key"的假结论。
 *  ③ **不轮询**：踪迹来自已落库的产物与引用（静态数据），打开即取一次；
 *     若定位到的 run 还在跑，提供「查看该 run 的任务流」跳转 —— 那里有 1.5s 轮询与投影兜底提示。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Drawer,
  Empty,
  Space,
  Spin,
  Steps,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  FileSearchOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import { attachmentUrl } from '@/api/project';
import { aiApi } from '@/api/ai';
import PipelineArtifactDrawer, { ContentKV } from '@/components/ai/PipelineArtifactDrawer';
import PipelineFlowDrawer from '@/components/ai/PipelineFlowDrawer';
import { findChunkRecord, hasValue } from '@/components/ai/pipelineFormat';
import { useAttachmentPreview } from '@/components/ai/useAttachmentPreview';
import { fmtDateTime } from '@/utils/format';
import {
  AiCitation,
  AiPipelineArtifact,
  AiPipelineRun,
  AiPipelineRunDetail,
  aiPipelineArtifactText,
  aiPipelineStatusMeta,
  citationChunkKey,
  isPipelineActive,
} from '@/types/ai';

interface Props {
  /** 要溯源的引用项（null = 关闭状态，不取数） */
  citation: AiCitation | null;
  open: boolean;
  onClose: () => void;
}

/** ③ 里要展示摘要的产物类型（§4.5.1 第 5 跳明确点名这三类） */
const TRACE_ARTIFACT_TYPES = ['page_digest', 'clean_diff', 'chunk_manifest'];

/** "未上报"统一文案：红/橙色 + 说清缺的是什么，避免用户以为是"加载中"或"没有内容" */
function Missing({ what }: { what: string }) {
  return (
    <Typography.Text style={{ color: '#d46b08', fontSize: 12 }}>
      该环节未上报：{what}
    </Typography.Text>
  );
}

/** 取某类型的"最新版本"产物（产物只 INSERT、rev 递增） */
function latestArtifact(detail: AiPipelineRunDetail | null, type: string): AiPipelineArtifact | null {
  if (!detail) return null;
  const rows = detail.artifacts.filter((a) => a.artifactType === type);
  if (rows.length === 0) return null;
  return rows.reduce((acc, cur) => ((cur.artifactRev ?? 0) >= (acc.artifactRev ?? 0) ? cur : acc), rows[0]);
}

export default function CitationTraceDrawer({ citation, open, onClose }: Props) {
  /** 附件原文预览：与问答面板共用同一实现（含 docId → attachmentId 的兜底映射） */
  const preview = useAttachmentPreview();

  const [locatedRun, setLocatedRun] = useState<AiPipelineRun | null>(null);
  const [detail, setDetail] = useState<AiPipelineRunDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 定位不到 run 的原因（如实展示，不用空白代替） */
  const [locateNote, setLocateNote] = useState<string | null>(null);
  /** 嵌套打开"该 run 的任务流" */
  const [flowOpen, setFlowOpen] = useState(false);
  const [artifactOpen, setArtifactOpen] = useState(false);

  const chunkKey = citation ? citationChunkKey(citation) : null;
  const attachmentId = citation?.attachmentId ?? null;
  const pageNo = citation?.pageNo ?? null;

  /**
   * 定位该引用所属的流水线记录：`GET /runs?attachment_id=` 取该附件的记录，
   * 优先"最近一次已结束的（非 PENDING/RUNNING）"，没有就取最近一条。
   *
   * 诚实标注一个**已知局限**：冻结契约没有"chunk_key → run_id"的反查，
   * 同一附件解析多次时这里只能取最近的一条，因此界面会同时显示 run_id 与状态，供用户判断。
   */
  const load = useCallback(async () => {
    if (!citation) return;
    if (!attachmentId) {
      setLocatedRun(null);
      setDetail(null);
      setLocateNote('引用项里没有 attachment_id，无法按附件定位流水线记录（§4.5.1 第 6 跳）');
      return;
    }
    setLoading(true);
    setError(null);
    setLocateNote(null);
    try {
      const res = await aiApi.pipelineRuns({ attachmentId, page: 1, size: 20 });
      const picked = res.records.find((r) => !isPipelineActive(r.status)) ?? res.records[0] ?? null;
      setLocatedRun(picked);
      if (!picked) {
        setDetail(null);
        setLocateNote(`该附件（#${attachmentId}）还没有流水线记录：产物摘要无从取起`);
        return;
      }
      setDetail(await aiApi.pipelineRunDetail(picked.runId));
    } catch (e) {
      setLocatedRun(null);
      setDetail(null);
      setError((e as Error).message || '流水线记录读取失败');
    } finally {
      setLoading(false);
    }
  }, [citation, attachmentId]);

  // 打开即取一次（不轮询：这里是已落库的产物与引用，属静态数据）
  useEffect(() => {
    if (!open || !citation) return;
    void load();
  }, [open, citation, load]);

  /** 命中切片在 chunk_manifest 里的逐片记录（后端把清单放进摘要时才有） */
  const chunkRecord = useMemo(() => {
    if (!chunkKey) return null;
    const manifest = latestArtifact(detail, 'chunk_manifest');
    return manifest ? findChunkRecord(manifest.content, chunkKey) : null;
  }, [detail, chunkKey]);

  const openOriginal = () => {
    if (!citation) return;
    void preview.open({
      attachmentId: citation.attachmentId,
      docId: citation.docId,
      filename: citation.filename,
      pageNo: citation.pageNo,
    });
  };

  const canTryOpen = !!(citation?.attachmentId || citation?.docId || citation?.filename);

  const items = [
    {
      title: '① 答案角标 → 引用项',
      status: 'finish' as const,
      icon: <CheckCircleOutlined style={{ color: '#52c41a' }} />,
      description: citation ? (
        <Space direction="vertical" size={2} style={{ width: '100%' }}>
          <Space size={6} wrap>
            <Tag color="blue" style={{ marginInlineEnd: 0 }}>
              [{citation.index}]
            </Tag>
            <span>{citation.filename || citation.docId || '未知文档'}</span>
            {pageNo ? <Tag>第 {pageNo} 页</Tag> : null}
            {typeof citation.score === 'number' ? (
              <Tooltip title="检索阶段的打分（精排后分数，口径由后端给；前端不做二次换算）">
                <Tag color="geekblue">score {citation.score.toFixed(2)}</Tag>
              </Tooltip>
            ) : null}
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            doc_id：{citation.docId || '该环节未上报'}
          </Typography.Text>
        </Space>
      ) : (
        <Missing what="引用项本身（答案里没有这条 citation）" />
      ),
    },
    {
      title: '② 引用项 → 切片',
      status: chunkKey ? ('finish' as const) : ('wait' as const),
      icon: chunkKey ? (
        <CheckCircleOutlined style={{ color: '#52c41a' }} />
      ) : (
        <ExclamationCircleOutlined style={{ color: '#faad14' }} />
      ),
      description: (
        <div>
          {chunkKey ? (
            <>
              <Typography.Text code copyable style={{ fontSize: 12, wordBreak: 'break-all' }}>
                {chunkKey}
              </Typography.Text>
              <div style={{ fontSize: 12, color: '#8c8c8c' }}>
                切片键形如 docId:pageNo:sha1:cleanRev:chunkRev（§4.5.1 第 3 跳）
              </div>
              {chunkRecord ? (
                <div style={{ marginTop: 6 }}>
                  <Typography.Text style={{ fontSize: 12, color: '#389e0d' }}>
                    该 run 的 chunk_manifest 里有这一片的逐片记录：
                  </Typography.Text>
                  <ContentKV value={chunkRecord} />
                </div>
              ) : null}
            </>
          ) : (
            <>
              <Missing what={'citations[].chunk_key（主系统尚未回填，§4.5.1 第 3 跳标记为「缺失」）'} />
              <div style={{ fontSize: 12, color: '#8c8c8c' }}>
                仍可用第 ④ 跳的 doc_id + 页码打开原文；但无法定位到"具体是哪一片"，
                也就看不到逐片产物。
              </div>
            </>
          )}
        </div>
      ),
    },
    {
      title: '③ 切片 → 清洗后文本 / 产物摘要',
      status: detail ? ('finish' as const) : ('wait' as const),
      icon: detail ? (
        <CheckCircleOutlined style={{ color: '#52c41a' }} />
      ) : (
        <ExclamationCircleOutlined style={{ color: '#faad14' }} />
      ),
      description: (
        <div>
          {/* 3.1 引用片段（检索时返回的原文片段） */}
          <Descriptions size="small" column={1} bordered style={{ marginBottom: 8 }}>
            <Descriptions.Item label="引用片段（snippet）">
              {citation?.snippet ? (
                <Typography.Text style={{ fontSize: 12 }}>
                  {citation.snippet.length > 240 ? `${citation.snippet.slice(0, 240)}…` : citation.snippet}
                </Typography.Text>
              ) : (
                <Missing what="citations[].snippet" />
              )}
            </Descriptions.Item>
            <Descriptions.Item label="清洗后切片全文">
              <Tooltip title="设计里的 API-4（/api/ai/chunks/{chunkKey}）不在本轮冻结契约内，故未调用；这里不伪造全文">
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  该环节未上报：主系统未提供"按切片键取清洗后全文"的接口（§4.5.2 API-4，设计中）
                </Typography.Text>
              </Tooltip>
            </Descriptions.Item>
          </Descriptions>

          {/* 3.2 产物摘要（page_digest / clean_diff / chunk_manifest） */}
          {loading ? (
            <Spin size="small" />
          ) : locatedRun ? (
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space size={6} wrap>
                <Typography.Text style={{ fontSize: 12 }}>
                  产物取自流水线 <Typography.Text code>{locatedRun.runId}</Typography.Text>
                </Typography.Text>
                <Tag color={aiPipelineStatusMeta(locatedRun.status).color}>
                  {aiPipelineStatusMeta(locatedRun.status).text}
                </Tag>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  创建于 {fmtDateTime(locatedRun.createdAt)}
                </Typography.Text>
                {isPipelineActive(locatedRun.status) ? (
                  <Tag color="processing">该 run 仍在跑，产物可能还没写全</Tag>
                ) : null}
                <Button
                  size="small"
                  icon={<FileSearchOutlined />}
                  onClick={() => setFlowOpen(true)}
                >
                  查看该 run 的任务流
                </Button>
                {detail?.artifacts.length ? (
                  <Button size="small" onClick={() => setArtifactOpen(true)}>
                    全部产物（{detail.artifacts.length}）
                  </Button>
                ) : null}
              </Space>

              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                定位口径：按 attachment_id 取该附件最近一条<b>已结束</b>的流水线记录；
                同一附件多次解析时这里可能不是产出该切片的那一次（契约没有 chunk_key → run_id 反查）。
              </Typography.Text>

              {TRACE_ARTIFACT_TYPES.map((type) => {
                const a = latestArtifact(detail, type);
                if (!a) {
                  return (
                    <div key={type}>
                      <Tag>{aiPipelineArtifactText(type)}</Tag>
                      <Missing what={`该 run 未上报 ${type} 产物`} />
                    </div>
                  );
                }
                return (
                  <Collapse
                    key={type}
                    size="small"
                    items={[
                      {
                        key: type,
                        label: (
                          <Space size={6} wrap>
                            <Tag color="blue">{aiPipelineArtifactText(type)}</Tag>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              rev {a.artifactRev ?? '-'}
                              {hasValue(a.itemCount) ? ` · ${a.itemCount} 项` : ''}
                              {hasValue(a.charCount) ? ` · ${a.charCount} 字符` : ''}
                              {a.sha1 ? ` · sha1 ${a.sha1.slice(0, 8)}` : ''}
                            </Typography.Text>
                          </Space>
                        ),
                        children: hasValue(a.content) ? (
                          <ContentKV value={a.content} />
                        ) : (
                          <Missing what={`${type} 只有元数据、没有摘要内容`} />
                        ),
                      },
                    ]}
                  />
                );
              })}
            </Space>
          ) : error ? (
            <Alert type="warning" showIcon message="无法取到产物摘要" description={error} />
          ) : (
            <Missing what={locateNote || '该附件的流水线记录'} />
          )}
        </div>
      ),
    },
    {
      title: '④ 所属附件 / 页码 → 原文第 N 页',
      status: canTryOpen ? ('finish' as const) : ('wait' as const),
      icon: canTryOpen ? (
        <CheckCircleOutlined style={{ color: '#52c41a' }} />
      ) : (
        <ExclamationCircleOutlined style={{ color: '#faad14' }} />
      ),
      description: (
        <div>
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="主系统附件">
              {attachmentId ? (
                `附件 #${attachmentId}`
              ) : (
                <Missing what="citations[].attachment_id（主系统未回填；点击时会按 doc_id / 文件名兜底映射一次）" />
              )}
            </Descriptions.Item>
            <Descriptions.Item label="页码">
              {pageNo ? `第 ${pageNo} 页` : <Missing what="citations[].page_no" />}
            </Descriptions.Item>
          </Descriptions>
          <Space wrap style={{ marginTop: 8 }}>
            <Button type="primary" size="small" onClick={openOriginal} loading={preview.opening}>
              {pageNo ? `打开原文第 ${pageNo} 页` : '打开附件原文'}
            </Button>
            {attachmentId ? (
              // 与预览弹窗同一 URL 构造（attachmentUrl(id,'inline')），PDF 阅读器认 #page=N
              <Button
                size="small"
                icon={<LinkOutlined />}
                href={attachmentUrl(attachmentId, 'inline') + (pageNo && pageNo > 0 ? `#page=${pageNo}` : '')}
                target="_blank"
                rel="noreferrer"
              >
                新窗口打开
              </Button>
            ) : null}
          </Space>
          <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>
            用的是主系统既有的附件预览组件（与文档库"查看"、问答引用点击同一实现），
            因此这里的页码跳转口径与它们完全一致。
          </div>
        </div>
      ),
    },
  ];

  return (
    <Drawer
      title={
        <Space size={8} wrap>
          <span>溯源</span>
          {citation ? (
            <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
              [{citation.index}] {citation.filename || citation.docId || '未知文档'}
              {pageNo ? ` 第 ${pageNo} 页` : ''}
            </Typography.Text>
          ) : null}
        </Space>
      }
      open={open}
      onClose={onClose}
      width={720}
      destroyOnClose
    >
      {!citation ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可溯源的引用" />
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
            这一条链的每一跳都标了数据来源；<b>任何一跳取不到都会写明"该环节未上报"</b>，
            不会留空，也不会用相邻信息凑一个看起来成立的结论。
          </Typography.Paragraph>
          <Steps direction="vertical" items={items} />
        </>
      )}

      {/* 该 run 的任务流（复用列表页同一个抽屉：打开即重取、1.5s 轮询、终态即停） */}
      <PipelineFlowDrawer
        open={flowOpen}
        runId={locatedRun?.runId ?? null}
        filenameHint={citation?.filename ?? null}
        onClose={() => setFlowOpen(false)}
      />
      <PipelineArtifactDrawer
        open={artifactOpen}
        artifacts={detail?.artifacts ?? []}
        runId={detail?.runId}
        onClose={() => setArtifactOpen(false)}
      />
      {preview.el}
    </Drawer>
  );
}
