export type Role = 'ADMIN' | 'MANAGER' | 'VIEWER';

export interface CurrentUser {
  id: number;
  account: string;
  name: string;
  role: Role;
  deptId?: number | null;
}

export interface LoginParams {
  account: string;
  password: string;
}

export interface LoginResult {
  token: string;
  user: CurrentUser;
}

export interface R<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

export interface DictItem {
  id?: number;
  dictType: string;
  code: string;
  name: string;
  sortNo?: number;
}

export interface DeptItem {
  id: number;
  parentId: number;
  name: string;
  orderNo?: number;
}

export interface UserOption {
  id: number;
  account: string;
  name: string;
  role: Role;
  deptId?: number | null;
  status?: number;
}

/** 项目状态元数据（展示用，与后端约定） */
export const PROJECT_STATUS: Record<string, { text: string; color: string }> = {
  RUN: { text: '进行中', color: 'processing' },
  DONE: { text: '已完结', color: 'success' },
  PAUSE: { text: '暂停', color: 'warning' },
  STOP: { text: '中止', color: 'error' },
};

export const PROJECT_TYPES: Record<string, string> = {
  HW: '硬件项目',
  SW: '软件项目',
};

export const PHASE_STATUS: Record<string, { text: string; color: string }> = {
  NOT_STARTED: { text: '未开始', color: 'default' },
  IN_PROGRESS: { text: '进行中', color: 'processing' },
  DONE: { text: '已完成', color: 'success' },
  SKIPPED: { text: '已跳过', color: 'default' },
};

export interface ProjectForm {
  code?: string;
  name: string;
  type: 'HW' | 'SW';
  status?: string;
  ownerUnit?: string;
  ownerDeptId?: number | null;
  managerUserId?: number | null;
  memberIds?: number[];
  vendorName?: string;
  vendorContact?: string;
  approveNo?: string;
  budgetAmount?: number | null;
  fundSource?: string;
  bidType?: string;
  bidAmount?: number | null;
  contractNo?: string;
  contractAmount?: number | null;
  changeAmount?: number;
  approveDate?: string | null;
  planStartDate?: string | null;
  planFinishDate?: string | null;
  contentSummary?: string;
  projectSource?: string;
  remark?: string;
}

export interface ProjectListItem {
  id: number;
  code: string;
  name: string;
  type: 'HW' | 'SW';
  status: string;
  ownerUnit?: string | null;
  ownerDeptId?: number | null;
  ownerDeptName?: string | null;
  managerUserId?: number | null;
  managerName?: string | null;
  vendorName?: string | null;
  budgetAmount?: number | null;
  contractAmount?: number | null;
  paidAmount?: number | null;
  approveDate?: string | null;
  planFinishDate?: string | null;
  actualFinishDate?: string | null;
  currentPhaseName?: string | null;
  overallProgress?: number;
  updateTime?: string | null;
}

export interface PhaseItem {
  id: number;
  projectId: number;
  phaseName: string;
  sortNo: number;
  weight: number;
  payNode?: string | null;
  status: string;
  percent: number;
  planStartDate?: string | null;
  planFinishDate?: string | null;
  actualStartDate?: string | null;
  actualFinishDate?: string | null;
  managerUserId?: number | null;
  managerName?: string | null;
  note?: string | null;
  resultFields?: Record<string, unknown> | null;
  updateTime?: string | null;
}

export interface ProjectDetail extends ProjectForm {
  id: number;
  managerName?: string | null;
  ownerDeptName?: string | null;
  memberNames?: string[];
  contractTotal?: number;
  actualFinishDate?: string | null;
  currentPhaseName?: string | null;
  overallProgress?: number;
  phases: PhaseItem[];
  createTime?: string | null;
  updateTime?: string | null;
}

export interface PaymentItem {
  id?: number;
  projectId: number;
  nodeCode: string;
  nodeName?: string;
  conditionDesc?: string;
  planAmount?: number | null;
  planDate?: string | null;
  paidAmount?: number;
  paidDate?: string | null;
  status: string;
  remark?: string;
}

export const PAYMENT_STATUS: Record<string, { text: string; color: string }> = {
  UNPAID: { text: '待支付', color: 'default' },
  PART: { text: '部分支付', color: 'processing' },
  PAID: { text: '已支付', color: 'success' },
};

export interface AttachmentItem {
  id: number;
  bizType: string;
  bizId: number;
  phaseId?: number | null;
  phaseName?: string | null;
  attachType?: string | null;
  fileName: string;
  fileSize?: number;
  fileExt?: string;
  uploadUserId?: number | null;
  uploadUserName?: string | null;
  uploadTime?: string | null;
}

export interface LogItem {
  id: number;
  userId?: number | null;
  userName?: string | null;
  bizType?: string;
  bizId?: number | null;
  action: string;
  detail?: string | null;
  createTime?: string | null;
}
