import { useEffect, useState } from 'react';
import { Button, Modal, Space, Spin, Typography, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { attachmentUrl } from '@/api/project';
import { fmtFileSize } from '@/utils/format';
import { AttachmentItem } from '@/types';

const IMG_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);
const TEXT_EXTS = new Set(['txt', 'csv', 'md', 'log', 'json', 'xml', 'html']);
const OFFICE_EXTS = new Set(['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx']);

interface Props {
  item: AttachmentItem | null;
  onClose: () => void;
}

/**
 * 附件在线预览：图片 / pdf 直接内嵌，文本类抓取展示，Office/压缩包提示下载。
 */
export default function AttachmentPreviewModal({ item, onClose }: Props) {
  const [text, setText] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const ext = (item?.fileExt || '').toLowerCase();

  useEffect(() => {
    setText(null);
    if (!item) return;
    if (TEXT_EXTS.has(ext)) {
      setLoading(true);
      const token = localStorage.getItem('pm_token') || '';
      fetch(attachmentUrl(item.id, 'inline'), {
        headers: { Authorization: `Bearer ${token}` },
      })
        .then((r) => {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.text();
        })
        .then(setText)
        .catch((e) => {
          message.error('预览读取失败：' + e.message);
          setText('（读取失败，请下载查看）');
        })
        .finally(() => setLoading(false));
    }
  }, [item, ext]);

  const isImage = IMG_EXTS.has(ext);
  const isPdf = ext === 'pdf';
  const isOffice = OFFICE_EXTS.has(ext);
  const unsupported = !isImage && !isPdf && !TEXT_EXTS.has(ext);

  return (
    <Modal
      title={item ? item.fileName : '预览'}
      open={!!item}
      onCancel={onClose}
      width={840}
      footer={
        item ? (
          <Space>
            <Typography.Text type="secondary">{fmtFileSize(item.fileSize)}</Typography.Text>
            <Button type="primary" icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
              下载
            </Button>
          </Space>
        ) : null
      }
    >
      {!item ? null : (
        <div style={{ maxHeight: '70vh', overflow: 'auto', textAlign: 'center' }}>
          {isImage ? (
            <img src={attachmentUrl(item.id, 'inline')} alt={item.fileName} style={{ maxWidth: '100%' }} />
          ) : isPdf ? (
            <iframe
              src={attachmentUrl(item.id, 'inline')}
              title={item.fileName}
              style={{ width: '100%', height: '65vh', border: 'none' }}
            />
          ) : TEXT_EXTS.has(ext) ? (
            loading ? (
              <Spin style={{ margin: '60px auto' }} />
            ) : (
              <pre
                style={{
                  textAlign: 'left',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  background: '#fafafa',
                  padding: 12,
                  borderRadius: 6,
                  maxHeight: '65vh',
                  overflow: 'auto',
                }}
              >
                {text}
              </pre>
            )
          ) : (
            <div style={{ padding: 40 }}>
              <Typography.Paragraph type="secondary">
                {isOffice
                  ? 'Office 文档暂不支持浏览器在线预览，请下载后用本机办公软件打开。'
                  : '该文件类型暂不支持在线预览，请下载查看。'}
              </Typography.Paragraph>
              <Button icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
                下载文件
              </Button>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
