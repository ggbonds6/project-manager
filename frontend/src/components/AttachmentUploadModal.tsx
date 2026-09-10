import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, List, Modal, Progress, Select, Space, Tag, Typography, Upload, message } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { attachmentApi } from '@/api/project';
import { useDict } from '@/hooks/useOptions';
import { fmtFileSize } from '@/utils/format';
import { UploadTaskItem } from '@/types';

interface Props {
  open: boolean;
  projectId: number | string;
  /** 默认归属（阶段/项目/付款记录） */
  bizType: string;
  bizId: number;
  scopeLabel?: string;
  /** 固定附件类别：设置后不再让用户选择文档类别（如付款凭证上传） */
  fixedAttachType?: string;
  onCancel: () => void;
  onDone: () => void;
}

const STATUS_META: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '排队中' },
  UPLOADING: { color: 'processing', text: '后台上传中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
};

/** 轮询上限：超过则认为异常，停止轮询（避免无限请求） */
const POLL_MAX_MS = 15 * 60 * 1000;
const POLL_INTERVAL_MS = 1000;

/**
 * 上传附件（后台上传）。
 *
 * 交互分两段，避免大文件让用户对着转圈干等：
 *  ① 传输：文件从浏览器传到服务端，显示真实传输百分比（axios onUploadProgress）；
 *  ② 后台处理：服务端受理后立即返回任务，写入对象存储由后台线程完成，
 *     这里轮询任务进度展示，用户可关闭弹窗——上传仍会在后台跑完。
 * 下方「上传记录」列出本项目的任务，失败可看到具体原因。
 */
