import { useState } from 'react';
import { Button, Modal, Select, Space, Typography, Upload, message } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { useUploadTasks } from '@/store/uploadTask';
import { useDict } from '@/hooks/useOptions';
import { fmtFileSize } from '@/utils/format';

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

/** 单文件上限（与后端 spring.servlet.multipart.max-file-size 保持一致） */
const MAX_UPLOAD_BYTES = 500 * 1024 * 1024;
const MAX_UPLOAD_TEXT = '500MB';

/**
 * 上传附件：**只负责选文件与文档类别**。
 *
 * 选定后立即交给后台（{@link useUploadTasks}），本窗口随即关闭——
 * 用户不需要一直开着窗口等上传完成；进度与历史记录统一在「上传记录」入口查看。
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
  const { startUpload } = useUploadTasks();

  const doUpload = (file: File) => {
    // 前端先拦一道，避免大文件白传一趟才被后端拒绝
    if (file.size > MAX_UPLOAD_BYTES) {
      message.error(`文件过大（${fmtFileSize(file.size)}），单文件上限 ${MAX_UPLOAD_TEXT}，请压缩或拆分后重试`);
      return;
    }
    // 不 await：交给后台跑，弹窗立刻关闭；失败由请求层统一提示
    void startUpload({
      projectId,
      bizType,
      bizId,
      attachType: fixedAttachType ?? attachType,
      file,
    }).catch(() => {
      /* 拦截器已提示 */
    });
    message.success('已开始后台上传，可在「上传记录」中查看进度');
    onDone();
    onCancel();
  };

  const beforeUpload = (file: File) => {
    doUpload(file);
    return false;
  };

  return (
    <Modal
      title={`上传附件${scopeLabel ? `（${scopeLabel}）` : ''}`}
      open={open}
      onCancel={onCancel}
      footer={null}
      destroyOnClose
      width={480}
    >
      <Space direction="vertical" style={{ width: '100%' }}>
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
        >
          <Button type="primary" icon={<UploadOutlined />}>
            选择文件并上传
          </Button>
        </Upload>
        {fixedAttachType && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            将按“{attachTypes.find((d) => d.code === fixedAttachType)?.name || fixedAttachType}”类别上传
          </Typography.Text>
        )}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          支持 pdf/word/excel/图片/压缩包，单文件 ≤ {MAX_UPLOAD_TEXT}。
          <br />
          选择后立即开始上传，<b>本窗口可直接关闭</b>——进度请到「上传记录」查看。
        </Typography.Text>
      </Space>
    </Modal>
  );
}
