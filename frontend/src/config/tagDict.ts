/**
 * 项目级“标签字典 / 颜色组”配置
 * ------------------------------------------
 * 所有业务字典标签（状态/类型/阶段/付款节点等）的颜色与文案统一在此配置并复用，
 * 修改这里即可全局调整，无需散落改动各页面。
 * color 使用 Ant Design Tag 支持的预设色。
 */

export type TagColor =
  | 'magenta' | 'red' | 'volcano' | 'orange' | 'gold'
  | 'lime' | 'green' | 'cyan' | 'blue' | 'geekblue' | 'purple'
  | 'success' | 'processing' | 'error' | 'default' | 'warning';

export interface TagMeta {
  text: string;
  color: TagColor;
}

/* ---------- 项目状态 ---------- */
export const PROJECT_STATUS_TAGS: Record<string, TagMeta> = {
  RUN: { text: '进行中', color: 'processing' },
  DONE: { text: '已完结', color: 'success' },
  PAUSE: { text: '暂停', color: 'warning' },
  STOP: { text: '中止', color: 'error' },
};

export const projectStatusTag = (code?: string | null): TagMeta =>
  (code && PROJECT_STATUS_TAGS[code]) || { text: code || '-', color: 'default' };

/* ---------- 项目类型 ---------- */
export const PROJECT_TYPE_TAGS: Record<string, TagMeta> = {
  HW: { text: '硬件项目', color: 'geekblue' },
  SW: { text: '软件项目', color: 'purple' },
};

export const projectTypeTag = (code?: string | null): TagMeta =>
  (code && PROJECT_TYPE_TAGS[code]) || { text: code || '-', color: 'default' };

/* ---------- 阶段状态 ---------- */
export const PHASE_STATUS_TAGS: Record<string, TagMeta> = {
  NOT_STARTED: { text: '未开始', color: 'default' },
  IN_PROGRESS: { text: '进行中', color: 'processing' },
  DONE: { text: '已完成', color: 'success' },
  SKIPPED: { text: '已跳过', color: 'default' },
};

export const phaseStatusTag = (code?: string | null): TagMeta =>
  (code && PHASE_STATUS_TAGS[code]) || { text: code || '-', color: 'default' };

/* ---------- 阶段名称 → 标签（“字典颜色组”） ----------
 * 按关键字将常见阶段归到不同颜色组，项目列表“当前阶段”直接复用；
 * 新增阶段名只需往下方 GROUPS 里补关键字即可。 */
export interface PhaseTagGroup {
  color: TagColor;
  keywords: string[];
}

export const PHASE_TAG_GROUPS: PhaseTagGroup[] = [
  { color: 'blue', keywords: ['立项', '需求'] },
  { color: 'cyan', keywords: ['招标', '采购', '合同', '设计'] },
  { color: 'geekblue', keywords: ['开发', '编码', '测试', '测评'] },
  { color: 'green', keywords: ['试运行', '初验', '终验', '验收', '上线', '到货'] },
  { color: 'volcano', keywords: ['安装', '调试', '部署', '实施'] },
  { color: 'purple', keywords: ['质保', '运维'] },
];

export const phaseNameTag = (name?: string | null): TagMeta => {
  const n = name || '';
  for (const g of PHASE_TAG_GROUPS) {
    if (g.keywords.some((k) => n.includes(k))) {
      return { text: n, color: g.color };
    }
  }
  return { text: n || '-', color: 'default' };
};

/* ---------- 付款节点 ---------- */
export const PAY_NODE_TAGS: Record<string, TagMeta> = {
  PREPAY: { text: '预付款', color: 'gold' },
  ARRIVAL: { text: '到货款', color: 'orange' },
  FIRST_ACCEPT: { text: '初验款', color: 'green' },
  FINAL_ACCEPT: { text: '终验款', color: 'blue' },
  WARRANTY: { text: '质保金', color: 'purple' },
};

export const payNodeTag = (code?: string | null): TagMeta =>
  (code && PAY_NODE_TAGS[code]) || { text: code || '', color: 'default' };

/* ---------- 付款记录状态 ---------- */
export const PAY_STATUS_TAGS: Record<string, TagMeta> = {
  UNPAID: { text: '待支付', color: 'default' },
  PART: { text: '部分支付', color: 'processing' },
  PAID: { text: '已支付', color: 'success' },
};

export const payStatusTag = (code?: string | null): TagMeta =>
  (code && PAY_STATUS_TAGS[code]) || { text: code || '-', color: 'default' };
