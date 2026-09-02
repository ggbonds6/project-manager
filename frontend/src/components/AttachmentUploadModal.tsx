import { useState } from 'react';
import { Button, Modal, Select, Space, Typography, Upload, message } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { attachmentApi } from '@/api/project';
import { useDict } from '@/hooks/useOptions';

interface Props {
  open: boolean;
  projectId: number | string;
  /** 默认归属（阶段/项目/付款记录） */
  bizType: string;
  bizId: number;
  scopeLabel?: string;
  onCancel: () => void;
  onDone: () => void;
}

export default function AttachmentUploadModal({
  open,
  projectId,
  bizType,
  bizId,
  scopeLabel,
  onCancel,
  onDone,
}: Props) {
  const { options: attachTypes } = useDict('ATTACH_TYPE');
  const [attachType, setAttachType] = useState<string | undefined>();
  const [uploading, setUploading] = useState(false);

  const doUpload = async (file: File) => {
    setUploading(true);
    try {
      const res = await attachmentApi.upload({ projectId, bizType, bizId, attachType, file });
      message.success(`上传成功：${res.fileName}`);
      onDone();
      onCancel();
    } catch {
      /* 拦截器已提示 */
    } finally {
      setUploading(false);
    }
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
        <Upload accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.png,.jpg,.jpeg,.gif,.webp,.zip,.rar,.7z" beforeUpload={beforeUpload} showUploadList={false}>
          <Button type="primary" icon={<UploadOutlined />} loading={uploading}>
            选择文件并上传
          </Button>
        </Upload>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          支持 pdf/word/excel/图片/压缩包，单文件 ≤ 100MB
        </Typography.Text>
      </Space>
    </Modal>
  );
}
