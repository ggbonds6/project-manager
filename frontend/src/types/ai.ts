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
  /** 四个模型的可用性（键名即契约字段名，不做二次包装）；`null` = 本次**未探测**，既不是可用也不是不可用 */
  models?: Record<'chat' | 'ocr' | 'embedding' | 'reranker', boolean | null> &
    Record<string, boolean | null>;
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
  /**
   * 切片键（snake_case 主名 `chunk_key`，见 《RAG流水线设计与并发管理》§4.5.1 第 3 跳）：
   * 形如 `docId:pageNo:sha1:cleanRev:chunkRev`，是"从答案 `[n]` 回溯到切片/产物"的载体。
   *
   * **现状**：主系统侧尚未回填 ⇒ 可能为 null/缺失；前端一律按"该环节未上报"显式说明，
   * 不用 docId+pageNo 伪造出等价语义（缺数据要看得见，不能显示成空白）。
   * 兼容读取统一走 {@link citationChunkKey}（后端给 `chunk_key` 或 `chunkKey` 都认）。
   */
  chunkKey?: string | null;
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

/** 兼容读取引用里的切片键：主名 `chunk_key`（snake_case），同时认 camelCase */
export function citationChunkKey(c: AiCitation): string | null {
  const raw = c as unknown as Record<string, unknown>;
  const v = raw.chunk_key ?? raw.chunkKey ?? c.chunkKey;
  return typeof v === 'string' && v.trim() !== '' ? v : null;
}

/* ==================== 解析流水线（RAG 任务流，§4.3~§4.9） ====================
 *
 * 契约来源：《RAG流水线设计与并发管理》§4 与任务书"冻结的接口"，路径按 `code-1.4` 的定案走
 * **用户侧入口**（浏览器带用户 JWT）：
 *   `GET /api/ai/runs?attachment_id=&status=&page=&size=`
 *   `GET /api/ai/runs/{run_id}/pipeline`
 * 同形状的 `GET /api/ai/pipeline/**` 是**服务间回写前缀**（只认 `X-AI-Service-Token`，用户 JWT 调 401），
 * 前端**不用**它；两侧字段名与分页信封完全一致（snake_case 主名 + camelCase 别名）。
 *
 * 两条纪律（与 §9 契约同一口径）：
 *  ① 前端**兼容读取** camelCase：归一化函数同时认 `run_id` 与 `runId`，
 *     避免后端某次序列化风格变化就让整页空白；
 *  ② 后端未提供的字段一律保留为 null，**页面显式写"暂无数据 / 该环节未上报"**，
 *     绝不在前端"算一个看起来合理的值"（§4.6 的指标口径必须来自后端）。
 */

/** 阶段键（顺序固定，§3.2）：前五段是解析主链路，后两段属问答链路（本视图折叠展示） */
export type AiPipelineStageKey =
  | 'PARSE'
  | 'CLEAN'
  | 'CHUNK'
  | 'EMBED'
  | 'INDEX'
  | 'RETRIEVE'
  | 'GENERATE';

/** 解析主链路五段的固定顺序（阶段条按此渲染，不随后端返回顺序变化） */
export const AI_PIPELINE_STAGE_ORDER: AiPipelineStageKey[] = ['PARSE', 'CLEAN', 'CHUNK', 'EMBED', 'INDEX'];

/** 问答链路阶段（本视图不作为阶段条展示，折叠成一块只读信息） */
export const AI_PIPELINE_QA_STAGE_ORDER: AiPipelineStageKey[] = ['RETRIEVE', 'GENERATE'];

/** 阶段键 → 中文名（后端给 `stage_name` 时以后端为准，这里只是兜底） */
export const AI_PIPELINE_STAGE_NAME: Record<string, string> = {
  PARSE: '识别',
  CLEAN: '清洗',
  CHUNK: '切分',
  EMBED: '向量化',
  INDEX: '入库',
  RETRIEVE: '召回',
  GENERATE: '生成',
};

/** 任务/阶段状态（§4.4 状态机；PARTIAL = 部分成功，必须与 DONE 视觉区分） */
export type AiPipelineStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'DONE'
  | 'PARTIAL'
  | 'SKIPPED'
  | 'FAILED'
  | 'CANCELLED'
  | 'DEAD';

/**
 * 状态 → 标签。
 * `PARTIAL` 用 **warning（橙）**：它意味着"可用但不完整（有项需人工复核）"，
 * 与 `DONE` 的 success（绿）必须一眼可分（任务书硬要求）。
 * `DEAD` 用 **深红**（`#a8071a`），与 `FAILED` 的红区分：前者是重试耗尽后的死信。
 * `SKIPPED` 用 default（灰）：它是"按 rev 复用而跳过"，不是失败；原因另在卡片里显示。
 */
