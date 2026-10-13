/**
 * 「AI 与知识库」接口封装
 * ----------------------------------------------------------------
 * 全部走 `@/api/http` 的 api.get/post/del（自动带 token、自动解包 `{code,message,data}` 的 data、
 * 错误已统一 toast），**不新建 axios 实例、不手写 fetch**——与主系统其余模块保持同一套请求语义。
 *
 * 路径前缀 `/api/ai`：前端只与主系统后端对话，AI 服务（8100）仅内网可达、由主系统后端代理调用
 * （见《AI前端与集成方案》§4.3）。因此这里的方法名对应 §9 契约编号 #1~#9。
 */
import { api } from '@/api/http';
import {
  AiAttachmentStatus,
  AiChatAnswer,
  AiChatRequest,
  AiDeleteResult,
  AiDocument,
  AiHealth,
  AiParseResult,
  AiPipelineRun,
  AiPipelineRunDetail,
  AiRetryResult,
  AiTask,
  normalizePipelineRun,
  normalizePipelineRunDetail,
} from '@/types/ai';
import { PageResult } from '@/types';

/** #2 文档库查询：keyword/projectId 可选，分页从 1 开始 */
export interface AiDocumentQuery {
  keyword?: string;
  projectId?: number | null;
  page?: number;
  size?: number;
}

/** #4 任务队列查询：status/projectId 可选，分页从 1 开始 */
export interface AiTaskQuery {
  status?: string;
  projectId?: number | null;
  page?: number;
  size?: number;
}

/**
 * 解析流水线（任务流）查询：`GET /api/ai/pipeline/runs`。
 *
 * 注意这是**用户 JWT** 的接口（与 `/ai/tasks` 同一鉴权族），不是 AI 服务的 `scope_token`；
 * 路径前缀 `/api/ai/pipeline`，字段 snake_case（后端给 camelCase 时由 `types/ai.ts` 的归一化兜住）。
 */
export interface AiPipelineRunQuery {
  /** 只看某个附件的流水线记录（溯源抽屉"查看该附件的任务流"用） */
  attachmentId?: number | null;
  /** 单值状态过滤（契约只允许一个值；"进行中/历史"两段由前端按状态分组） */
  status?: string;
  page?: number;
  size?: number;
}

/**
 * #8 附件状态一次最多查 200 个（契约上限）。
 * 这里按 200 切片是为了让调用方（如附件中心整页几百个附件）不必自己关心上限，
 * 也不必为了绕过上限而改契约。
 */
export const AI_STATUS_BATCH_SIZE = 200;

export function chunkIds(ids: number[], size = AI_STATUS_BATCH_SIZE): number[][] {
  const out: number[][] = [];
  for (let i = 0; i < ids.length; i += size) {
    out.push(ids.slice(i, i + size));
  }
  return out;
}

/**
 * 深度自检的超时（毫秒）。
 *
 * 全局 axios 超时是 30s，而深度自检会真的去 ping 平台的对话模型与向量网关（实测几秒到十几秒，
 * 平台侧慢时更久）。用一个明显更宽的值兜住，避免"其实在探、只是被前端掐断"被误报成服务不可用。
 */
export const AI_DEEP_HEALTH_TIMEOUT_MS = 120000;

/**
 * 一次问答的超时（毫秒）。
 *
 * 全局 axios 超时是 30s，对 `/api/ai/chat` **明显不够**：一次问答可能包含多轮工具调用
 * （`search_documents` → `read_page` → `calculate`，P2 起还可能回调主系统的
 * `query_business_data`），实测 10s 起、复杂问题更久；用全局 30s 会在中途把请求掐断，
 * 表现为"AI 服务调用失败"，而其实后端还在正常算。
 * 这里放宽到 3 分钟，并把 timeout 只加在本接口上（`api.post` 的可选参数），
 * 不改变其它接口沿用全局 30s 的行为。
 */
export const AI_CHAT_TIMEOUT_MS = 180000;

