/**
 * 附件原文预览（AI 模块共用入口）
 * ----------------------------------------------------------------
 * 为什么需要这一层：AI 的引用出处只给 `attachmentId` + 文件名（+ 页码），
 * 拿不到主系统附件列表里的元数据；而主系统的 `AttachmentPreviewModal` 是一个"传入完整
 * AttachmentItem 并自己管开关"的受控组件。
 *
 * 这里做两件事，避免悬浮窗与 `/ai` 页各写一份：
 *  1. 用 attachmentId + 文件名组装出**最小可用**的 AttachmentItem（fileExt 从文件名推断，
 *     fileSize 允许为空——它只用于"大文件先提示"，不影响 PDF/图片/Office 分支渲染）；
 *  2. 把"解析 docId → attachmentId"的过程也收在这里（见 `api/ai.ts` 的缓存映射），
 *     拿不到 id 时明确返回失败，由调用方显示"附件已删除"而不是打开坏链接。
 */
import { useCallback, useState } from 'react';
import type { ReactNode } from 'react';
import { App } from 'antd';
import { resolveCitationAttachment } from '@/api/ai';
import AttachmentPreviewModal from '@/components/AttachmentPreviewModal';
import { AttachmentItem } from '@/types';

/**
 * 跳转附件中心时携带的参数名，两个：
 *  - `aiPreviewAttachmentId`：要自动打开的附件 id；
 *  - `aiPreviewFilename`：文件名（附件 id 在别处可能查不到时可据文件名给出可读提示）。
 *
 * 放在这里（而不是对话组件里）是因为它属于"预览入口"的约定：
 * `/ai` 页与悬浮问答都可能跳转，项目详情页则负责消费这两个参数。
 */
export const AI_PREVIEW_ATTACHMENT_PARAM = 'aiPreviewAttachmentId';
export const AI_PREVIEW_FILENAME_PARAM = 'aiPreviewFilename';

/** 引用只给文件名时推断扩展名，供预览组件选择渲染分支 */
export function extOf(filename?: string | null): string {
  const name = filename || '';
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

export interface AttachmentPreviewState {
  /** 打开预览；返回 false 表示找不到可预览的附件（已 toast 说明） */
  open: (params: {
    attachmentId?: number | null;
    docId?: string | null;
    filename?: string | null;
    pageNo?: number | null;
  }) => Promise<boolean>;
  /** 正在解析附件 id（按钮可据此显示"打开中"） */
  opening: boolean;
  /** 渲染预览弹窗（调用方把它放到自己的 JSX 里） */
  el: ReactNode;
}

export function useAttachmentPreview(): AttachmentPreviewState {
  const { message } = App.useApp();
  const [target, setTarget] = useState<{ item: AttachmentItem; pageNo?: number | null } | null>(null);
  const [opening, setOpening] = useState(false);

  const open = useCallback(
    async ({
      attachmentId,
      docId,
      filename,
      pageNo,
    }: {
      attachmentId?: number | null;
      docId?: string | null;
      filename?: string | null;
      pageNo?: number | null;
    }) => {
      let id = attachmentId ?? null;
      if (!id) {
        setOpening(true);
        try {
          id = await resolveCitationAttachment(docId, filename);
        } finally {
          setOpening(false);
        }
      }
      if (!id) {
        message.warning(
          `出处「${filename || '附件'}」在主系统中已查不到对应附件（可能已被删除），无法打开原文。`,
        );
        return false;
      }
      setTarget({
        item: {
          id,
          bizType: '',
          bizId: 0,
          fileName: filename || `附件 #${id}`,
          fileExt: extOf(filename),
        },
        pageNo,
      });
      return true;
    },
    [message],
  );

  const el = target ? (
    <AttachmentPreviewModal
      item={target.item}
      pageNo={target.pageNo}
      onClose={() => setTarget(null)}
    />
  ) : null;

  return { open, opening, el };
}

/**
 * 生成"跳到某项目附件中心并自动打开该附件预览"的路径。
 *
 * 用途：需要离开当前页面去看原文时（例如从 `/ai` 文档库跳到主系统项目页），
 * 带上附件 id 与文件名，由项目详情页构造最小 AttachmentItem 后就地预览——
 * 与 AI 模块内的预览是同一个组件，不需要额外接口。
 */
export function buildAttachmentPreviewPath(
  projectId: number,
  attachmentId: number,
  filename?: string | null,
): string {
  const params = new URLSearchParams({ tab: 'attach' });
  params.set(AI_PREVIEW_ATTACHMENT_PARAM, String(attachmentId));
  if (filename) params.set(AI_PREVIEW_FILENAME_PARAM, filename);
  return `/projects/${projectId}?${params.toString()}`;
}
