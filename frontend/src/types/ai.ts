/**
 * AI 与知识库模块的类型定义
 * ----------------------------------------------------------------
 * 严格对应《AI前端与集成方案》§9「P0 接口契约（冻结）」，字段名与枚举取值一一对应：
 * 前端只认主系统后端的 `/api/ai/*` 契约，**不认 AI 服务的原始契约**——
 * AI 服务字段变了由主系统后端适配层承担，前端无需改动。
 *
 * 枚举一律用字符串字面量联合类型，并配中文标签映射表（与 `config/tagDict.ts` 同一做法：
 * 页面里只查标签、不写死文案，新增取值只需改这里）。
 */

/* ==================== 枚举（§9 定死） ==================== */

/** 文档/附件的索引状态 */
export type AiIndexStatus = 'NOT_PARSED' | 'PARSING' | 'READY' | 'FAILED';

/** 解析任务状态 */
export type AiTaskStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED';

/** 向量检索后端 */
export type AiVectorBackend = 'local' | 'opensearch';

/* ==================== 中文标签映射 ==================== */

export interface AiLabelMeta {
  text: string;
  color: string;
}

/** 索引状态 → 标签（用于文档库、附件列表） */
export const AI_INDEX_STATUS: Record<AiIndexStatus, AiLabelMeta> = {
  NOT_PARSED: { text: '未解析', color: 'default' },
  PARSING: { text: '解析中', color: 'processing' },
  READY: { text: '已可检索', color: 'success' },
  FAILED: { text: '解析失败', color: 'error' },
};

/** 任务状态 → 标签（用于任务队列） */
export const AI_TASK_STATUS: Record<AiTaskStatus, AiLabelMeta> = {
  QUEUED: { text: '排队中', color: 'default' },
  RUNNING: { text: '解析中', color: 'processing' },
  DONE: { text: '已完成', color: 'success' },
  FAILED: { text: '失败', color: 'error' },
};

/** 向量后端 → 标签（服务自检展示） */
export const AI_VECTOR_BACKEND: Record<AiVectorBackend, string> = {
  local: '进程内向量',
  opensearch: 'OpenSearch',
};

/**
 * 查表函数：未知取值原样展示（不吞掉信息）——
 * 后端将来新增枚举时，页面至少能看到原始 code，而不是错标成某个已知状态。
 */
export function aiIndexStatusMeta(code?: string | null): AiLabelMeta {
  if (code && (code as AiIndexStatus) in AI_INDEX_STATUS) {
    return AI_INDEX_STATUS[code as AiIndexStatus];
  }
  return { text: code || '未解析', color: 'default' };
}

export function aiTaskStatusMeta(code?: string | null): AiLabelMeta {
  if (code && (code as AiTaskStatus) in AI_TASK_STATUS) {
    return AI_TASK_STATUS[code as AiTaskStatus];
  }
  return { text: code || '-', color: 'default' };
}

export function aiVectorBackendText(code?: string | null): string {
  if (code && (code as AiVectorBackend) in AI_VECTOR_BACKEND) {
    return AI_VECTOR_BACKEND[code as AiVectorBackend];
  }
  return code || '-';
}

/** 任务状态筛选项（下拉框复用，顺序即业务流程顺序） */
export const AI_TASK_STATUS_OPTIONS: { value: AiTaskStatus; label: string }[] = (
  Object.keys(AI_TASK_STATUS) as AiTaskStatus[]
).map((value) => ({ value, label: AI_TASK_STATUS[value].text }));

/** 索引状态筛选项 */
export const AI_INDEX_STATUS_OPTIONS: { value: AiIndexStatus; label: string }[] = (
  Object.keys(AI_INDEX_STATUS) as AiIndexStatus[]
).map((value) => ({ value, label: AI_INDEX_STATUS[value].text }));

/* ==================== 契约类型（§9 #1~#9） ==================== */

/** #1 GET /api/ai/health —— 服务自检 */
export interface AiHealth {
  /** AI 服务整体是否可用；false 时页面必须显示原因，不得显示成"未找到" */
  available: boolean;
  aiServiceBaseUrl?: string | null;
  vectorBackend?: string | null;
  platformReachable?: boolean | null;
  /** 四个模型的可用性（键名即契约字段名，不做二次包装） */
  models?: Record<'chat' | 'ocr' | 'embedding' | 'reranker', boolean> & Record<string, boolean>;
  documentCount?: number | null;
  pendingTaskCount?: number | null;
  checkedAt?: string | null;
  /** 不可用/降级时的中文原因 */
  message?: string | null;
}

