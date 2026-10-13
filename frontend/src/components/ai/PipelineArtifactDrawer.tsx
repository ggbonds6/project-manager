/**
 * 产物抽屉（任务流视图的第 ③ 层）
 * ----------------------------------------------------------------
 * 数据来源：**详情接口的 `artifacts[]`**（`GET /api/ai/runs/{run_id}/pipeline`，用户 JWT）。
 * 这里**不自己打接口**——产物摘要是详情的一部分，抽屉打开时详情已经取过全量；
 * 也让"打开抽屉即重取全量"这条纪律只有一个落点（PipelineFlowDrawer）。
 *
 * 与 §4.7 的边界一致：
 *  - DB **只存摘要**（`content` ≤ 4000 字符、**已解析成 JSON 给前端直接渲染**，超长截断），
 *    而 `char_count` 是**产物全文的真实字符数**（不是摘要长度）——所以卡片上按"全文字符数"标注；
 *  - 全文留在 AI 服务 `work/`，由 `object_key` 指路 ⇒ 这里把 `object_key` 做成**可复制文本**，
 *    并明说"主系统不提供该路径的下载接口"——不做点不动的"下载"按钮；
 *  - 产物行**不可变**（只 INSERT、`artifact_rev` 递增）⇒ 同一类型可能有多行，按 rev 展示，供"两次解析对比"。
 */
import { Alert, Card, Descriptions, Divider, Drawer, Empty, Space, Tag, Tooltip, Typography } from 'antd';
import type { AiPipelineArtifact } from '@/types/ai';
import { AI_PIPELINE_ARTIFACT_META, aiPipelineArtifactHint, aiPipelineArtifactText } from '@/types/ai';
import { arrayOf, hasValue, kvEntries } from '@/components/ai/pipelineFormat';
import { fmtDateTime } from '@/utils/format';

interface Props {
  open: boolean;
  /** 详情里的产物列表（可能为空：该 run 没上报产物） */
  artifacts: AiPipelineArtifact[];
  /** 产物所属 run（标题里显示，方便与任务流对齐） */
  runId?: string | null;
  onClose: () => void;
}

/** 列表里最多逐条展示的数组元素数（摘要里塞几百条切片时，避免一次渲染上千个 DOM 节点） */
const MAX_ARRAY_ROWS = 20;

