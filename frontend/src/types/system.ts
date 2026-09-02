export interface AdminUser {
  id: number;
  account: string;
  name: string;
  deptId?: number | null;
  deptName?: string | null;
  role: string;
  status: number;
  lastLoginTime?: string | null;
  createTime?: string | null;
}

export interface DeptRow {
  id: number;
  parentId: number;
  name: string;
  orderNo?: number;
}

export interface DictRow {
  id?: number;
  dictType: string;
  code: string;
  name: string;
  sortNo?: number;
}

export interface DictTypeInfo {
  type: string;
  count: number;
}

export interface PhaseTemplateRow {
  id?: number;
  projectType: string;
  phaseName: string;
  sortNo?: number;
  weight?: number;
  payNode?: string | null;
  description?: string | null;
  attachTypeHints?: string | null;
  skipable?: number;
}

export interface LogRow {
  id: number;
  userId?: number | null;
  userName?: string | null;
  bizType?: string;
  bizId?: number | null;
  action?: string;
  detail?: string | null;
  createTime?: string | null;
}

export const BIZ_TYPES = [
  { value: 'USER', label: '用户' },
  { value: 'DEPT', label: '部门' },
  { value: 'DICT', label: '字典' },
  { value: 'TEMPLATE', label: '阶段模板' },
  { value: 'PROJECT', label: '项目' },
  { value: 'PHASE', label: '阶段' },
  { value: 'ATTACHMENT', label: '附件' },
  { value: 'PAYMENT', label: '付款' },
  { value: 'LOGIN', label: '登录' },
];