/** #2 GET /api/ai/documents —— 文档库行 */
export interface AiDocument {
  docId: string;
  filename: string;
  projectId?: number | null;
  projectName?: string | null;
  /** 对应主系统附件 id，用于跳转预览 */
  attachmentId?: number | null;
  pageCount?: number | null;
  chunkCount?: number | null;
  sizeBytes?: number | null;
  indexedAt?: string | null;
  indexStatus?: string | null;
  error?: string | null;
}

/** #4 GET /api/ai/tasks —— 任务队列行（#5 单条同结构） */
export interface AiTask {
  /** 主系统 attachment_ai_task 的自增主键（后端按数字输出） */
  taskId: number;
  attachmentId?: number | null;
  filename?: string | null;
  projectId?: number | null;
  projectName?: string | null;
  status: AiTaskStatus;
  /** 0~100 */
  progress?: number | null;
  docId?: string | null;
  error?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** #8 GET /api/ai/attachments/status —— 附件索引状态（批量） */
export interface AiAttachmentStatus {
  attachmentId: number;
  indexStatus: string | null;
  /** 0~100 */
  progress?: number | null;
  docId?: string | null;
  error?: string | null;
}

/** #7 POST /api/ai/attachments/{id}/parse 返回 */
export interface AiParseResult {
  taskId: number;
  docId?: string | null;
}

/** #3 DELETE /api/ai/documents/{docId} 返回 */
export interface AiDeleteResult {
  docId: string;
  deleted: boolean;
}

/** #6 POST /api/ai/tasks/{taskId}/retry 返回 */
export interface AiRetryResult {
  taskId: number;
}

/** #9 POST /api/ai/chat 入参 */
export interface AiChatRequest {
  question: string;
  /** 项目作用域（与 attachmentIds 二选一或同时给，权限在主系统解析） */
  projectId?: number | null;
  attachmentIds?: number[];
  conversationId?: string | null;
  topK?: number | null;
}

/** 引用出处：filename + pageNo 是"可点击跳原文"的最小信息 */
export interface AiCitation {
  /** 答案正文里的角标序号，如 [1] */
  index: number;
  docId?: string | null;
  filename?: string | null;
  pageNo?: number | null;
  snippet?: string | null;
  score?: number | null;
  /**
   * 主系统附件 id：**§9 #9 契约字段，由主系统后端回填**（映射 doc_id → attachment_id，
   * 映射不到给 null）。前端 `api/ai.ts` 的 `resolveCitationAttachment()` 只是**兜底**：
   * 后端未回填（或旧数据）时仍按 docId/文件名去文档列表映射一次，保证引用还能点开。
   */
  attachmentId?: number | null;
}

/** 结构化数据（受控查询工具结果，P0 恒为 []，此处按契约预留） */
export interface AiSystemDatum {
  label: string;
  value?: string | number | null;
  unit?: string | null;
  /** 口径说明（如"含税/截至日期"），让用户知道数字是怎么来的 */
  caliber?: string | null;
  dataTime?: string | null;
  scope?: string | null;
}

/** 工具轨迹（可折叠展示，用于判断"答不出来"是解析问题还是检索问题） */
export interface AiToolTrace {
  name: string;
  summary?: string | null;
  elapsedMs?: number | null;
  hitCount?: number | null;
}

/** #9 POST /api/ai/chat 返回 */
export interface AiChatAnswer {
  conversationId?: string | null;
  answer: string;
  citations?: AiCitation[];
  systemData?: AiSystemDatum[];
  toolTrace?: AiToolTrace[];
  /** true = 本次回答发生了降级（如向量不可用回落关键词），必须用 Alert 明示 */
  degraded?: boolean;
  /** 降级/未找到/数据口径等需要显式告知用户的说明 */
  notice?: string | null;
  elapsedMs?: number | null;
  /** 主系统 `ai_ask_log` 的自增主键（审计留痕用，P1 的问答留痕页据此对账） */
  logId?: number | null;
}