export const AI_PIPELINE_STATUS: Record<AiPipelineStatus, AiLabelMeta> = {
  PENDING: { text: '排队中', color: 'default' },
  RUNNING: { text: '进行中', color: 'processing' },
  DONE: { text: '已完成', color: 'success' },
  PARTIAL: { text: '部分成功', color: 'warning' },
  SKIPPED: { text: '已跳过', color: 'default' },
  FAILED: { text: '失败', color: 'error' },
  CANCELLED: { text: '已取消', color: 'default' },
  DEAD: { text: '死信', color: '#a8071a' },
};

export function aiPipelineStatusMeta(code?: string | null): AiLabelMeta {
  const key = (code || '').toUpperCase();
  if (key && (key as AiPipelineStatus) in AI_PIPELINE_STATUS) {
    return AI_PIPELINE_STATUS[key as AiPipelineStatus];
  }
  // 未知取值原样展示（与 aiTaskStatusMeta 同一取舍：不吞信息、不误标成某个已知状态）
  return { text: code || '-', color: 'default' };
}

export function aiPipelineStageText(key?: string | null, backendName?: string | null): string {
  if (backendName) return backendName;
  const k = (key || '').toUpperCase();
  return AI_PIPELINE_STAGE_NAME[k] || key || '-';
}

/** 任务分类（§5.2；`attachment_ai_task.task_type`） */
export const AI_PIPELINE_TASK_TYPE: Record<string, string> = {
  PARSE: '上传解析',
  RETRY: '重试',
  REINDEX: '重建索引',
  EMBED: '批量向量化',
  EVAL: '评测跑批',
};

export function aiPipelineTaskTypeText(code?: string | null): string {
  const k = (code || '').toUpperCase();
  return AI_PIPELINE_TASK_TYPE[k] || code || '-';
}

/** 产物类型（§4.7；五类，主系统只存摘要、全文在 AI 服务 `work/` 由 `object_key` 指路） */
export const AI_PIPELINE_ARTIFACT_META: Record<string, { text: string; hint: string }> = {
  page_digest: { text: '页级摘要', hint: '页数 / 空页 / OCR 置信度等页级读数' },
  clean_diff: { text: '清洗差异', hint: '剔除行数 / 剔除字符比 / 命中规则' },
  chunk_manifest: { text: '切片清单', hint: '切片数 / 平均长度 / 表格块 / 切分参数' },
  embed_batch: { text: '向量化批次', hint: '批数 / 成功 / 失败 / 耗时' },
  index_result: { text: '索引结果', hint: '写入条数 / 失败 / 耗时' },
};

export function aiPipelineArtifactText(type?: string | null): string {
  const k = (type || '').toLowerCase();
  return AI_PIPELINE_ARTIFACT_META[k]?.text || type || '-';
}

export function aiPipelineArtifactHint(type?: string | null): string {
  const k = (type || '').toLowerCase();
  return AI_PIPELINE_ARTIFACT_META[k]?.hint || '未知产物类型（后端新增时前端原样展示）';
}

/** 终态（终态一律停止轮询；与 §4.4.1 的任务级状态机一致） */
const PIPELINE_TERMINAL: string[] = ['DONE', 'PARTIAL', 'SKIPPED', 'FAILED', 'CANCELLED', 'DEAD'];

/**
 * 是否终态。
 * **未知状态按"未终态"处理**：宁可多轮询一次，也不要因为后端新增了一个状态
 * 就永久停在"进行中"的外观上（漏掉状态变化比多一次请求严重）。
 */
export function isPipelineTerminal(code?: string | null): boolean {
  return PIPELINE_TERMINAL.includes((code || '').toUpperCase());
}

/** 进行中/排队中（列表"进行中"段与角标计数的判据） */
export function isPipelineActive(code?: string | null): boolean {
  const k = (code || '').toUpperCase();
  return k === 'RUNNING' || k === 'PENDING';
}

/** 失败（含重试耗尽的死信）：列表"只看失败"的判据，失败行整行标红底 */
export function isPipelineFailed(code?: string | null): boolean {
  const k = (code || '').toUpperCase();
  return k === 'FAILED' || k === 'DEAD';
}

