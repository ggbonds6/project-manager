/**
 * 附件「AI 索引状态」标签（附件中心 / 上传记录复用）
 * ----------------------------------------------------------------
 * 对应方案 §3.3 需求②：附件列表要能看到"是否已可检索"。
 * 四种状态：未解析 / 解析中（带进度）/ 已可检索 / 解析失败（原因 + 重新解析）。
 *
 * 设计取舍：
 *  - 状态**不来自本地乐观更新**，一切以 `GET /api/ai/attachments/status` 的返回为准，
 *    解析触发后重新拉取（见 `hooks/useAiAttachmentStatus.ts`）——界面不撒谎；
 *  - 解析失败必须能看到原因（Tooltip/行内文案），否则用户只知道"坏了"却无从下手；
 *  - "重新解析"只在有权限（ADMIN/MANAGER）时出现，且对正在解析中的附件禁用，
 *    避免重复提交把队列打满。
 */
import { Button, Progress, Space, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { AiAttachmentStatus, aiIndexStatusMeta } from '@/types/ai';

interface Props {
  /** 批量查询得到的实时状态；为空时退回附件记录里带的 aiIndexStatus */
  status?: AiAttachmentStatus | null;
  /** 附件记录自带的索引状态（后端在附件列表里回填时用） */
  fallbackIndexStatus?: string | null;
  fallbackError?: string | null;
  /** 是否可触发解析 */
  canManage?: boolean;
  /** 正在触发解析（按钮 loading） */
  parsing?: boolean;
  onReparse?: () => void;
  /** 紧凑模式：只显示标签，失败原因收进 Tooltip（附件中心一页可能几十个附件） */
  compact?: boolean;
}

export default function AttachmentAiStatusTag({
  status,
  fallbackIndexStatus,
  fallbackError,
  canManage,
  parsing,
  onReparse,
  compact,
}: Props) {
  // 解析中：显示进度百分比，让用户知道不是"卡住了"
  const indexStatus = status?.indexStatus ?? fallbackIndexStatus ?? null;
  const error = status?.error ?? fallbackError ?? null;
  const meta = aiIndexStatusMeta(indexStatus);
  const isParsing = indexStatus === 'PARSING';
  const isFailed = indexStatus === 'FAILED';
  const isReady = indexStatus === 'READY';
  const progress = status?.progress ?? null;

  const tag = (
    <Tag color={meta.color} style={{ marginInlineEnd: 0 }}>
      {meta.text}
      {isParsing && typeof progress === 'number' ? ` ${progress}%` : ''}
    </Tag>
  );

  return (
    <Space size={4} wrap={false}>
      {isFailed && error ? (
        <Tooltip title={error}>
          <span>{tag}</span>
        </Tooltip>
      ) : (
        tag
      )}

      {/* 解析中：细进度条（比只显示百分比更直观，也避免用户以为界面死了） */}
      {isParsing && typeof progress === 'number' ? (
        <Progress percent={progress} size="small" style={{ width: 80, marginBottom: 0 }} showInfo={false} />
      ) : null}

      {/* 失败原因：非紧凑模式下直接显示一行，省得用户去悬浮 */}
      {isFailed && error && !compact ? (
        <Typography.Text type="danger" style={{ fontSize: 12 }} ellipsis={{ tooltip: error }}>
          {error}
        </Typography.Text>
      ) : null}

      {/* 未解析/失败可手动触发；已可检索不提供"重新解析"以外的入口（重新解析由后端决定是否重建） */}
      {canManage && onReparse && !isParsing ? (
        <Tooltip title={isReady ? '重新解析（用于文档被替换或解析结果不可用）' : '触发解析入库'}>
          <Button
            size="small"
            type="link"
            icon={<ReloadOutlined />}
            loading={parsing}
            onClick={(e) => {
              e.stopPropagation();
              onReparse();
            }}
          >
            {isReady ? '重新解析' : isFailed ? '重新解析' : '解析'}
          </Button>
        </Tooltip>
      ) : null}
    </Space>
  );
}