export const aiApi = {
  /**
   * #1 服务自检：available=false 时 data.message 给中文原因。
   *
   * @param deep `false`（默认）只探 OCR 的快速探活；`true` 额外探对话 / 向量化 / 重排模型
   *             （真发请求，慢）。`deep` 是本次新增的**可选**查询参数，
   *             不带它时后端行为与以前完全一致。
   */
  health(deep = false): Promise<AiHealth> {
    return api.get<AiHealth>(
      '/ai/health',
      { deep },
      deep ? { timeoutMs: AI_DEEP_HEALTH_TIMEOUT_MS } : undefined,
    );
  },

  /** #2 文档库分页（后端按可访问项目过滤） */
  documents(params: AiDocumentQuery): Promise<PageResult<AiDocument>> {
    return api.get<PageResult<AiDocument>>('/ai/documents', params);
  },

  /** #3 删除某个已入库文档（ADMIN/MANAGER） */
  deleteDocument(docId: string): Promise<AiDeleteResult> {
    return api.del<AiDeleteResult>(`/ai/documents/${encodeURIComponent(docId)}`);
  },

  /** #4 任务队列分页 */
  tasks(params: AiTaskQuery): Promise<PageResult<AiTask>> {
    return api.get<PageResult<AiTask>>('/ai/tasks', params);
  },

  /** #5 单个任务（轮询用） */
  getTask(taskId: number): Promise<AiTask> {
    return api.get<AiTask>(`/ai/tasks/${taskId}`);
  },

  /** #6 失败任务重试（ADMIN/MANAGER） */
  retryTask(taskId: number): Promise<AiRetryResult> {
    return api.post<AiRetryResult>(`/ai/tasks/${taskId}/retry`);
  },

  /**
   * #7 触发某个附件的解析（ADMIN/MANAGER）。
   *
   * 用 api.post 而不是 api.upload：本接口没有文件体，只是让后端按 attachmentId 去取原件（§9 #7 入参为空），
   * 走 multipart 反而会把请求体变成 FormData，与契约不符。
   */
  parseAttachment(attachmentId: number): Promise<AiParseResult> {
    return api.post<AiParseResult>(`/ai/attachments/${attachmentId}/parse`);
  },

  /** #8 批量查附件索引状态（内部按 200 分批，结果合并） */
  async attachmentsStatus(attachmentIds: number[]): Promise<AiAttachmentStatus[]> {
    const ids = attachmentIds.filter((v) => Number.isFinite(v));
    if (ids.length === 0) return [];
    const batches = chunkIds(ids);
    const results = await Promise.all(
      batches.map((batch) =>
        api.get<AiAttachmentStatus[]>('/ai/attachments/status', { attachmentIds: batch.join(',') }),
      ),
    );
    return results.flat();
  },

  /**
   * #9 提问。
   *
   * AI 服务不可用时后端返回业务错误（code≠0，message 含"AI 服务不可用"），
   * http 拦截器已统一 toast 并 reject —— 调用方据此进入"错误态"，
   * **绝不能把它显示成"未找到"**（"未找到"只能是后端正常回答里 notice/答案的语义）。
   *
   * 超时：显式放宽到 {@link AI_CHAT_TIMEOUT_MS}（3 分钟）。一次问答可能包含多轮工具调用，
   * 实测 10s 起、复杂问题更久；全局 30s 会中途掐断。
   */
  chat(body: AiChatRequest): Promise<AiChatAnswer> {
    return api.post<AiChatAnswer>('/ai/chat', body, { timeoutMs: AI_CHAT_TIMEOUT_MS });
  },

  /**
   * 解析流水线列表（任务流视图的**表头**数据）。
   *
   * 列表接口**不带**阶段明细与产物 content（§4.9.2⑥"详情必须重新取全量"），
   * 所以这里只归一化表头字段；打开抽屉时一定再打一次详情接口。
   *
   * 兼容两种响应形状：分页对象 `{total, records}` 与裸数组
   * （后端未分页时也给得出结果，不至于整页空白）。
   */
  async pipelineRuns(params: AiPipelineRunQuery): Promise<{ total: number; records: AiPipelineRun[] }> {
    const res = await api.get<PageResult<Record<string, unknown>> | Record<string, unknown>[]>(
      '/ai/pipeline/runs',
      {
        attachment_id: params.attachmentId ?? undefined,
        status: params.status || undefined,
        page: params.page,
        size: params.size,
      },
    );
    const rawRecords = Array.isArray(res) ? res : res?.records ?? [];
    const total = Array.isArray(res) ? rawRecords.length : Number(res?.total ?? rawRecords.length);
    return {
      total: Number.isFinite(total) ? total : rawRecords.length,
      // run_id 缺失的行打不开详情（抽屉按 run_id 取数），留着只会是坏行：直接过滤
      records: rawRecords.map(normalizePipelineRun).filter((r) => !!r.runId),
    };
  },

  /** 流水线详情：任务字段 + `stages[]` + `artifacts[]` + `events[]`（打开抽屉与 1.5s 轮询都用它） */
  async pipelineRunDetail(runId: string): Promise<AiPipelineRunDetail> {
    const raw = await api.get<Record<string, unknown>>(`/ai/pipeline/runs/${encodeURIComponent(runId)}`);
    return normalizePipelineRunDetail(raw);
  },

  /*
   * ==================== 待接：流水线级动作端点（**后端尚未提供，故意不调用**） ====================
   *
   * 任务书要求"若主系统提供重试/取消端点则接上，未提供就只读展示并在代码注释里标注待接"。
   * 本轮冻结契约只有两个 GET（列表 / 详情），因此：
   *   * 阶段卡片与列表**只读展示**（不显示点不动的按钮——放个不能用的按钮比没有更糟）；
   *   * 端点落地后在这里补两个方法，并把 PipelineFlowDrawer 底部的"待接"提示换成按钮：
   *       retryPipelineRun(runId, onlyFailedStage?)  → POST /ai/pipeline/runs/{run_id}/retry
   *       cancelPipelineRun(runId)                   → POST /ai/pipeline/runs/{run_id}/cancel
   * 取消语义必须沿用现状口径：**已在跑的项会跑完，未开始的会跳过**（§4.9.2⑤）。
   */
};

