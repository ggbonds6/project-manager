/**
 * 流水线视图的小工具（纯函数，不引 antd，便于被抽屉/看板/列表共用）
 * ----------------------------------------------------------------
 * 纪律：这里**只做展示层转换**（毫秒 → 人读时长、对象 → 键值行、按切片键找产物里的逐片记录），
 * **不做任何指标计算**——《RAG流水线设计与并发管理》§4.6 的质量口径必须来自后端字段，
 * 前端算出来的"率/比"无法与后端对账（任务书明确要求"不要伪造计算"）。
 */

/** 毫秒 → 人读时长；null/undefined/负数一律返回 null（由调用方决定显示"暂无数据"还是"-"） */
export function fmtDuration(ms?: number | null): string | null {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return null;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const totalSec = ms / 1000;
  if (totalSec < 60) return `${totalSec.toFixed(1)} s`;
  const totalMin = Math.floor(totalSec / 60);
  const sec = Math.round(totalSec - totalMin * 60);
  if (totalMin < 60) return `${totalMin} min ${sec} s`;
  const hour = Math.floor(totalMin / 60);
  return `${hour} h ${totalMin - hour * 60} min`;
}

/** 值是否"有内容"：null/undefined/空串/空数组都算没有（页面据此写"暂无数据"） */
export function hasValue(v: unknown): boolean {
  if (v === null || v === undefined) return false;
  if (typeof v === 'string') return v.trim() !== '';
  if (Array.isArray(v)) return v.length > 0;
  if (typeof v === 'object') return Object.keys(v as Record<string, unknown>).length > 0;
  return true;
}

/** 原始值 → 单行文本（对象交给调用方逐键展开，不在这里 JSON 序列化，避免糊成一坨） */
export function scalarText(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (Array.isArray(v)) {
    return v
      .map((item) => (item && typeof item === 'object' ? '' : String(item)))
      .filter((s) => s !== '')
      .join('、');
  }
  return '';
}

/** 对象 → 键值行（保留后端给的键名与顺序；值为对象时不展开，交给渲染层递归） */
export function kvEntries(obj?: Record<string, unknown> | null): { key: string; value: unknown }[] {
  if (!obj) return [];
  return Object.keys(obj).map((key) => ({ key, value: obj[key] }));
}

/** 摘要里可能装的是数组（如 `chunks: [...]`）：取出来给"逐片记录"用 */
export function arrayOf(v: unknown): Record<string, unknown>[] {
  return Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x)) : [];
}

/**
 * 在产物摘要里按 `chunk_key` 找**命中切片自己的记录**（溯源链第 3 跳的加强项）。
 *
 * 为什么要有它：冻结契约只给 `artifacts[].content`（摘要），没有"按 chunk_key 反查产物"的接口；
 * 若后端把逐片清单放进了 `chunk_manifest.content`（数组），这里就能取到该片自己的读数，
 * 取不到就如实显示"摘要里没有逐片记录"——**不猜也不编**。
 */
export function findChunkRecord(content: unknown, chunkKey: string): Record<string, unknown> | null {
  if (!chunkKey) return null;
  const pools: Record<string, unknown>[] = [];
  if (Array.isArray(content)) {
    pools.push(...arrayOf(content));
  } else if (content && typeof content === 'object') {
    const obj = content as Record<string, unknown>;
    pools.push(obj);
    for (const k of Object.keys(obj)) pools.push(...arrayOf(obj[k]));
  }
  for (const item of pools) {
    const key = item.chunk_key ?? item.chunkKey;
    if (typeof key === 'string' && key === chunkKey) return item;
  }
  return null;
}
