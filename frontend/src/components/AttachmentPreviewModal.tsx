import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { Button, Modal, Space, Spin, Tag, Typography, message } from 'antd';
import { DownloadOutlined, FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { attachmentUrl } from '@/api/project';
import { fmtFileSize } from '@/utils/format';
import { fileExtTag } from '@/config/tagDict';
import { AttachmentItem } from '@/types';

const IMG_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp']);
const PDF_EXTS = new Set(['pdf']);
const TEXT_EXTS = new Set(['txt', 'log', 'json', 'xml', 'html']);
const MD_EXTS = new Set(['md']);
const DOCX_EXTS = new Set(['docx']);
const XLSX_EXTS = new Set(['xls', 'xlsx']);
const DOC_EXTS = new Set(['doc']); // 旧版 Word：内容嗅探（实为 docx 才渲染，真·doc 提示下载）
const OFD_EXTS = new Set(['ofd']); // OFD 版式：@sharp9/ofdjs 首页 Canvas 试渲染

const DOCX_BASE_CSS = `.pm-office-body{font-family:'Microsoft YaHei','PingFang SC',sans-serif;color:#1f2329;}
.pm-office-body table{border-collapse:collapse;margin:10px 0;width:100%;}
.pm-office-body td,.pm-office-body th{border:1px solid #d0d4da;padding:4px 8px;font-size:13px;}
.pm-office-body p{margin:6px 0;line-height:1.7;}
.pm-office-body img{max-width:100%;}`;

interface Props {
  item: AttachmentItem | null;
  onClose: () => void;
}

/**
 * 附件在线预览：
 *  - 图片/pdf：内嵌（加载中显示等待态）
 *  - 文本类：txt/log/json/xml/html 原文；md 用 react-markdown 渲染
 *  - docx：docx-preview 解析渲染（懒加载）
 *  - xls/xlsx：SheetJS(xlsx) 解析为表格 HTML（懒加载）
 *  - doc/ppt/pptx/ofd/压缩包等：当前无稳定纯前端方案 → 提示"暂不支持在线预览，请下载后查看"
 * 支持全屏（Esc / 按钮退出）。
 */
export default function AttachmentPreviewModal({ item, onClose }: Props) {
  const [text, setText] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pdfLoaded, setPdfLoaded] = useState(false);
  const [full, setFull] = useState(false);
  const officeRef = useRef<HTMLDivElement | null>(null);

  const ext = (item?.fileExt || '').toLowerCase();
  const extTag = fileExtTag(ext);

  const authToken = () => localStorage.getItem('pm_token') || '';

  const renderFallback = (msg: string) => {
    if (officeRef.current) {
      officeRef.current.innerHTML =
        `<div style="padding:24px;text-align:center;color:#999">${msg}</div>`;
    }
  };

  // OFD：@sharp9/ofdjs → 渲染首页到 Canvas（多页暂取第 1 页，失败回落下载提示）
  const doOfd = async () => {
    setBusy(true);
    try {
      const resp = await fetch(attachmentUrl(item!.id), {
        headers: { Authorization: `Bearer ${authToken()}` },
      });
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      const buf = await resp.arrayBuffer();
      const jszipMod: any = await import('jszip');
      (window as any).JSZip = jszipMod.default;
      const ofdMod: any = await import('@sharp9/ofdjs');
      const ofd = await ofdMod.readOfd(new Uint8Array(buf));
      const pageCount = typeof ofdMod.getPageCount === 'function' ? ofdMod.getPageCount(ofd) : 1;
      const dims = await ofdMod.getPageDimensions(ofd, 0);
      const dpi = 96;
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round((dims.width * dpi) / 25.4));
      canvas.height = Math.max(1, Math.round((dims.height * dpi) / 25.4));
      canvas.style.maxWidth = '100%';
      canvas.style.height = 'auto';
      await ofdMod.renderPageToCanvas(ofd, 0, canvas, { dpi });
      const host = officeRef.current;
      if (host) {
        host.innerHTML = '';
        if (pageCount > 1) {
          const tip = document.createElement('div');
          tip.style.cssText = 'font-size:12px;color:#8c8c8c;margin-bottom:6px';
          tip.textContent = `共 ${pageCount} 页，当前预览第 1 页（首页试渲染）`;
          host.appendChild(tip);
        }
        host.appendChild(canvas);
      }
    } catch (e) {
      console.warn('ofd preview failed:', e);
      renderFallback('OFD 预览解析失败（该库为试集成），请下载后用 OFD 阅读器查看。');
    } finally {
      setBusy(false);
    }
  };

  // 旧版 .doc：若实际是 docx(zip 头 PK..) 则用 docx-preview；真·doc 提示下载
  const doDocLegacy = async () => {
    setBusy(true);
    try {
      const resp = await fetch(attachmentUrl(item!.id), {
        headers: { Authorization: `Bearer ${authToken()}` },
      });
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      const buf = new Uint8Array(await resp.arrayBuffer());
      const isZip = buf.length > 2 && buf[0] === 0x50 && buf[1] === 0x4b;
      if (!isZip) throw new Error('LEGACY_DOC');
      const docx: any = await import('docx-preview');
      await docx.renderAsync(buf, officeRef.current!, null, {
        className: 'pm-docx',
        inWrapper: true,
        ignoreWidth: true,
        ignoreHeight: true,
      });
    } catch (e: any) {
      if (e?.message === 'LEGACY_DOC') {
        renderFallback('旧版 Word(.doc) 暂不支持在线预览，请下载后用本机 Word 打开。');
      } else {
        console.warn('doc preview failed:', e);
        renderFallback('Word 解析失败，请下载后用本机软件打开。');
      }
    } finally {
      setBusy(false);
    }
  };

  // 解析类预览（docx/xlsx/md/text）在附件切换时执行
  useEffect(() => {
    setPdfLoaded(false);
    setFull(false);
    setText(null);
    if (!item) return;
    setBusy(true);
    if (officeRef.current) officeRef.current.innerHTML = '';

    const doOffice = async (kind: 'docx' | 'xlsx') => {
      setBusy(true);
      try {
        const resp = await fetch(attachmentUrl(item.id), {
          headers: { Authorization: `Bearer ${authToken()}` },
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const buf = await resp.arrayBuffer();
        if (kind === 'docx') {
          const docx = await import('docx-preview');
          await docx.renderAsync(new Uint8Array(buf), officeRef.current!, null, {
            className: 'pm-docx',
            inWrapper: true,
            ignoreWidth: true,
            ignoreHeight: true,
          });
        } else {
          const XLSX = await import('xlsx');
          const wb = XLSX.read(new Uint8Array(buf), { type: 'array' });
          const first = wb.SheetNames[0];
          const html = first
            ? XLSX.utils.sheet_to_html(wb.Sheets[first], { editable: false })
            : '<div style="padding:16px;color:#999">表格无数据</div>';
          if (officeRef.current) officeRef.current.innerHTML = html;
        }
      } catch (e) {
        message.error('预览解析失败：' + (e as Error).message);
        if (officeRef.current) {
          officeRef.current.innerHTML =
            '<div style="padding:24px;color:#999">解析失败，请下载后用本机软件打开。</div>';
        }
      } finally {
        setBusy(false);
      }
    };

    if (TEXT_EXTS.has(ext) || MD_EXTS.has(ext)) {
      setBusy(true);
      fetch(attachmentUrl(item.id, 'inline'), {
        headers: { Authorization: `Bearer ${authToken()}` },
      })
        .then((r) => {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.text();
        })
        .then(setText)
        .catch(() => setText('（读取失败，请下载查看）'))
        .finally(() => setBusy(false));
    } else if (DOCX_EXTS.has(ext)) {
      doOffice('docx');
    } else if (XLSX_EXTS.has(ext)) {
      doOffice('xlsx');
    } else if (OFD_EXTS.has(ext)) {
      doOfd();
    } else if (DOC_EXTS.has(ext)) {
      doDocLegacy();
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
  const isPdf = PDF_EXTS.has(ext);
  const isOfficeParsed =
    DOCX_EXTS.has(ext) || XLSX_EXTS.has(ext) || DOC_EXTS.has(ext) || OFD_EXTS.has(ext);

  const renderViewer = (large: boolean) => {
    if (!item) return null;
    const maxH = large ? 'calc(100vh - 140px)' : '65vh';
    const box: CSSProperties = {
      position: 'relative',
      maxHeight: maxH,
      minHeight: 240,
      overflow: 'auto',
    };
    if (isImage) {
      return (
        <div style={{ textAlign: 'center', position: 'relative', minHeight: 240 }}>
          {busy && <Spin style={{ marginTop: 80 }} />}
          <img
            src={attachmentUrl(item.id, 'inline')}
            alt={item.fileName}
            onLoad={() => setBusy(false)}
            onError={() => setBusy(false)}
            style={{
              display: busy ? 'none' : 'block',
              maxWidth: '100%',
              maxHeight: maxH,
              margin: '0 auto',
              objectFit: 'contain',
            }}
          />
        </div>
      );
    }
    if (isPdf) {
      return (
        <div style={box}>
          {!pdfLoaded && (
            <div style={{ textAlign: 'center', padding: 80 }}>
              <Spin tip="PDF 加载中…" />
            </div>
          )}
          <iframe
            src={attachmentUrl(item.id, 'inline')}
            title={item.fileName}
            onLoad={() => setPdfLoaded(true)}
            onError={() => setPdfLoaded(true)}
            style={{
              width: '100%',
              height: maxH,
              border: 'none',
              display: pdfLoaded ? 'block' : 'none',
            }}
          />
        </div>
      );
    }
    if (MD_EXTS.has(ext)) {
      return busy ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin />
        </div>
      ) : (
        <div
          className="pm-md-body"
          style={{ background: '#fafafa', padding: '12px 18px', maxHeight: maxH, overflow: 'auto' }}
        >
          <ReactMarkdown>{text || ''}</ReactMarkdown>
        </div>
      );
    }
    if (TEXT_EXTS.has(ext)) {
      return busy ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin />
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
            maxHeight: maxH,
            overflow: 'auto',
            margin: 0,
          }}
        >
          {text}
        </pre>
      );
    }
    if (isOfficeParsed) {
      return (
        <div style={box}>
          {busy && (
            <div style={{ textAlign: 'center', padding: 80 }}>
              <Spin
                tip={
                  DOCX_EXTS.has(ext) || DOC_EXTS.has(ext)
                    ? 'Word 解析中…'
                    : OFD_EXTS.has(ext)
                      ? 'OFD 解析中…'
                      : 'Excel 解析中…'
                }
              />
            </div>
          )}
          <style>{DOCX_BASE_CSS}</style>
          <div
            ref={officeRef}
            className="pm-office-body"
            style={{ opacity: busy ? 0 : 1, minHeight: 240 }}
          />
        </div>
      );
    }
    // ppt/pptx 等 —— 无稳定纯前端预览方案，明确提示下载
    return (
      <div style={{ textAlign: 'center', padding: 48 }}>
        <Typography.Paragraph type="secondary">
          当前文件类型暂不支持在线预览，请下载后查看。
        </Typography.Paragraph>
        <Button type="primary" icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
          下载文件
        </Button>
      </div>
    );
  };

  if (!item) return null;

  const headerTitle = (
    <Space size={8}>
      <Tag color={extTag.color} style={{ marginInlineEnd: 0 }}>
        {extTag.text}
      </Tag>
      <span>{item.fileName}</span>
      <Typography.Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>
        {fmtFileSize(item.fileSize)}
      </Typography.Text>
    </Space>
  );

  const footer = (
    <Space>
      <Button type="primary" icon={<DownloadOutlined />} href={attachmentUrl(item.id)} download={item.fileName}>
        下载
      </Button>
      <Button icon={<FullscreenOutlined />} onClick={() => setFull(true)}>
        全屏查看
      </Button>
    </Space>
  );

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
            <Tag color={extTag.color} style={{ marginInlineEnd: 0 }}>
              {extTag.text}
            </Tag>
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

  return (
    <Modal title={headerTitle} open onCancel={onClose} width={900} footer={footer}>
      <div style={{ maxHeight: '72vh', overflow: 'auto' }}>{renderViewer(false)}</div>
    </Modal>
  );
}
