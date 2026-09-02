import dayjs from 'dayjs';

/** 金额展示：千分位 + 两位小数；空值显示 '-' */
export function fmtMoney(v?: number | null): string {
  if (v === null || v === undefined || Number.isNaN(Number(v))) {
    return '-';
  }
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** 万元展示 */
export function fmtWan(v?: number | null): string {
  if (v === null || v === undefined || Number.isNaN(Number(v))) {
    return '-';
  }
  return (Number(v) / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

export function fmtDate(v?: string | null): string {
  return v ? dayjs(v).format('YYYY-MM-DD') : '-';
}

export function fmtDateTime(v?: string | null): string {
  return v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-';
}

/** 文件大小可读化 */
export function fmtFileSize(bytes?: number | null): string {
  if (!bytes && bytes !== 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

export function today(): string {
  return dayjs().format('YYYY-MM-DD');
}
