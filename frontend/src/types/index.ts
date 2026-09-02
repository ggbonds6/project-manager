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

/** 统一响应（code=0 成功），http 拦截器已解包，直接得到 data */
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

export interface ProjectListItem {
  id: number;
  code: string;
  name: string;
  type: 'HW' | 'SW';
  status: string;
  ownerUnit?: string | null;
  ownerDeptId?: number | null;
  managerUserId?: number | null;
  vendorName?: string | null;
  contractAmount?: number | null;
  budgetAmount?: number | null;
  approveDate?: string | null;
  planFinishDate?: string | null;
  actualFinishDate?: string | null;
  updateTime?: string | null;
}
