export type Role = 'ADMIN' | 'MANAGER' | 'VIEWER';

export interface CurrentUser {
  id: number;
  account: string;
  name: string;
  role: Role;
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

export interface UserOption {
  id: number;
  account: string;
  name: string;
  role: Role;
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
  /** 所属总项目 id；为空/编辑顶层时传 null */
  parentId?: number | null;
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

export interface PaymentBrief {
  nodeCode: string;
  nodeName: string;
  status: string;
  planAmount?: number | null;
  paidAmount?: number;
  paidDate?: string | null;
}

export interface ProjectListItem {
  id: number;
  code: string;
  name: string;
  type: 'HW' | 'SW';
  status: string;
  ownerUnit?: string | null;
  /** 父(总)项目 id，null=顶层 */
  parentId?: number | null;
  /** 子项目数量（>0=总项目容器，无自身流程） */
  childCount?: number;
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
  payments?: PaymentBrief[];
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
  parentName?: string | null;
  childCount?: number;
  memberNames?: string[];
  contractTotal?: number;
  actualFinishDate?: string | null;
  currentPhaseName?: string | null;
  overallProgress?: number;
  phases: PhaseItem[];
  createTime?: string | null;
  updateTime?: string | null;
  /** 项目概览：README 式介绍 + 二级功能模块清单 */
  overview?: OverviewData;
}

/** 项目概览-功能模块节点（最多二级） */
export interface OverviewModule {
  name: string;
  description?: string;
  children?: { name: string; description?: string }[];
}

export interface OverviewData {
  introMd?: string | null;
  modules?: OverviewModule[] | null;
}

export interface PaymentItem {
  id?: number;
  projectId: number;
  contractId?: number | null;
  nodeCode: string;
  nodeName?: string;
  conditionDesc?: string;
  planAmount?: number | null;
  planDate?: string | null;
  paidAmount?: number;
  paidDate?: string | null;
  status: string;
  remark?: string;
  // ── 付款过程信息（V11）：资金情况以"付款"为主线 ──
  /** 付款方式（字典 PAY_METHOD） */
  payMethod?: string | null;
  /** 经办人 */
  handler?: string | null;
  /** 发票号 */
  invoiceNo?: string | null;
  /** 记账凭证号 / 报销单号 */
  voucherNo?: string | null;
  /** 收款账户快照（付款当时核对用） */
  payeeName?: string | null;
  payeeBank?: string | null;
  payeeAccount?: string | null;
}

export const PAYMENT_STATUS: Record<string, { text: string; color: string }> = {
  UNPAID: { text: '待支付', color: 'default' },
  PART: { text: '部分支付', color: 'processing' },
  PAID: { text: '已支付', color: 'success' },
};

/** 合同：可覆盖一个或多个(子)项目 */
export interface ContractItem {
  id?: number;
  name: string;
  contractNo?: string | null;
  vendorName?: string | null;
  vendorContact?: string | null;
  bidType?: string | null;
  bidAmount?: number | null;
  contractAmount?: number | null;
  changeAmount?: number;
  planAmount?: number | null;
  scopeRemark?: string | null;
  remark?: string | null;
  // ── V10 新增：政府合同常见字段 ──
  /** 合同类型（字典 CONTRACT_TYPE）：MAIN 施工合同 / TEST 第三方测评 / … */
  contractType?: string | null;
  /** 甲方（建设单位） */
  partyA?: string | null;
  signDate?: string | null;
  effectiveDate?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  /** 合同状态（字典 CONTRACT_STATUS） */
  contractStatus?: string | null;
  payeeName?: string | null;
  payeeBank?: string | null;
  payeeAccount?: string | null;
  acceptanceStandard?: string | null;
  warrantyMonths?: number | null;
  warrantyAmount?: number | null;
  settleAmount?: number | null;
  createTime?: string | null;
  updateTime?: string | null;
}

/** 项目分工（V10）：模块 / 子模块的负责方、负责人、计划时间与进度 */
export interface ProjectDivisionItem {
  id?: number;
  projectId: number;
  /** 父级分工 id（空 = 顶层模块），用于"模块 → 子模块"层级 */
  parentId?: number | null;
  name: string;
  /** 负责方：OWNER 甲方 / VENDOR 乙方 / BOTH 双方 */
  ownerSide?: string | null;
  ownerName?: string | null;
  vendorOwner?: string | null;
  planDevDate?: string | null;
  planTestDate?: string | null;
  planOnlineDate?: string | null;
  /** 当前进度 0-100 */
  progress?: number | null;
  /** 状态（字典 DIVISION_STATUS）：TODO/DOING/DONE/RISK */
  status?: string | null;
  remark?: string | null;
  sortNo?: number | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface AttachmentItem {
  id: number;
  bizType: string;
  bizId: number;
  phaseId?: number | null;
  phaseName?: string | null;
  /** bizType=CONTRACT 时由后端补齐合同名，便于附件中心按合同分组 */
  bizName?: string | null;
  attachType?: string | null;
  fileName: string;
  fileSize?: number;
  fileExt?: string;
  uploadUserId?: number | null;
  uploadUserName?: string | null;
  uploadTime?: string | null;
  /**
   * AI 知识库索引状态（V13 的 attachment.ai_index_status）。
   * 后端在附件列表里带上时直接用，省掉一次批量状态查询；
   * 没带也没关系——附件中心会调 `GET /api/ai/attachments/status` 补齐（见 useAiAttachmentStatus）。
   */
  aiIndexStatus?: string | null;
  /** 解析失败原因（与 aiIndexStatus=FAILED 搭配展示） */
  aiError?: string | null;
}

/** 后台上传任务（上传记录）：文件已受理，存储写入在后台进行 */
export interface UploadTaskItem {
  id: number;
  projectId?: number | null;
  bizType: string;
  bizId: number;
  attachType?: string | null;
  /** 归属阶段名（bizType=PROJECT_PHASE 时由后端补齐，用于展示「所属阶段」标签） */
  phaseName?: string | null;
  /** 归属合同名（bizType=CONTRACT 时由后端补齐，用于展示「所属合同」标签） */
  bizName?: string | null;
  fileName: string;
  fileSize?: number;
  fileExt?: string;
  /** PENDING 已受理 | UPLOADING 写入存储中 | SUCCESS | FAILED */
  status: 'PENDING' | 'UPLOADING' | 'SUCCESS' | 'FAILED';
  /** 0~100 */
  progress?: number;
  errorMsg?: string | null;
  /** 成功后关联的正式附件 id */
  attachmentId?: number | null;
  uploadUserId?: number | null;
  createTime?: string | null;
  finishTime?: string | null;
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
