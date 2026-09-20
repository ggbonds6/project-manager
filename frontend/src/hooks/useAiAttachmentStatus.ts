/**
 * 附件 AI 索引状态（给附件列表/文档库复用）
 * ----------------------------------------------------------------
 * 为什么要单独抽一个 hook：
 *  1. 状态来自 AI 模块的批量接口 `#8 GET /api/ai/attachments/status`，与主系统附件列表是两个数据源，
 *     组件里直接混着写会很乱，而且项目页与「/ai 文档库」都要用；
 *  2. 解析是异步的（NOT_PARSED → PARSING → READY/FAILED），只有"存在解析中"时才轮询，
 *     避免无脑定时请求把后端打满；
 *  3. `parseAttachment`（#7）成功后不立刻改本地状态为"已可检索"，而是重新拉一次真实状态——
 *     界面不撒谎，一切以服务端为准。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { aiApi } from '@/api/ai';
import { AiAttachmentStatus } from '@/types/ai';

/** 轮询间隔：解析通常几秒到几十秒，3s 足够跟上进度又不至于压后端 */
const POLL_INTERVAL_MS = 3000;

export interface UseAiAttachmentStatus {
  /** attachmentId → 状态 */
  map: Record<number, AiAttachmentStatus>;
  loading: boolean;
  /** 最近一次拉取失败的原因（AI 服务不可用时用于明确提示，而不是装作"没有状态"） */
  error: string | null;
  /** 手动触发某个附件的重新解析（#7）；返回 false 表示失败 */
  reparse: (attachmentId: number) => Promise<boolean>;
  /** 正在触发解析中的附件 id（按钮 loading 用） */
  parsingIds: number[];
  refresh: () => Promise<void>;
}

/**
 * @param attachmentIds 需要查询状态的附件 id（空数组表示不查询）
 * @param enabled       false 时不发请求（例如附件页签还没打开）
 */
export function useAiAttachmentStatus(
  attachmentIds: number[],
  enabled = true,
): UseAiAttachmentStatus {
  const [map, setMap] = useState<Record<number, AiAttachmentStatus>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [parsingIds, setParsingIds] = useState<number[]>([]);

  // 依赖用排序后的字符串：数组每次渲染都是新引用，直接进依赖会导致无限拉取
  const key = [...attachmentIds].sort((a, b) => a - b).join(',');

  const refresh = useCallback(async () => {
    const ids = key ? key.split(',').map(Number) : [];
    if (!enabled || ids.length === 0) {
      setMap({});
      return;
    }
    setLoading(true);
    try {
      const list = await aiApi.attachmentsStatus(ids);
      const next: Record<number, AiAttachmentStatus> = {};
      for (const s of list) next[s.attachmentId] = s;
      setMap(next);
      setError(null);
    } catch (e) {
      // 拦截器已 toast；这里保留原因用于在附件中心显示"AI 服务不可用"提示
      setError((e as Error).message || 'AI 服务不可用');
    } finally {
      setLoading(false);
    }
  }, [key, enabled]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // 有"解析中"才轮询；全部落定后自动停
  const hasParsing = Object.values(map).some((s) => s.indexStatus === 'PARSING');
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  useEffect(() => {
    if (!hasParsing || !enabled) return;
    const timer = window.setInterval(() => {
      void refreshRef.current();
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [hasParsing, enabled]);

  const reparse = useCallback(
    async (attachmentId: number): Promise<boolean> => {
      setParsingIds((prev) => [...prev, attachmentId]);
      try {
        await aiApi.parseAttachment(attachmentId);
        await refresh();
        return true;
      } catch {
        // 拦截器已提示失败原因；保持原状态不变（不假装已进入解析中）
        return false;
      } finally {
        setParsingIds((prev) => prev.filter((x) => x !== attachmentId));
      }
    },
    [refresh],
  );

  return { map, loading, error, reparse, parsingIds, refresh };
}