/** 状态筛选项（下拉框复用；顺序即 §4.4.1 状态机顺序） */
export const AI_PIPELINE_STATUS_OPTIONS: { value: AiPipelineStatus; label: string }[] = (
  Object.keys(AI_PIPELINE_STATUS) as AiPipelineStatus[]
).map((value) => ({ value, label: AI_PIPELINE_STATUS[value].text }));

/** 流水线任务（列表行；详情继承本接口的字段） */
export interface AiPipelineRun {
  runId: string;
  attachmentId?: number | null;
  /** 文件名：列表契约未强制给（`attachment_id` 是主键），后端给了就显示 */
  filename?: string | null;
  status: AiPipelineStatus | string;
  taskType?: string | null;
  priority?: number | null;
  /** 当前阶段（`stage_key` 机器可读 / `stage` 中文名，二者后端至少给一个时显示） */
  stageKey?: string | null;
  stage?: string | null;
  /** 当前阶段计量（0 表示总量未知 ⇒ 前端显示"不确定进度"） */
  stageDone?: number | null;
  stageTotal?: number | null;
  elapsedMs?: number | null;
  errorMsg?: string | null;
  attempts?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 单个阶段（阶段条 + 阶段卡片的数据源） */
export interface AiPipelineStage {
  stageKey: string;
  stageName?: string | null;
  seq?: number | null;
  status: AiPipelineStatus | string;
  elapsedMs?: number | null;
  itemCount?: number | null;
  itemFailed?: number | null;
  /** `counters_json` 解析后的键值表（后端直接给对象，前端不再二次计算） */
  counters?: Record<string, unknown> | null;
  inputDigest?: string | null;
  outputDigest?: string | null;
  errorMsg?: string | null;
  attempts?: number | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

/** 产物（只存摘要；全文在 AI 服务 `work/`，`object_key` 指路） */
export interface AiPipelineArtifact {
  artifactType: string;
  artifactRev?: number | null;
  /** 摘要内容（形状由后端定：对象/数组都要能渲染，见 PipelineArtifactDrawer） */
  content?: unknown;
  objectKey?: string | null;
  charCount?: number | null;
  sha1?: string | null;
  itemCount?: number | null;
  createdAt?: string | null;
}

/** 事件流（状态机审计：谁 / 何时 / 为什么把状态改成什么） */
export interface AiPipelineEvent {
  stageKey?: string | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  reason?: string | null;
  operator?: string | null;
  createdAt?: string | null;
}

/** `GET /api/ai/runs/{run_id}/pipeline` 详情（用户侧入口；服务间同形状入口见本节文件头注释） */
export interface AiPipelineRunDetail extends AiPipelineRun {
  stages: AiPipelineStage[];
  artifacts: AiPipelineArtifact[];
  events: AiPipelineEvent[];
  /**
   * 投影兜底提示（§4.4.3 的 C 方案）：主系统发现任务 RUNNING 但没有阶段行时，
   * 按 AI 任务详情补投影。此字段非空即说明"这份视图是投影来的"，
   * 页面必须显示"AI 侧可能已中断"，而不是让用户以为后端一直在正常跑。
   */
  projectionNote?: string | null;
}

/* ---------- snake_case → camelCase 归一化（契约以 snake_case 为主名） ---------- */

function asRecord(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

function pickStr(raw: Record<string, unknown>, ...keys: string[]): string | null {
  for (const k of keys) {
    const v = raw[k];
    if (typeof v === 'string' && v.trim() !== '') return v;
    if (typeof v === 'number' && Number.isFinite(v)) return String(v);
  }
  return null;
}

function pickNum(raw: Record<string, unknown>, ...keys: string[]): number | null {
  for (const k of keys) {
    const v = raw[k];
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
  }
  return null;
}

function pickObj(raw: Record<string, unknown>, ...keys: string[]): Record<string, unknown> | null {
  for (const k of keys) {
    const v = raw[k];
    if (v && typeof v === 'object' && !Array.isArray(v)) return v as Record<string, unknown>;
  }
  return null;
}

/** 列表行归一化。`run_id` 缺失的行无法打开详情，由 api 层过滤掉（见 `aiApi.pipelineRuns`） */
export function normalizePipelineRun(rawInput: unknown): AiPipelineRun {
  const raw = asRecord(rawInput);
  return {
    runId: pickStr(raw, 'run_id', 'runId', 'id') ?? '',
    attachmentId: pickNum(raw, 'attachment_id', 'attachmentId'),
    filename: pickStr(raw, 'filename', 'file_name', 'fileName'),
    status: (pickStr(raw, 'status') ?? 'PENDING').toUpperCase(),
    taskType: pickStr(raw, 'task_type', 'taskType'),
    priority: pickNum(raw, 'priority'),
    stageKey: pickStr(raw, 'stage_key', 'stageKey'),
    stage: pickStr(raw, 'stage'),
    stageDone: pickNum(raw, 'stage_done', 'stageDone'),
    stageTotal: pickNum(raw, 'stage_total', 'stageTotal'),
    elapsedMs: pickNum(raw, 'elapsed_ms', 'elapsedMs'),
    errorMsg: pickStr(raw, 'error_msg', 'errorMsg', 'error'),
    attempts: pickNum(raw, 'attempts'),
    createdAt: pickStr(raw, 'created_at', 'createdAt', 'create_time', 'createTime'),
    updatedAt: pickStr(raw, 'updated_at', 'updatedAt', 'update_time', 'updateTime'),
  };
}

export function normalizePipelineStage(rawInput: unknown): AiPipelineStage {
  const raw = asRecord(rawInput);
  return {
    stageKey: (pickStr(raw, 'stage_key', 'stageKey') ?? '').toUpperCase(),
    stageName: pickStr(raw, 'stage_name', 'stageName'),
    seq: pickNum(raw, 'seq'),
    status: (pickStr(raw, 'status') ?? 'PENDING').toUpperCase(),
    elapsedMs: pickNum(raw, 'elapsed_ms', 'elapsedMs'),
    itemCount: pickNum(raw, 'item_count', 'itemCount'),
    itemFailed: pickNum(raw, 'item_failed', 'itemFailed'),
    counters: pickObj(raw, 'counters', 'counters_json', 'countersJson'),
    inputDigest: pickStr(raw, 'input_digest', 'inputDigest'),
    outputDigest: pickStr(raw, 'output_digest', 'outputDigest'),
    errorMsg: pickStr(raw, 'error_msg', 'errorMsg'),
    attempts: pickNum(raw, 'attempts'),
    startedAt: pickStr(raw, 'started_at', 'startedAt'),
    finishedAt: pickStr(raw, 'finished_at', 'finishedAt'),
  };
}

export function normalizePipelineArtifact(rawInput: unknown): AiPipelineArtifact {
  const raw = asRecord(rawInput);
  return {
    artifactType: (pickStr(raw, 'artifact_type', 'artifactType') ?? '').toLowerCase(),
    artifactRev: pickNum(raw, 'artifact_rev', 'artifactRev'),
    // content 的形状由后端定（对象/数组/字符串都可能）：原样保留，渲染层做通用展示
    content: raw.content ?? raw.content_json ?? raw.contentJson ?? null,
    objectKey: pickStr(raw, 'object_key', 'objectKey'),
    charCount: pickNum(raw, 'char_count', 'charCount'),
    sha1: pickStr(raw, 'sha1'),
    itemCount: pickNum(raw, 'item_count', 'itemCount'),
    createdAt: pickStr(raw, 'created_at', 'createdAt', 'create_time', 'createTime'),
  };
}

export function normalizePipelineEvent(rawInput: unknown): AiPipelineEvent {
  const raw = asRecord(rawInput);
  return {
    stageKey: pickStr(raw, 'stage_key', 'stageKey'),
    fromStatus: pickStr(raw, 'from_status', 'fromStatus'),
    toStatus: pickStr(raw, 'to_status', 'toStatus'),
    reason: pickStr(raw, 'reason'),
    operator: pickStr(raw, 'operator'),
    createdAt: pickStr(raw, 'created_at', 'createdAt', 'create_time', 'createTime'),
  };
}

/**
 * 详情归一化。
 *
 * 兼容两种后端形态：任务字段**平铺在顶层**，或**包在 `task` / `run` 子对象里**
 * （§4.4.3 的投影兜底实现容易写成后者）；顶层字段优先。
 */
export function normalizePipelineRunDetail(rawInput: unknown): AiPipelineRunDetail {
  const raw = asRecord(rawInput);
  const task = { ...asRecord(raw.task), ...asRecord(raw.run), ...raw };
  return {
    ...normalizePipelineRun(task),
    stages: asArray(raw.stages)
      .map(normalizePipelineStage)
      .filter((s) => !!s.stageKey),
    artifacts: asArray(raw.artifacts)
      .map(normalizePipelineArtifact)
      .filter((a) => !!a.artifactType),
    events: asArray(raw.events).map(normalizePipelineEvent),
    projectionNote: pickStr(raw, 'projection_note', 'projectionNote'),
  };
}