export default function AttachmentUploadModal({
  open,
  projectId,
  bizType,
  bizId,
  scopeLabel,
  fixedAttachType,
  onCancel,
  onDone,
}: Props) {
  const { options: attachTypes } = useDict('ATTACH_TYPE');
  const [attachType, setAttachType] = useState<string | undefined>(fixedAttachType);

  const [transferPct, setTransferPct] = useState(0);
  const [task, setTask] = useState<UploadTaskItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [records, setRecords] = useState<UploadTaskItem[]>([]);

  const timerRef = useRef<number | null>(null);
  const pollStartRef = useRef(0);

  const stopPolling = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const loadRecords = useCallback(async () => {
    try {
      setRecords(await attachmentApi.listUploadTasks(projectId, 20));
    } catch {
      /* 记录加载失败不打断上传 */
    }
  }, [projectId]);

  useEffect(() => {
    if (open) {
      loadRecords();
    } else {
      stopPolling();
      setTask(null);
      setTransferPct(0);
      setBusy(false);
    }
  }, [open, loadRecords, stopPolling]);

  useEffect(() => stopPolling, [stopPolling]);

  const poll = useCallback(
    (id: number) => {
      stopPolling();
      pollStartRef.current = Date.now();
      timerRef.current = window.setInterval(async () => {
        if (Date.now() - pollStartRef.current > POLL_MAX_MS) {
          stopPolling();
          setBusy(false);
          message.warning('后台上传耗时过长，请稍后在「上传记录」中查看结果');
          loadRecords();
          return;
        }
        try {
          const t = await attachmentApi.getUploadTask(id);
          setTask(t);
          if (t.status === 'SUCCESS') {
            stopPolling();
            setBusy(false);
            message.success(`上传成功：${t.fileName}`);
            loadRecords();
            onDone();
          } else if (t.status === 'FAILED') {
            stopPolling();
            setBusy(false);
            message.error(`上传失败：${t.fileName}（${t.errorMsg || '未知原因'}）`);
            loadRecords();
          }
        } catch {
          stopPolling();
          setBusy(false);
        }
      }, POLL_INTERVAL_MS);
    },
    [loadRecords, onDone, stopPolling],
  );

  const doUpload = async (file: File) => {
    setBusy(true);
    setTransferPct(0);
    setTask(null);
    try {
      const t = await attachmentApi.upload(
        { projectId, bizType, bizId, attachType: fixedAttachType ?? attachType, file },
        (p) => setTransferPct(p),
      );
      setTask(t);
      setTransferPct(100);
      loadRecords();
      poll(t.id);
    } catch {
      /* 拦截器已提示 */
      setBusy(false);
      loadRecords();
    }
  };

  const beforeUpload = (file: File) => {
    if (busy) {
      message.warning('已有文件正在上传，请等待完成');
      return false;
    }
    doUpload(file);
    return false;
  };

  const handleClose = () => {
    if (busy) {
      message.info('上传已在后台继续，关闭窗口不会中断，可稍后打开查看上传记录');
    }
    stopPolling();
    onCancel();
  };

  const statusText = (t: UploadTaskItem) => {
    if (t.status === 'UPLOADING' || t.status === 'PENDING') {
      return `${STATUS_META[t.status]?.text ?? t.status} ${t.progress ?? 0}%`;
    }
    if (t.status === 'FAILED') {
      return STATUS_META.FAILED.text;
    }
    return STATUS_META.SUCCESS.text;
  };

  return (
    <Modal
      title={`上传附件${scopeLabel ? `（${scopeLabel}）` : ''}`}
      open={open}
      onCancel={handleClose}
      footer={null}
      destroyOnClose
      width={560}
    >
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        {!fixedAttachType && (
          <div>
            <Typography.Text type="secondary">文档类别：</Typography.Text>
            <Select
              style={{ width: 260 }}
              allowClear
              placeholder="选择文档类别"
              value={attachType}
              onChange={setAttachType}
              options={attachTypes.map((d) => ({ value: d.code, label: d.name }))}
            />
          </div>
        )}
        <Upload
          accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.png,.jpg,.jpeg,.gif,.webp,.zip,.rar,.7z"
          beforeUpload={beforeUpload}
          showUploadList={false}
          disabled={busy}
        >
          <Button type="primary" icon={<UploadOutlined />} loading={busy} disabled={busy}>
            选择文件并上传
          </Button>
        </Upload>
        {fixedAttachType && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            将按“{attachTypes.find((d) => d.code === fixedAttachType)?.name || fixedAttachType}”类别上传
          </Typography.Text>
        )}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          支持 pdf/word/excel/图片/压缩包，单文件 ≤ 100MB。
          <br />
          大文件上传分两步：先传到服务器（显示传输进度），再由服务器后台上传到存储——
          此阶段可关闭窗口，不会中断。
        </Typography.Text>

        {/* 当前上传进度：传输阶段与后台阶段分开显示，避免"卡住"的错觉 */}
        {busy || task ? (
          <div style={{ background: '#fafafa', borderRadius: 6, padding: '10px 12px' }}>
            {transferPct < 100 ? (
              <>
                <Typography.Text style={{ fontSize: 12 }}>正在传输到服务器…</Typography.Text>
                <Progress percent={transferPct} size="small" status="active" />
              </>
            ) : (
              <>
                <Typography.Text style={{ fontSize: 12 }}>
                  服务器正在后台上传…
                  {task?.fileSize ? `（${fmtFileSize(task.fileSize)}）` : ''}
                </Typography.Text>
                <Progress
                  percent={task?.progress ?? 0}
                  size="small"
                  status={task?.status === 'FAILED' ? 'exception' : 'active'}
                />
              </>
            )}
          </div>
        ) : null}

        {/* 上传记录 */}
        {records.length > 0 && (
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              上传记录（最近 {records.length} 条）
            </Typography.Text>
            <List
              size="small"
              dataSource={records}
              style={{ maxHeight: 220, overflow: 'auto', marginTop: 4 }}
              renderItem={(r) => {
                const meta = STATUS_META[r.status] ?? { color: 'default', text: r.status };
                return (
                  <List.Item
                    style={{ padding: '6px 0' }}
                    actions={[
                      <Tag key="s" color={meta.color} style={{ marginInlineEnd: 0 }}>
                        {statusText(r)}
                      </Tag>,
                    ]}
                  >
                    <Typography.Text
                      ellipsis={{ tooltip: r.errorMsg || r.fileName }}
                      style={{ fontSize: 12, maxWidth: 320 }}
                    >
                      {r.fileName}
                      <Typography.Text type="secondary" style={{ fontSize: 12, marginInlineStart: 6 }}>
                        {r.fileSize ? fmtFileSize(r.fileSize) : ''}
                      </Typography.Text>
                      {r.status === 'FAILED' && r.errorMsg ? (
                        <Typography.Text type="danger" style={{ fontSize: 12, marginInlineStart: 6 }}>
                          {r.errorMsg}
                        </Typography.Text>
                      ) : null}
                    </Typography.Text>
                  </List.Item>
                );
              }}
            />
          </div>
        )}
      </Space>
    </Modal>
  );
}
