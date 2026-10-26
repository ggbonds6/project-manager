/**
 * 附件「当前存储可读性」批量探测（给项目详情页的四处附件列表复用）
 * ----------------------------------------------------------------
 * 为什么要这个 hook（而不是每行自己发请求）：
 *  1. 探测接口一次能收 50 个 id，列表加载后**一次批量**问完就够了；每行一个请求会把
 *     一个 40 条附件的项目变成 40 次往返（OBS 上每次还是一个 HEAD）；
 *  2. 失败必须**静默**：这是"探测"，探测不到就不显示标签，绝不能让附件列表因此报错或弹 toast
 *     （所以走 `attachmentApi.availability` 的 silent + 8s 短超时）；
 *  3. 结果只活在本页内存里、不写回附件对象：附件可能被重新上传，探测结论随时会变
 *     （后端也只为它做 60 秒缓存，见 AttachmentAvailabilityService）。
 */
import { useEffect, useState } from 'react';
import { attachmentApi } from '@/api/project';

/** 与后端 AttachmentAvailabilityService.MAX_IDS 对齐：单次最多 50 个 id */
const BATCH_SIZE = 50;

/**
 * @param attachmentIds 当前页要探测的附件 id（空数组 = 不探测）
 * @returns `id → 是否可读`；**没有某个 id 的键**表示"未知"（未探测 / 该批失败 / 超时）⇒ 不显示标签
 */
export function useAttachmentAvailability(attachmentIds: number[]): Record<number, boolean> {
  const [map, setMap] = useState<Record<number, boolean>>({});

  // 依赖用排序后的字符串：数组每次渲染都是新引用，直接进依赖会导致无限请求
  const key = [...attachmentIds].sort((a, b) => a - b).join(',');

  useEffect(() => {
    if (!key) {
      setMap({});
      return;
    }
    const ids = key.split(',').map(Number);
    // 卸载/换项目时把在飞的响应丢掉：慢请求回来不该覆盖新列表的结论
    let cancelled = false;
    void (async () => {
      const next: Record<number, boolean> = {};
      for (let from = 0; from < ids.length; from += BATCH_SIZE) {
        const batch = ids.slice(from, from + BATCH_SIZE);
        try {
          const result = await attachmentApi.availability(batch);
          for (const item of result.items || []) {
            next[item.id] = item.available;
          }
        } catch {
          // 静默降级：这一批失败就不给这批打标签（其它批成功的结果照常显示）。
          // 不 toast、不改附件列表状态——探测失败不等于附件有问题。
        }
      }
      if (!cancelled) {
        setMap(next);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [key]);

  return map;
}