/* ==================== 引用 → 附件（跳转预览用） ==================== */

/**
 * 文档 id → 附件 id 的缓存（**兜底用**）。
 *
 * §9 #9 的 `citations` 带 `attachmentId`，且**由主系统后端回填**（见 `AiCitationVO` 的注释：
 * 映射该在后端做，不推给前端），所以这条路径正常情况下不会走到。保留它的价值在于：
 *  - 后端未回填（旧数据/适配层未完成）时，前端仍能把引用点开，而不是显示成"附件已删除"；
 *  - 同一文档在整轮会话里只查一次（缓存），且在文档被删除后能明确给出"查不到"，而不是打开坏链接。
 */
const docCache = new Map<string, AiDocument | null>();
let docListPromise: Promise<AiDocument[]> | null = null;

/** 关键词搜索命中多条时，只有文件名完全一致才算数，避免把引用指到同名/近名的另一份文档 */
function pickDocument(list: AiDocument[], docId: string, filename?: string | null): AiDocument | null {
  const byId = list.find((d) => d.docId === docId);
  if (byId) return byId;
  if (filename) {
    const same = list.filter((d) => d.filename === filename);
    if (same.length === 1) return same[0];
  }
  return null;
}

function loadAllDocuments(): Promise<AiDocument[]> {
  if (!docListPromise) {
    docListPromise = aiApi
      .documents({ page: 1, size: 500 })
      .then((res) => res.records || [])
      .catch(() => {
        // 失败后清掉，下一次点击还能重试（否则一次抖动会让整轮会话的引用都点不动）
        docListPromise = null;
        return [] as AiDocument[];
      });
  }
  return docListPromise;
}

/**
 * 解析某条引用对应的主系统附件。
 * @returns 附件 id（解析不到返回 null —— 调用方据此把该条引用显示成"附件已删除"且不可点击）
 */
export async function resolveCitationAttachment(
  docId?: string | null,
  filename?: string | null,
): Promise<number | null> {
  const key = docId || filename || '';
  if (!key) return null;
  if (docCache.has(key)) return docCache.get(key)?.attachmentId ?? null;

  if (docId) {
    try {
      const res = await aiApi.documents({ page: 1, size: 20, keyword: docId });
      const hit = pickDocument(res.records || [], docId, filename);
      if (hit) {
        docCache.set(key, hit);
        return hit.attachmentId ?? null;
      }
    } catch {
      /* 落到整表缓存再试一次 */
    }
  }

  const all = await loadAllDocuments();
  const hit = pickDocument(all, docId || '', filename);
  docCache.set(key, hit);
  return hit?.attachmentId ?? null;
}

/** 清掉映射缓存（文档库发生删除/重解析后调用，避免继续跳到已失效的附件） */
export function clearDocAttachmentCache(): void {
  docCache.clear();
  docListPromise = null;
}
