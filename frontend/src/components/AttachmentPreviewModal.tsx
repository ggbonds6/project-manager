import { useCallback, useEffect, useState } from 'react';
import { Button, Modal, Space, Spin, Typography, message } from 'antd';
import { DownloadOutlined, FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons';
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
 * 附件在线预览：图片 / pdf 内嵌、文本抓取展示、Office 提示下载；
 * 支持全屏（Esc 或“退出全屏”按钮关闭）。
 */
export default function AttachmentPreviewModal({ item, onClose }: Props) {
  const [text, setText] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [full, setFull] = useState(false);

  const ext = (item?.fileExt || '').toLowerCase();

  // 切换附件时复位状态
  useEffect(() => {
    setFull(false);
    setText(null);
    if (!item) return;
    if (TEXT_EXTS.has(ext)) {
      setLoading(true);
      const token = localStorage.getItem('pm_token') || '';
      fetch(attachmentUrl(item.id, 'inline'), { headers: { Authorization: `Bearer ${token}` } })
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

  // 全屏时 Esc 退出
  useEffect(() => {
    if (!full) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setFull(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [full]);

  const isImage = IMG_EXTS.has(ext);
  const isPdf = ext === 'pdf';
  const isOffice = OFFICE_EXTS.has(ext);

  const renderViewer = useCallback(
    (large: boolean) => {
      if (!item) return null;
      if (isImage) {
        return (
          <div style={{ textAlign: 'center' }}>
            <img
              src={attachmentUrl(item.id, 'inline')}
              alt={item.fileName}
              style={{ maxWidth: '100%', maxHeight: large ? 'calc(100vh - 140px)' : '70vh', objectFit: 'contain' }}
            />
          </div>
        );
      }
      if (isPdf) {
        return (
          <iframe
            src={attachmentUrl(item.id, 'inline')}
            title={item.fileName}
            style={{ width: '100%', height: large ? 'calc(100vh - 140px)' : '65vh', border: 'none' }}
          />
        );
      }
      if (TEXT_EXTS.has(ext)) {
        return loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Spin size="large" />
          </div>
        ) : (
          <pre
            style={{
              textAlign: 'left',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              background: '#fafafa',
              padding: 12,
              borderRadius: 6,
              maxHeight: large ? 'calc(100vh - 140px)' : '65vh',
              overflow: 'auto',
              margin: 0,
            }}
          >
            {text}
          </pre>
        );
      }
      return (
        <div style={{ textAlign: 'center', padding: 48 }}>
          <Typography.Paragraph type="secondary">
            {isOffice
              ? 'Office 文档暂不支持浏览器在线预览，请下载后用本机办公软件打开。'
              : '该文件类型暂不支持在线预览，请下载查看。'}
          </Typography.Paragraph>
          <Button type="primary" icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
            下载文件
          </Button>
        </div>
      );
    },
    [item, isImage, isPdf, ext, isOffice, loading, text],
  );

  if (!item) return null;

  // ==================== 全屏模式 ====================
  if (full) {
    return (
      <div
        style={{
          position: 'fixed',
          inset: 0,
          zIndex: 3000,
          background: '#fafafa',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <div
          style={{
            height: 52,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 16px',
            background: '#fff',
            borderBottom: '1px solid #f0f0f0',
            gap: 12,
            flexShrink: 0,
          }}
        >
          <Space size={10}>
            <b style={{ fontSize: 15 }}>{item.fileName}</b>
            <Typography.Text type="secondary">{fmtFileSize(item.fileSize)}</Typography.Text>
          </Space>
          <Space>
            <Button type="primary" icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
              下载
            </Button>
            <Button icon={<FullscreenExitOutlined />} onClick={() => setFull(false)}>
              退出全屏 (Esc)
            </Button>
          </Space>
        </div>
        <div style={{ flex: 1, overflow: 'auto', padding: 12 }}>{renderViewer(true)}</div>
      </div>
    );
  }

  // ==================== 弹窗模式 ====================
  return (
    <Modal
      title={
        <Space size={8}>
          <span>{item.fileName}</span>
          <Typography.Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>
            {fmtFileSize(item.fileSize)}
          </Typography.Text>
        </Space>
      }
      open
      onCancel={onClose}
      width={880}
      footer={
        <Space>
          <Button type="primary" icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
            下载
          </Button>
          <Button icon={<FullscreenOutlined />} onClick={() => setFull(true)}>
            全屏查看
          </Button>
        </Space>
      }
    >
      <div style={{ maxHeight: '70vh', overflow: 'auto' }}>{renderViewer(false)}</div>
    </Modal>
  );
}
