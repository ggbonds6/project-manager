import { useState } from 'react';
import { Badge, Button, Empty, List, Modal, Progress, Space, Tag, Tooltip, Typography } from 'antd';
import { CloudUploadOutlined, ReloadOutlined } from '@ant-design/icons';
import { useUploadTasks } from '@/store/uploadTask';
import { useDict } from '@/hooks/useOptions';
import { fmtDateTime, fmtFileSize } from '@/utils/format';
import { fileExtTag } from '@/config/tagDict';

/** 归属类型展示名（PROJECT_PHASE 用阶段名，其余用固定文案） */
const BIZ_LABEL: Record<string, string> = {
  PROJECT: '项目级',
  PAYMENT: '付款凭证',
};

const STATUS_META: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '排队中' },
  UPLOADING: { color: 'processing', text: '上传中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
};

interface Props {
  /** 与工具栏按钮风格保持一致（用于附件中心并排放置） */
  block?: boolean;
}

/**
 * 上传中心：一个统一入口（按钮 + 弹窗），随时查看上传进度与历史记录。
 *
 * 与上传动作解耦——上传由 {@link useUploadTasks} 在后台维护，
 * 用户不必保持任何弹窗打开；有进行中任务时按钮显示角标。
 */
export default function UploadTaskCenter({ block }: Props) {
  const { tasks, transfer, activeCount, refresh } = useUploadTasks();
  const [open, setOpen] = useState(false);
  const { options: attachTypes } = useDict('ATTACH_TYPE');

  const attachTypeName = (code?: string | null) =>
    code ? attachTypes.find((d) => d.code === code)?.name || code : null;

  const openPanel = () => {
    setOpen(true);
    void refresh();
  };

  const badgeCount = activeCount + (transfer ? 1 : 0);

  return (
    <>
      <Badge count={badgeCount} size="small" offset={[-2, 2]}>
        <Button
          icon={<CloudUploadOutlined />}
          block={block}
          onClick={openPanel}
          title="查看上传进度与历史记录"
        >
          上传记录{badgeCount > 0 ? `（${badgeCount}）` : ''}
        </Button>
      </Badge>

      <Modal
        title={
          <Space size={8}>
            <span>上传记录</span>
            <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
              上传在后台进行，可关闭本窗口
            </Typography.Text>
          </Space>
        }
        open={open}
        onCancel={() => setOpen(false)}
        footer={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>
              刷新
            </Button>
            <Button type="primary" onClick={() => setOpen(false)}>
              关闭
            </Button>
          </Space>
        }
        width={680}
      >
        {/* 正在传输到服务器的文件：此时还没有任务 id，单独提示 */}
        {transfer ? (
          <div style={{ background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6, padding: '8px 12px', marginBottom: 10 }}>
            <Typography.Text style={{ fontSize: 12 }}>
              正在传输到服务器：<b>{transfer.name}</b>
            </Typography.Text>
            <Progress percent={transfer.pct} size="small" status="active" />
          </div>
        ) : null}

        {tasks.length === 0 ? (
          <Empty description="暂无上传记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <List
            size="small"
            dataSource={tasks}
            style={{ maxHeight: 460, overflow: 'auto' }}
            renderItem={(t) => {
              const meta = STATUS_META[t.status] ?? { color: 'default', text: t.status };
              const extTag = fileExtTag(t.fileExt);
              const bizText = t.phaseName || BIZ_LABEL[t.bizType] || t.bizType;
              const atName = attachTypeName(t.attachType);
              const running = t.status === 'PENDING' || t.status === 'UPLOADING';
              return (
                <List.Item style={{ padding: '8px 0', alignItems: 'flex-start' }}>
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <Space size={6} wrap>
                      <Tag color={extTag.color} style={{ marginInlineEnd: 0 }}>
                        {extTag.text}
                      </Tag>
                      <Typography.Text strong style={{ fontSize: 13 }}>
                        {t.fileName}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {fmtFileSize(t.fileSize)}
                      </Typography.Text>
                    </Space>

                    <Space size={6} wrap>
                      <Tooltip title="所属阶段/归属">
                        <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                          {bizText}
                        </Tag>
                      </Tooltip>
                      {atName ? (
                        <Tag color="geekblue" style={{ marginInlineEnd: 0 }}>
                          {atName}
                        </Tag>
                      ) : null}
                      <Tag color={meta.color} style={{ marginInlineEnd: 0 }}>
                        {meta.text}
                        {running ? ` ${t.progress ?? 0}%` : ''}
                      </Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {fmtDateTime(t.createTime)}
                      </Typography.Text>
                    </Space>

                    {running ? <Progress percent={t.progress ?? 0} size="small" status="active" /> : null}

                    {t.status === 'FAILED' && t.errorMsg ? (
                      <Typography.Text type="danger" style={{ fontSize: 12 }}>
                        {t.errorMsg}
                      </Typography.Text>
                    ) : null}
                  </Space>
                </List.Item>
              );
            }}
          />
        )}
      </Modal>
    </>
  );
}