export default function PipelineArtifactDrawer({ open, artifacts, runId, onClose }: Props) {
  // 固定五类的顺序（与 §4.7 的类型清单一致），后端新增的未知类型追加在末尾
  const knownOrder = Object.keys(AI_PIPELINE_ARTIFACT_META);
  const unknownTypes = Array.from(new Set(artifacts.map((a) => a.artifactType))).filter(
    (t) => !knownOrder.includes(t),
  );
  const orderedTypes = [...knownOrder, ...unknownTypes];

  return (
    <Drawer
      title={
        <Space size={8} wrap>
          <span>解析产物（摘要）</span>
          {runId ? (
            <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
              run {runId}
            </Typography.Text>
          ) : null}
        </Space>
      }
      open={open}
      onClose={onClose}
      width={720}
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="这里只有摘要"
        description={
          <Typography.Text style={{ fontSize: 12 }}>
            主系统按 §4.7 只存摘要（content ≤ 4000 字符、超长截断；卡片上的 char_count 是<b>产物全文的真实字符数</b>）；
            清洗前后全文 / 切片全文留在 AI 服务 work/，由 object_key 指路。
            本系统<b>不提供该路径的下载接口</b>，所以 object_key 只做可复制展示（不做点不动的下载按钮）。
          </Typography.Text>
        }
      />

      {artifacts.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="该流水线未上报任何产物（后端未提供时，这里不会用 0 或空表冒充有数据）"
        />
      ) : (
        orderedTypes.map((type) => {
          const rows = artifacts
            .filter((a) => a.artifactType === type)
            // 同一类型多个版本：rev 升序，便于"上一次 vs 这一次"对照
            .sort((a, b) => (a.artifactRev ?? 0) - (b.artifactRev ?? 0));
          if (rows.length === 0) return null;
          return (
            <div key={type} style={{ marginBottom: 16 }}>
              <Space size={6} wrap style={{ marginBottom: 6 }}>
                <Typography.Text strong>{aiPipelineArtifactText(type)}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {aiPipelineArtifactHint(type)}
                </Typography.Text>
                <Tag>{type}</Tag>
                <Tag color={rows.length > 1 ? 'blue' : 'default'}>{rows.length} 个版本</Tag>
              </Space>

              {rows.map((a, idx) => (
                <Card
                  key={`${type}-${a.artifactRev ?? idx}`}
                  size="small"
                  style={{ marginBottom: 8 }}
                  title={
                    <Space size={6} wrap>
                      <span>rev {a.artifactRev ?? '-'}</span>
                      {hasValue(a.itemCount) ? <Tag color="geekblue">{a.itemCount} 项</Tag> : null}
                      {hasValue(a.charCount) ? <Tag>{a.charCount} 字符</Tag> : null}
                      {a.sha1 ? (
                        <Tooltip title={`完整 sha1：${a.sha1}`}>
                          <Tag>sha1 {a.sha1.slice(0, 8)}</Tag>
                        </Tooltip>
                      ) : null}
                    </Space>
                  }
                >
                  <Descriptions size="small" column={1} bordered>
                    <Descriptions.Item label="产物键">
                      <Typography.Text code>{a.artifactType}</Typography.Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="版本 / 条数">
                      {(a.artifactRev ?? '暂无数据') + ' / ' + (hasValue(a.itemCount) ? a.itemCount : '暂无数据')}
                    </Descriptions.Item>
                    <Descriptions.Item label="全文字符数 char_count">
                      {hasValue(a.charCount) ? a.charCount : '暂无数据'}
                    </Descriptions.Item>
                    <Descriptions.Item label="sha1">
                      {a.sha1 ? <Typography.Text copyable>{a.sha1}</Typography.Text> : '暂无数据'}
                    </Descriptions.Item>
                    <Descriptions.Item label="全文位置 object_key">
                      {a.objectKey ? (
                        <Space direction="vertical" size={0}>
                          <Typography.Text copyable code style={{ fontSize: 12 }}>
                            {a.objectKey}
                          </Typography.Text>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            AI 服务 work/ 内路径，仅内网可达（本系统不代理下载）
                          </Typography.Text>
                        </Space>
                      ) : (
                        '暂无数据'
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="生成时间">{fmtDateTime(a.createdAt)}</Descriptions.Item>
                  </Descriptions>

                  <Divider style={{ margin: '10px 0 8px' }} orientation="left" plain>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      摘要内容 content
                    </Typography.Text>
                  </Divider>
                  {hasValue(a.content) ? (
                    <ContentKV value={a.content} />
                  ) : (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      该产物只有元数据、未上报摘要内容
                    </Typography.Text>
                  )}
                </Card>
              ))}
            </div>
          );
        })
      )}
    </Drawer>
  );
}

/**
 * 通用"摘要内容"渲染：形状由后端定（对象/数组/标量都可能），因此**按结构递归展示**，
 * 不假设任何具体字段名——后端加字段不需要改前端。
 *
 * 深度到 2 层后回落到 `JSON.stringify`：再深的嵌套（如 counters 里塞了对象数组）
 * 用键值行展示反而更难读，直接给出可复制的原文更诚实。
 */
export function ContentKV({ value, depth = 0 }: { value: unknown; depth?: number }) {
  if (!hasValue(value)) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        暂无数据
      </Typography.Text>
    );
  }
  if (Array.isArray(value)) {
    const scalars = value.filter((v) => !v || typeof v !== 'object');
    if (scalars.length === value.length) {
      // 纯标量数组：一行展示（如 rules_hit: ["C1","C4"]）
      return <Typography.Text style={{ fontSize: 12 }}>{scalars.map((v) => String(v)).join('、')}</Typography.Text>;
    }
    const objs = arrayOf(value);
    return (
      <div>
        {objs.slice(0, MAX_ARRAY_ROWS).map((item, i) => (
          <div
            key={i}
            style={{ borderLeft: '2px solid #f0f0f0', paddingLeft: 8, marginBottom: 6 }}
          >
            <ContentKV value={item} depth={depth + 1} />
          </div>
        ))}
        {value.length > MAX_ARRAY_ROWS ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            共 {value.length} 条，此处只显示前 {MAX_ARRAY_ROWS} 条（完整清单在 AI 服务 work/）
          </Typography.Text>
        ) : null}
      </div>
    );
  }
  if (typeof value === 'object' && value !== null) {
    if (depth >= 2) {
      return (
        <Typography.Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>
          {JSON.stringify(value)}
        </Typography.Text>
      );
    }
    return (
      <div>
        {kvEntries(value as Record<string, unknown>).map(({ key, value: v }) => (
          <div key={key} style={{ display: 'flex', gap: 8, padding: '2px 0' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12, minWidth: 140, flexShrink: 0 }}>
              {key}
            </Typography.Text>
            <div style={{ fontSize: 12, minWidth: 0, flex: 1 }}>
              <ContentKV value={v} depth={depth + 1} />
            </div>
          </div>
        ))}
      </div>
    );
  }
  return (
    <Typography.Text style={{ fontSize: 12, wordBreak: 'break-all' }}>{String(value)}</Typography.Text>
  );
}
