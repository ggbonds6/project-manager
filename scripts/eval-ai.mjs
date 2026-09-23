#!/usr/bin/env node
/**
 * ============================================================
 * AI 问答冒烟评测集 runner（契约：docs/AI前端与集成方案.md §11.7 第 6 条）
 *
 * 用法（仓库根，Node 18+，**不新增任何 npm 依赖**）：
 *   node scripts/eval-ai.mjs --set eval/smoke-30.json --dry
 *   node scripts/eval-ai.mjs --set eval/smoke-30.json --list-truth --token <JWT>
 *   node scripts/eval-ai.mjs --set eval/smoke-30.json --base http://127.0.0.1:8080 --token <JWT>
 *   set PM_TOKEN=<JWT> && node scripts/eval-ai.mjs --set eval/smoke-30.json
 *
 * 三种模式：
 *   --dry（默认档，无服务也能跑）  只做**数据集体检**：必填字段、类别合法、id 唯一、
 *                                 citation_pages 为 null 的统计、结构化题是否带 truth。
 *                                 **不发任何请求。**
 *   --list-truth                    只连**主系统页面接口**（--base + --token）把每道题的
 *                                 真值实际算出来并打印；**不需要 AI 服务、不发 /api/ai/chat**。
 *                                 这是"评测集真值 == 用户页面所见"的自证。
 *   非 dry                          逐题 POST {base}/api/ai/chat，按 expected 判定，
 *                                 输出汇总表 + eval/report-<时间戳>.json。
 *
 * 退出码：0 = 全通过或全跳过（含 --dry 体检通过 / --list-truth 全部解析成功）；
 *         1 = 有失败（含体检不合格、真值解析失败）；2 = 用法/配置错误。
 *
 * 设计原则（为什么这么写）：
 *   1) **真值优先取"用户看得见的接口"**（§11.7 要回答的是"模型说的数字与页面上一致吗"）。
 *      `expected.truth` 用页面 API（`/api/projects/{id}`、`/api/attachments`、
 *      `/api/projects/{id}/contracts`…）现场算出真值 —— 这些接口带登录 JWT 就能调。
 *      §11.2 的受控查询接口只认短时效 scope_token（AI 服务专用），故仅作**可选交叉校验**：
 *      给了 `--scope-token`/`PM_SCOPE_TOKEN` 才走它，并额外报"两条路径口径一致率"，
 *      **绝不为跑通去伪造 scope 令牌**。
 *   2) **数字宽松比较**：忽略千分位、空格、全角逗号、单位后缀；但对 1/2/3 这类
 *      个位数字做**边界匹配**，避免 "31" 里的 "3"、"2026" 里的 "2" 被误判为命中。
 *      must_contain_any 可给出多个等价表述（如 ["2100000"]、["210","万"]）。
 *   3) **页码只在真标了页码时判定**：citation_pages=null → SKIP(unlabeled)，
 *      口径是"没标就不判"，绝不把"没标页码"当成"命中"。
 *   4) 跳过原因分三类，不混：SKIP(no-endpoint) 接口不可达 / SKIP(unlabeled) 页码没标 /
 *      SKIP(no-truth) 该题没有任何可用真值来源。
 *   5) 每题的 note 写明真值来源；报告里原样带出，回归对比时不用回去翻库。
 * ============================================================
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..');

const CATEGORIES = ['structured', 'document', 'mixed', 'negative', 'permission'];

/**
 * runner 已实现的真值种类（`expected.truth.kind`）。
 * 每一种都对应用户**页面上看得见**的接口与字段，映射关系见 resolveTruth() 的注释
 * 与 eval/README.md「真值来源与局限」一节。
 */
const TRUTH_KINDS = [
  'phase_attachment_count',       // 某阶段附件数        GET /api/attachments?bizType=PROJECT_PHASE&bizId={phaseId}
  'phase_attachment_count_all',   // 全部阶段附件数(数组) 同上，逐阶段
  'phase_attachment_sum',         // 全部阶段附件数合计   同上求和
  'ai_ready_count',               // 已入库(可问答)附件数  GET /api/ai/attachments/status
  'contract_total',               // 合同份数 + 合同总额   GET /api/projects/{id}/contracts
  'contract_amount_total',        // 仅合同总额（不含份数）  同上；用于"只问总额"的题，避免拿份数当扣分项
  'budget_amount',                // 概算/预算金额        GET /api/projects/{id}.budgetAmount
  'contract_amount_main',         // 主合同口径金额       GET /api/projects/{id}.contractTotal
  'child_count',                  // 子项目数            GET /api/projects/{id}.childCount
  'payment_records',              // 付款记录数+已付合计   GET /api/projects/{id}/payments
  'project_type_status',          // 类型 + 状态          GET /api/projects/{id}.type/.status
  'project_name_code',            // 名称 + 编号          GET /api/projects/{id}.name/.code
  'bid_amount',                   // 中标金额            GET /api/projects/{id}.bidAmount
];

/** 如实拒答的判定词（命中任一即算"如实说没有"）。
 *  「没有」是裸词，必须有：实测中「该项目没有子项目」「没有付款记录」这类正确拒答
 *  不会带「没有找到」，只收录长词组会把正确答案判成 FAIL。 */
const REFUSAL_WORDS = [
  '没有', '未找到', '找不到', '不存在', '未登记', '无相关', '无此', '暂无', '无数据',
  '不包含', '未提及', '未包含', '未提供', '无法确认', '无法给出', '无法计算', '无法提供',
  'no data', 'not found', 'no such', 'cannot find',
];

/** 受控查询工具的别名：trace 里出现任何一个都算"用了业务数据查询"。 */
const QUERY_TOOL_ALIASES = [
  'query_business_data', 'querybusinessdata', 'query_business',
  'stats', 'projects', 'contracts', 'payments', 'business_data', 'pm_query',
];

// ─────────────────────────── CLI ───────────────────────────

function usage() {
  return [
    '用法：node scripts/eval-ai.mjs --set eval/smoke-30.json [选项]',
    '',
    '选项：',
    '  --set <path>         评测集 JSON 路径（默认 eval/smoke-30.json）',
    '  --dry                只做数据集体检，不发任何请求（无服务时用这个）',
    '  --list-truth         只连主系统页面接口算并打印每题真值（不需要 AI 服务）',
    '  --write-truth        同 --list-truth，并把真值快照写回 eval/truth-snapshot.json',
    '  --base <url>         主系统基址（默认 http://127.0.0.1:8080）',
    '  --token <jwt>        登录 JWT（缺省读环境变量 PM_TOKEN）',
    '  --scope-token <jwt>  §11.2 的 scope_token（缺省读 PM_SCOPE_TOKEN）：给了就多跑一条',
    '                       §11 受控查询交叉校验，额外报"两条路径口径一致率"；不给就只走页面 API',
    '  --timeout <ms>       单题请求超时（默认 120000）',
    '  --out <path>         报告输出路径（默认 eval/report-<时间戳>.json）',
    '  --max <n>            只跑前 n 题（联调时缩短耗时）',
    '  -h, --help           打印本说明',
    '',
    'token 从哪来：登录主系统后浏览器 DevTools → Application/Local Storage 取 token；',
    '或 POST {base}/api/auth/login {"account":"...","password":"..."} 取 data.token。',
    'scope_token 只有主系统在调用 AI 服务时签发，**不要伪造**：拿不到就不做交叉校验。',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    set: 'eval/smoke-30.json',
    dry: false,
    listTruth: false,
    writeTruth: false,
    base: 'http://127.0.0.1:8080',
    token: '',
    scopeToken: '',
    timeout: 120000,
    out: '',
    max: 0,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--dry') out.dry = true;
    else if (a === '--list-truth') out.listTruth = true;
    else if (a === '--write-truth') { out.listTruth = true; out.writeTruth = true; }
    else if (a === '-h' || a === '--help') out.help = true;
    else if (a.startsWith('--')) {
      const key = a.slice(2);
      const val = argv[i + 1];
      if (val === undefined || val.startsWith('--')) throw new Error(`参数 ${a} 缺少取值`);
      i += 1;
      if (key === 'set') out.set = val;
      else if (key === 'base') out.base = val;
      else if (key === 'token') out.token = val;
      else if (key === 'scope-token') out.scopeToken = val;
      else if (key === 'out') out.out = val;
      else if (key === 'timeout') out.timeout = Number(val);
      else if (key === 'max') out.max = Number(val);
      else throw new Error(`未知参数：${a}`);
    } else throw new Error(`未知参数：${a}`);
  }
  return out;
}

// ─────────────────── 数据集（体检 + 取用） ───────────────────

function loadDataset(path) {
  if (!existsSync(path)) throw new Error(`找不到评测集文件：${path}`);
  let raw;
  try {
    raw = readFileSync(path, 'utf8');
  } catch (e) {
    throw new Error(`读取评测集失败：${e.message}`);
  }
  try {
    return { json: JSON.parse(raw), path };
  } catch (e) {
    throw new Error(`评测集不是合法 JSON：${e.message}`);
  }
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}
function isNonEmptyString(v) {
  return typeof v === 'string' && v.trim() !== '';
}

/**
 * 数据集体检。返回 { problems, warnings, stats }。
 * problems 非空 → 退出码 1（这是"质量门"，不是提示）。
 */
function validateDataset(json) {
  const problems = [];
  const warnings = [];
  const stats = {
    total: 0,
    byCategory: {},
    citationPagesLabeled: 0,
    citationPagesNull: 0,
    structuredWithMetric: 0,
    structuredWithoutMetric: 0,
    withMustContain: 0,
    withMustContainAny: 0,
    withMustCallTool: 0,
    refusalCases: 0,
    permissionCases: 0,
    weakCases: 0,
    truthKinds: {},
  };

  if (!isPlainObject(json)) {
    problems.push('顶层不是对象');
    return { problems, warnings, stats };
  }
  if (!isNonEmptyString(json.version)) problems.push('缺少 version（字符串）');
  if (!isNonEmptyString(json.generatedAt)) problems.push('缺少 generatedAt（ISO 时间字符串）');
  else if (Number.isNaN(Date.parse(json.generatedAt))) {
    problems.push(`generatedAt 不是可解析的时间：${json.generatedAt}`);
  }
  if (!isNonEmptyString(json.scopeNote)) problems.push('缺少 scopeNote');
  if (!Array.isArray(json.cases)) {
    problems.push('cases 不是数组');
    return { problems, warnings, stats };
  }

  const n = json.cases.length;
  stats.total = n;
  if (n < 25 || n > 30) {
    warnings.push(`题量 ${n} 条，任务建议 25~30 条（超出只是提醒：多出来的都是同源真值的补强题）`);
  }

  const seenIds = new Map();
  json.cases.forEach((c, i) => {
    const where = `cases[${i}]${c && c.id ? ` (${c.id})` : ''}`;
    if (!isPlainObject(c)) {
      problems.push(`${where}: 不是对象`);
      return;
    }
    // id
    if (!isNonEmptyString(c.id)) problems.push(`${where}: 缺少 id`);
    else {
      if (seenIds.has(c.id)) problems.push(`id 重复：${c.id}（首次出现在 cases[${seenIds.get(c.id)}]）`);
      else seenIds.set(c.id, i);
      if (!/^[SDMNP]-\d{3}-\d{3}$/.test(c.id)) {
        warnings.push(`${where}: id 不符合建议格式 <类别首字母>-NNN-NNN（如 S-012-001）`);
      }
    }
    // category
    if (!isNonEmptyString(c.category)) problems.push(`${where}: 缺少 category`);
    else if (!CATEGORIES.includes(c.category)) {
      problems.push(`${where}: category 非法（${c.category}），合法值 ${CATEGORIES.join('/')}`);
    } else {
      stats.byCategory[c.category] = (stats.byCategory[c.category] || 0) + 1;
    }
    // question
    if (!isNonEmptyString(c.question)) problems.push(`${where}: 缺少 question`);

    // scope
    if (!isPlainObject(c.scope)) problems.push(`${where}: 缺少 scope 对象`);
    else {
      if (!Number.isInteger(c.scope.projectId)) problems.push(`${where}: scope.projectId 必须是整数`);
      if (!Array.isArray(c.scope.attachmentIds)) problems.push(`${where}: scope.attachmentIds 必须是数组`);
      else if (c.scope.attachmentIds.some((x) => !Number.isInteger(x))) {
        problems.push(`${where}: scope.attachmentIds 只允许整数`);
      }
    }

    // expected
    const exp = c.expected;
    if (!isPlainObject(exp)) {
      problems.push(`${where}: 缺少 expected 对象`);
      return;
    }
    const mustContain = exp.must_contain;
    if (!Array.isArray(mustContain)) problems.push(`${where}: expected.must_contain 必须是数组`);
    else {
      if (mustContain.some((x) => !isNonEmptyString(x))) {
        problems.push(`${where}: must_contain 只允许非空字符串（数字也写成字符串）`);
      }
      if (mustContain.length > 0) stats.withMustContain += 1;
    }
    if (exp.must_contain_any !== undefined) {
      if (!Array.isArray(exp.must_contain_any)) problems.push(`${where}: must_contain_any 必须是数组`);
      else {
        const bad = exp.must_contain_any.find((g) => !Array.isArray(g) || g.length === 0
          || g.some((x) => !isNonEmptyString(x)));
        if (bad !== undefined) problems.push(`${where}: must_contain_any 必须是「非空字符串数组」的数组`);
        else stats.withMustContainAny += 1;
      }
    }
    if (exp.must_call_tool !== undefined && !isNonEmptyString(exp.must_call_tool)) {
      problems.push(`${where}: must_call_tool 必须是非空字符串`);
    } else if (isNonEmptyString(exp.must_call_tool)) stats.withMustCallTool += 1;

    // truth：结构化/混合题的真值描述（指向页面 API；见文件头设计原则 1）
    if (exp.truth !== undefined && exp.truth !== null) {
      if (!isPlainObject(exp.truth)) problems.push(`${where}: expected.truth 必须是对象或 null`);
      else {
        if (!isNonEmptyString(exp.truth.kind)) problems.push(`${where}: truth.kind 缺失`);
        else {
          stats.truthKinds[exp.truth.kind] = (stats.truthKinds[exp.truth.kind] || 0) + 1;
          if (!TRUTH_KINDS.includes(exp.truth.kind)) {
            warnings.push(`${where}: truth.kind=${exp.truth.kind} 不在 runner 已实现的真值种类内`
              + `（${TRUTH_KINDS.join('/')}）——该题会记 SKIP(no-truth)`);
          }
        }
        if (!Number.isInteger(exp.truth.projectId)) problems.push(`${where}: truth.projectId 必须是整数`);
        if (!isNonEmptyString(exp.truth.via)) {
          warnings.push(`${where}: truth.via 未写（建议 page-apis / page-apis+controlled-query）`);
        }
      }
    }
    if (c.category === 'structured' && !isPlainObject(exp.truth)) {
      stats.structuredWithoutMetric += 1;
      problems.push(`${where}: structured 题必须带 expected.truth（否则无法与页面/接口逐位比对）`);
    }
    if (c.category === 'structured') {
      if (isPlainObject(exp.truth)) stats.structuredWithMetric += 1;
      if (mustContain.length === 0 && !Array.isArray(exp.must_contain_any) && !isPlainObject(exp.truth)) {
        problems.push(`${where}: structured 题必须至少给 must_contain / must_contain_any / truth 之一`);
      }
    }

    // citation_pages
    if (!Object.prototype.hasOwnProperty.call(exp, 'citation_pages')) {
      problems.push(`${where}: 缺少 expected.citation_pages（没有页码就显式写 null）`);
    } else if (exp.citation_pages === null) {
      stats.citationPagesNull += 1;
    } else if (!Array.isArray(exp.citation_pages) || exp.citation_pages.some((x) => !Number.isInteger(x) || x < 1)) {
      problems.push(`${where}: citation_pages 必须是正整数数组或 null`);
    } else {
      stats.citationPagesLabeled += 1;
      if (exp.citation_pages.length === 0) warnings.push(`${where}: citation_pages 是空数组，请确认是想表达"无页码"（应写 null）`);
    }

    // 各类型的专用字段
    if (c.category === 'negative') {
      stats.refusalCases += 1;
      if (exp.expect_refusal !== true) problems.push(`${where}: negative 题必须 expect_refusal=true`);
      if (mustContain.length > 0) {
        problems.push(`${where}: negative 题不应有 must_contain（正解是"没有"，不应要求命中任何内容）`);
      }
    }
    if (c.category === 'permission') {
      stats.permissionCases += 1;
      if (exp.expect_error_or_empty !== true) {
        problems.push(`${where}: permission 题必须 expect_error_or_empty=true`);
      }
      if (isPlainObject(exp.truth)) {
        warnings.push(`${where}: permission 题带 truth —— 越权时接口应根本查不到，真值一致性无意义`);
      }
    }
    if (c.category === 'document' && isNonEmptyString(exp.must_call_tool)
        && exp.must_call_tool !== 'search_documents' && exp.must_call_tool !== 'read_page') {
      warnings.push(`${where}: document 题的 must_call_tool=${exp.must_call_tool} 不是文档检索工具`);
    }

    // note 是"真值来源"的落点，必须有
    if (!isNonEmptyString(c.note)) problems.push(`${where}: 缺少 note（须写明口径/真值来源）`);
    else if (!/真值来源|口径|来源|SQL|SELECT|实测|查询/.test(c.note)) {
      warnings.push(`${where}: note 里没有出现"真值来源/口径/SQL"等字样，请确认写清了出处`);
    }
    if (exp.expect_weak === true) stats.weakCases += 1;
  });

  if (stats.citationPagesNull === stats.total && stats.total > 0) {
    warnings.push('全部题目的 citation_pages 都是 null —— 若已有服务可核对页码，请人工补上，否则引用命中率恒为"未标注"');
  }

  // 与真值快照做一致性体检（有快照才做）：**每个真值都必须能在该题的必须命中里找到**。
  // 方向是"真值 → must_contain"而不是反过来：只报"真值没被要求命中"这一种危险情况
  // （数据集拿着旧数字判新库 → 假失败），不报"多出来的数字"（那通常是 万/千分位 等值表述
  // 或派生值，属正常）。派生值题用 truth.alsoFrom 显式声明它引用了哪些题的真值。
  const snap = loadTruthSnapshot();
  if (snap && Array.isArray(snap.cases)) {
    const byId = new Map(snap.cases.map((e) => [e.id, e]));
    const norm = (s) => String(s).replace(/[，,](?=\d{3}(\D|$))/g, '');
    for (const c of json.cases) {
      const t = isPlainObject(c.expected?.truth) ? c.expected.truth : null;
      if (!t || !byId.has(c.id)) continue;
      // 派生值题（truth.derived=true）的真值是算出来的（差额/比例），来源值本来就不该出现在答案里，
      // 这条门对它没有意义 —— 跳过，改由 truth.sources 人工可读地记录来源。
      if (t.derived === true) continue;
      const declared = [
        ...(Array.isArray(c.expected.must_contain) ? c.expected.must_contain : []),
        ...((Array.isArray(c.expected.must_contain_any) ? c.expected.must_contain_any : []).flat()),
      ].map(norm);
      const sources = [c.id, ...(Array.isArray(t.sources) ? t.sources : [])];
      const missing = [];
      for (const sid of sources) {
        for (const v of (byId.get(sid)?.values || [])) {
          if (!declared.includes(norm(v))) missing.push(`${v}（来自 ${sid}）`);
        }
      }
      if (missing.length > 0) {
        warnings.push(`${c.id}: 真值 ${missing.join('、')} 没有出现在 must_contain/must_contain_any 里`
          + '——数据集可能没跟上数据变化（先跑 --write-truth），或该真值被派生使用（用 truth.alsoFrom 声明来源）');
      }
    }
  }
  return { problems, warnings, stats };
}

// ─────────────────────── 比较与判定 ───────────────────────

const THIN_SPACES = /[\s\u00a0\u2000-\u200b\u3000]/g;
/** 千分位（英文逗号或全角逗号 + 3 位数字）——用来把 "1,855,560" 归一成 "1855560" 再比。 */
const THOUSANDS_SEPARATORS = /[，,](?=\d{3}(\D|$))/g;
/**
 * 单位后缀**只紧跟在数字后面时才去掉**。
 * 早期版本用全局替换（/(元|个|条|份|项…)/g），把「没有子项目」打成了「没有子目」，
 * 直接导致如实拒答判定失效 —— 这是实测踩到的坑，别再改回全局替换。
 */
const UNIT_AFTER_NUMBER = /(\d)[元个条份项次户人页天%％]/g;

/** 数字/文本宽松归一：去空格（含全角）、去千分位逗号、去"数字后"的单位后缀。 */
function normalizeAnswer(text) {
  return String(text ?? '')
    .replace(THIN_SPACES, '')
    .replace(/[，,](?=\d{3}(\D|$))/g, '')
    .replace(UNIT_AFTER_NUMBER, '$1');
}

/**
 * 数字边界匹配：命中处左右不能是字母/数字，且不能是未归一化千分位的中间段。
 *
 * 允许的边界（**实测踩坑后放宽**）：列举分隔符 `、` `，` `,` 也算合法边界——
 * 否则模型答「9、2、3、1、3…」这种最自然的中文列举会被判成"没命中 2/3/1"。
 * 例如："9、2、3" 里匹配 "2"：左邻 `、` 合法，右邻 `、` 合法 → 命中。
 */
function hasBoundedNumber(hay, num) {
  let from = 0;
  for (;;) {
    const idx = hay.indexOf(num, from);
    if (idx < 0) return false;
    from = idx + 1;
    const before = idx === 0 ? '' : hay[idx - 1];
    const after = idx + num.length >= hay.length ? '' : hay[idx + num.length];
    if (/[A-Za-z0-9]/.test(before)) continue;              // 前面贴着字母/数字 → 是更大 token 的一段
    if (/[0-9]/.test(after)) continue;                     // 后面还有数字 → 是更长数字的一部分
    if (after === ',') {
      // 逗号后跟数字 → 这是 "1,848,000" 的第 1 段，不是独立数字（列举分隔符后跟中文/文字则无妨）
      const seg = hay.slice(idx + num.length + 1, idx + num.length + 4);
      if (/^\d/.test(seg)) continue;
    }
    return true;
  }
}

function isPureNumber(s) {
  return /^\d+$/.test(s);
}

/** 单个 token 是否命中（文本子串 或 数字边界）。 */
function tokenHit(hay, raw, note) {
  const tok = String(raw);
  if (isPureNumber(tok)) {
    if (hasBoundedNumber(hay, tok)) return true;
    if (note) note.push(`数字未命中（边界匹配）：${tok}`);
    return false;
  }
  const t = tok.replace(THIN_SPACES, '');
  if (hay.includes(t)) return true;
  if (note) note.push(`文本未命中：${tok}`);
  return false;
}

/** must_contain 组：数组内**每一项都要命中**。 */
function groupHit(hay, group, note) {
  return group.every((raw) => tokenHit(hay, raw, note));
}

function containsRefusal(hay) {
  const lower = hay.toLowerCase();
  return REFUSAL_WORDS.some((w) => hay.includes(w) || lower.includes(w.toLowerCase()));
}

/** 答案里是否出现答案之外的数字（越权泄漏检查用）：只查给定 forbidden 值。 */
function leaksValue(hay, values) {
  return values.some((v) => hasBoundedNumber(hay, String(v)) || hay.includes(String(v)));
}

function extractCitations(payload) {
  const data = payload && payload.data ? payload.data : payload;
  const list = data && Array.isArray(data.citations) ? data.citations : [];
  return list.filter(isPlainObject);
}

function citationText(c) {
  return normalizeAnswer([c.snippet, c.text, c.quote, c.content].filter(isNonEmptyString).join(' '));
}

function citationPages(c) {
  const out = [];
  const candidates = [c.pageNo, c.page_no, c.page, c.pageNum];
  for (const v of candidates) {
    if (Number.isInteger(v) && v > 0) out.push(v);
    else if (typeof v === 'string' && /^\d+$/.test(v.trim())) out.push(Number(v.trim()));
  }
  return out;
}

function traceNames(payload) {
  const data = payload && payload.data ? payload.data : payload;
  const trace = data && Array.isArray(data.toolTrace) ? data.toolTrace : [];
  return trace
    .filter(isPlainObject)
    .map((t) => String(t.name ?? t.tool ?? '').trim())
    .filter(Boolean);
}

function toolCalled(names, wanted) {
  const w = String(wanted).toLowerCase();
  const wantedAliases = w.includes('query_business') || w.includes('stats') || w === 'projects'
    || w === 'contracts' || w === 'payments'
    ? QUERY_TOOL_ALIASES
    : [w];
  return names.some((n) => {
    const lower = n.toLowerCase();
    return wantedAliases.some((a) => lower === a || lower.includes(a));
  });
}

function systemDataEmpty(payload) {
  const data = payload && payload.data ? payload.data : payload;
  if (!data || typeof data !== 'object') return true;
  const sd = data.systemData ?? data.system_data;
  if (Array.isArray(sd)) return sd.length === 0;
  const rows = data.rows;
  if (Array.isArray(rows)) return rows.length === 0;
  return false;
}

/**
 * 单题判定 → { status, reasons[], checks{} }。
 *  @param truth 由 resolveTruth() 解析出的真值（或 null）。null 且题目声明了 truth → SKIP(no-truth)。
 *  @param xcheck 受控查询（§11）交叉校验结果（可选，同结构），仅用于报"两条路径口径一致率"。 */
function judge(oneCase, httpStatus, payload, elapsedMs, truth, xcheck) {
  const exp = oneCase.expected || {};
  const data = payload && isPlainObject(payload) && isPlainObject(payload.data) ? payload.data : (payload || {});
  const answer = typeof data.answer === 'string' ? data.answer
    : typeof payload?.answer === 'string' ? payload.answer : '';
  const hay = normalizeAnswer(answer);
  const names = traceNames(payload);
  const citations = extractCitations(payload);
  const reasons = [];
  const checks = {};
  const skips = [];

  const category = oneCase.category;

  // ── 工具选择 ──
  if (isNonEmptyString(exp.must_call_tool)) {
    const ok = toolCalled(names, exp.must_call_tool);
    checks.toolCalled = ok;
    if (!ok) {
      reasons.push(`未调用 ${exp.must_call_tool}（toolTrace=[${names.join(', ') || '空'}]）`);
    }
  }

  // ── must_contain ──
  const mustContain = Array.isArray(exp.must_contain) ? exp.must_contain : [];
  if (mustContain.length > 0) {
    const missed = mustContain.filter((t) => !tokenHit(hay, t));
    checks.mustContain = { total: mustContain.length, hit: mustContain.length - missed.length, missed };
    if (missed.length > 0) {
      reasons.push(`must_contain 缺 ${missed.length}/${mustContain.length}：${missed.join('、')}`);
    }
  }

  // ── must_contain_any（每组内部"全部命中"算该组满足；任一组满足即通过）──
  if (Array.isArray(exp.must_contain_any) && exp.must_contain_any.length > 0) {
    const groupResults = exp.must_contain_any.map((g) => groupHit(hay, g, null));
    const anyOk = groupResults.some(Boolean);
    // 「0」这类退化取值：必须同时出现如实拒答措辞，否则不算（避免日期里的 0 蒙混）
    const degenerate = exp.must_contain_any.some((g) => g.every((x) => x === '0'));
    const refusalSeen = containsRefusal(hay);
    checks.mustContainAny = { groups: exp.must_contain_any, groupResults, anyOk, refusalSeen };
    if (!anyOk) {
      reasons.push(`must_contain_any 全部等价表述都没命中：${JSON.stringify(exp.must_contain_any)}`);
    } else if (degenerate && !refusalSeen) {
      reasons.push('只命中「0」而没有"没有/不存在"等如实措辞 —— 不认定为正确（防日期里的 0 蒙混）');
      checks.mustContainAny.degenerateNeedsRefusal = true;
      checks.mustContainAny.passed = false;
    }
  }

  // ── 口径转述（§11.4 硬要求；但这里只**记账不判失败**）──
  // 为什么要松：口径写法千变万化（"口径：…" / "按 PROJECT_PHASE 统计" / "数据时间 …"），
  // 一个固定词表判失败会把"答对了但话少"的模型全打成 FAIL，噪音大于信号。
  // §11.7 要的是「口径转述率」这个指标，所以这里只记录，由报告里的比率体现严重程度。
  if (exp.caliber_required === true && answer !== '') {
    const caliberWords = ['口径', '含税', '不含税', '含子项目', '不含子项目', '数据时间', '统计时间', '截至', '数据截止', '范围', '统计'];
    checks.caliber = caliberWords.some((w) => answer.includes(w));
    if (!checks.caliber) checks.caliberNote = '答案里没有口径/数据时间类表述（§11.4 要求模型能原话转述）——计入口径转述率，不单独判失败';
  }

  // ── 引用页码 ──
  if (Array.isArray(exp.citation_pages) && exp.citation_pages.length > 0) {
    if (citations.length === 0) {
      checks.citationPages = { hit: 0, total: exp.citation_pages.length, hitPages: [], missed: exp.citation_pages };
      reasons.push(`期望命中页码 ${exp.citation_pages.join('/')}，但响应里没有任何 citations`);
    } else {
      const found = [];
      for (const p of exp.citation_pages) {
        const inField = citations.some((c) => citationPages(c).includes(p));
        const inText = citations.some((c) => {
          const t = citationText(c);
          return t.includes(`第${p}页`) || t.includes(`p${p}`) || t.includes(`page${p}`);
        });
        if (inField || inText) found.push(p);
      }
      checks.citationPages = { hit: found.length, total: exp.citation_pages.length, hitPages: found };
      if (found.length < exp.citation_pages.length) {
        const missed = exp.citation_pages.filter((p) => !found.includes(p));
        reasons.push(`页码未命中：${missed.join('/')}（引用条数 ${citations.length}）`);
      }
    }
  } else if (exp.citation_pages === null) {
    skips.push('unlabeled');
    checks.citationPages = { skipped: true, reason: 'citation_pages=null（待人工补页码）' };
  }

  // 引用覆盖（只记录条数）
  checks.citationCoverage = { citations: citations.length, expected: Array.isArray(exp.citation_pages) ? exp.citation_pages.length : null };

  // ── 指定了附件范围的题：必须给出**至少一条**可核验引用 ──
  // 为什么这是失败项：契约 §10 的交互底线要求"引用必须可点击跳附件原文"，
  // 指定了附件却一条引用都不给，等于答案无法核验（页码待补不构成免责理由）。
  const scopedAttachments = Array.isArray(oneCase.scope?.attachmentIds) ? oneCase.scope.attachmentIds : [];
  if (scopedAttachments.length > 0 && category !== 'negative' && category !== 'permission') {
    checks.citationRequired = scopedAttachments.length;
    if (citations.length === 0) {
      reasons.push(`指定了 ${scopedAttachments.length} 个附件范围，但响应里没有任何 citations（引用不可核验）`);
    }
  }

  // ── negative：如实拒答 ──
  if (category === 'negative') {
    const refused = exp.expect_refusal === true ? containsRefusal(hay) : true;
    checks.refusal = refused;
    if (!refused) {
      reasons.push(`答案里没有"未找到/没有/不存在"等如实拒答措辞：${answer.slice(0, 80)}${answer.length > 80 ? '…' : ''}`);
    }
  }

  // ── permission：错误 / 无数据 + 不泄漏 ──
  if (category === 'permission') {
    const httpOk = httpStatus === 0 || httpStatus >= 400; // 0 = 请求本身失败/超时，按"未放行"处理但会另标
    const empty = systemDataEmpty(payload);
    const refused = containsRefusal(hay);
    checks.permission = { httpStatus, httpError: httpStatus >= 400, dataEmpty: empty, refused };
    if (!(httpOk || empty || refused)) {
      reasons.push(`越权题未表现为"错误/无数据"：HTTP ${httpStatus}，systemData 非空，答案也未如实拒答`);
    }
    const forbidden = Array.isArray(exp.forbid_values) ? exp.forbid_values : [];
    if (forbidden.length > 0 && leaksValue(hay, forbidden)) {
      const leaked = forbidden.filter((v) => leaksValue(hay, [v]));
      checks.permission.leaked = leaked;
      reasons.push(`越权题答案里泄漏了受保护数值：${leaked.join('/')}`);
    }
  }

  // ── truth：真值一致性（主判据）──
  // 真值由 resolveTruth() 现场调**页面接口**算出（见文件头设计原则 1）。
  if (isPlainObject(exp.truth)) {
    if (truth === null || truth === undefined) {
      skips.push('no-truth');
      checks.truth = { skipped: true, reason: '该题声明的真值来源无法解析（接口不可达/字段缺失/未实现的 kind）', truth: exp.truth };
    } else {
      const values = Array.isArray(truth.values) ? truth.values : [];
      // 真值比对时把"答案里的千分位"也归一（正文归一保留了逗号以配合边界匹配，
      // 而真值形如 1855560、答案常写成 1,855,560）——所以再比一份"去千分位"的正文。
      const hayNoSep = hay.replace(THOUSANDS_SEPARATORS, '');
      const missed = values.filter((v) => {
        const s = String(v);
        if (tokenHit(hay, s)) return false;
        return !hayNoSep.includes(s);
      });
      checks.truth = {
        kind: truth.kind,
        values,
        hit: values.length - missed.length,
        total: values.length,
        missed,
        note: truth.note || null,
        resolvedAt: truth.resolvedAt || null,
      };
      if (missed.length > 0) {
        reasons.push(`真值未命中 ${missed.length}/${values.length}：${missed.join('、')}`
          + `（真值来源：${truth.note || exp.truth.kind}）`);
      }
      // 退化取值（真值就是 0）要加强约束：必须同时出现如实措辞，避免日期里的 0 蒙混
      if (values.length > 0 && values.every((v) => String(v) === '0') && !containsRefusal(hay)) {
        reasons.push('真值是「0/没有」，但答案里没有"没有/不存在/未记录"等如实措辞 —— 不认定为一致');
      }
    }
  }

  // ── 受控查询（§11）交叉校验：只记账，不判失败 ──
  // 为什么只记账：页面 API 才是"用户看得见"的主真值（§11.7 要回答的是那个问题）；
  // 两条路径若不一致，属于主系统自身口径分歧，应由人来裁决，不该让问答题变红。
  if (truth && xcheck) {
    const a = (truth.values || []).map(String).join('|');
    const b = (xcheck.values || []).map(String).join('|');
    checks.crossCheck = { pageValues: truth.values || [], controlledValues: xcheck.values || [], agree: a === b };
  } else if (truth && xcheck === null) {
    checks.crossCheck = { skipped: true, reason: '未提供 --scope-token/PM_SCOPE_TOKEN，跳过受控查询交叉校验' };
  }

  checks.elapsedMs = elapsedMs;

  if (reasons.length > 0) return { status: 'FAIL', reasons, checks, skips: [] };
  const hasNoTruth = skips.includes('no-truth');
  const hasUnlabeled = skips.includes('unlabeled');
  if (hasNoTruth) {
    checks.skipDetail = '除真值比对外，其余判定（must_contain/工具/引用/拒答）都已执行并通过';
    return { status: 'SKIP', skipReason: 'no-truth', reasons: [], checks, skips };
  }
  if (hasUnlabeled) return { status: 'SKIP', skipReason: 'unlabeled', reasons: [], checks, skips };
  return { status: 'PASS', reasons: [], checks, skips };
}

// ─────────────────── 真值解析（页面 API 为主） ───────────────────

function round2(n) {
  return Math.round(n * 100) / 100;
}

/** 分页/数组都能算条数：数组用 length；`{records|rows|list,total}` 优先用 total。 */
function countOf(v) {
  if (Array.isArray(v)) return v.length;
  if (isPlainObject(v)) {
    if (Number.isInteger(v.total)) return v.total;
    for (const k of ['records', 'rows', 'list', 'content', 'items', 'data']) {
      if (Array.isArray(v[k])) return v[k].length;
    }
  }
  return null;
}

function listOf(v) {
  if (Array.isArray(v)) return v;
  if (isPlainObject(v)) {
    for (const k of ['records', 'rows', 'list', 'content', 'items', 'data']) {
      if (Array.isArray(v[k])) return v[k];
    }
  }
  return null;
}

/**
 * 真值解析器：**只读**地调主系统页面接口，算出每题的真值。
 *
 * 接口与字段**照代码抄**（不要猜）：
 *   | 真值 | 接口（前端 api 封装 → 实际路径） | 字段 |
 *   | --- | --- | --- |
 *   | 阶段列表 | `projectApi.detail(id)` → `GET /api/projects/{id}` | `data.phases[].{id,phaseName,sortNo}` |
 *   | 阶段附件数 | `attachmentApi.listByBiz` → `GET /api/attachments?bizType=PROJECT_PHASE&bizId={phaseId}` | 数组长度 |
 *   | 已入库附件 | `GET /api/ai/attachments/status?attachmentIds=...` | `[].indexStatus === 'READY'` |
 *   | 合同 | `contractApi.listByProject` → `GET /api/projects/{id}/contracts` | `[].contractAmount` |
 *   | 预算 | `GET /api/projects/{id}` | `data.budgetAmount` |
 *   | 主合同金额 | `GET /api/projects/{id}` | `data.contractTotal` |
 *   | 子项目数 | `GET /api/projects/{id}` | `data.childCount` |
 *   | 付款 | `paymentApi.listByProject` → `GET /api/projects/{id}/payments` | `[].paidAmount` |
 *
 * 逻辑删除由后端 `@TableLogic` 在 SQL 层排除（`Attachment.deleted` / `BaseEntity.deleted`），
 * 所以页面接口返回的条数天然是"未删"口径（口径写进 note）。
 */
class TruthResolver {
  constructor({ base, token, timeoutMs }) {
    this.base = base;
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.cache = new Map();
    this.calls = [];
  }

  url(path, params) {
    const u = new URL(`/api${path}`, this.base.endsWith('/') ? this.base : `${this.base}/`);
    for (const [k, v] of Object.entries(params || {})) u.searchParams.set(k, String(v));
    return u.toString();
  }

  /** 只读 GET：带登录 JWT，解开 {code,data} 信封；失败抛错（由调用方转成 no-truth）。 */
  async get(path, params, cacheKey, headers) {
    const key = cacheKey || `${path}?${JSON.stringify(params || {})}`;
    if (this.cache.has(key)) return this.cache.get(key);
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), this.timeoutMs);
    const started = Date.now();
    try {
      const res = await fetch(this.url(path, params), {
        method: 'GET',
        headers: { Authorization: `Bearer ${this.token}`, ...(headers || {}) },
        signal: ctl.signal,
      });
      const text = await res.text();
      this.calls.push({ method: 'GET', path, params: params || null, status: res.status, ms: Date.now() - started });
      if (!res.ok) throw new Error(`HTTP ${res.status} ${text.slice(0, 120)}`);
      const body = text ? JSON.parse(text) : null;
      if (isPlainObject(body) && 'code' in body) {
        if (body.code !== 0) throw new Error(`业务码 ${body.code}：${body.message || ''}`);
        this.cache.set(key, body.data);
        return body.data;
      }
      this.cache.set(key, body);
      return body;
    } finally {
      clearTimeout(timer);
    }
  }

  /** 项目详情（阶段列表/金额/子项目数等的共同来源），按 projectId 缓存。 */
  project(id) {
    return this.get(`/projects/${id}`, null, `project:${id}`);
  }

  /** 某项目全部阶段的附件数：逐阶段调页面接口（阶段卡片用的就是它）。 */
  async phaseAttachmentCounts(projectId) {
    const detail = await this.project(projectId);
    const phases = Array.isArray(detail?.phases) ? detail.phases : [];
    const rows = [];
    for (const ph of phases) {
      const list = await this.get('/attachments',
        { bizType: 'PROJECT_PHASE', bizId: ph.id },
        `att:PROJECT_PHASE:${ph.id}`);
      rows.push({
        phaseId: ph.id,
        phaseName: ph.phaseName,
        sortNo: ph.sortNo,
        attachmentCount: countOf(list) ?? 0,
      });
    }
    return rows;
  }

  /**
   * 解析一道题的 truth 描述 → { kind, values, note, detail }。
   * values 是"答案里必须出现的数字字符串"列表（判定时仍走宽松数字比较）。
   */
  async resolve(truth) {
    const pid = truth.projectId;
    const kind = truth.kind;
    switch (kind) {
      case 'phase_attachment_count': {
        const rows = await this.phaseAttachmentCounts(pid);
        if (rows.length === 0) throw new Error('项目详情里没有阶段（phases 为空）');
        const wanted = truth.phaseName
          ? rows.find((r) => r.phaseName === truth.phaseName)
          : rows[0];
        if (!wanted) {
          throw new Error(`阶段「${truth.phaseName}」不存在；实际阶段：${rows.map((r) => r.phaseName).join('/')}`);
        }
        return {
          kind, values: [String(wanted.attachmentCount)],
          note: `GET /api/attachments?bizType=PROJECT_PHASE&bizId=${wanted.phaseId}（${wanted.phaseName}）条数`
            + `（口径：仅 PROJECT_PHASE 附件，逻辑删除已由后端排除）`,
          detail: { rows, matched: wanted },
        };
      }
      case 'phase_attachment_count_all': {
        const rows = await this.phaseAttachmentCounts(pid);
        return {
          kind, values: rows.map((r) => String(r.attachmentCount)),
          note: '逐阶段 GET /api/attachments?bizType=PROJECT_PHASE&bizId={phaseId} 的条数，按 sortNo 顺序',
          detail: { rows },
        };
      }
      case 'phase_attachment_sum': {
        const rows = await this.phaseAttachmentCounts(pid);
        const sum = rows.reduce((a, r) => a + r.attachmentCount, 0);
        return {
          kind, values: [String(sum)],
          note: `各阶段附件数之和（${rows.map((r) => r.attachmentCount).join('+')}=${sum}）`,
          detail: { rows, sum },
        };
      }
      case 'ai_ready_count': {
        const rows = await this.phaseAttachmentCounts(pid);
        const ids = [];
        for (const r of rows) {
          const list = await this.get('/attachments',
            { bizType: 'PROJECT_PHASE', bizId: r.phaseId }, `att:PROJECT_PHASE:${r.phaseId}`);
          for (const a of listOf(list) || []) if (Number.isInteger(a?.id)) ids.push(a.id);
        }
        if (ids.length === 0) throw new Error('该项目阶段下没有附件，无法统计已入库数');
        const status = await this.get('/ai/attachments/status', { attachmentIds: ids.join(',') },
          `aistatus:${ids.join(',')}`);
        const ready = (listOf(status) || []).filter((s) => String(s?.indexStatus || '').toUpperCase() === 'READY');
        return {
          kind, values: [String(ready.length)],
          note: `GET /api/ai/attachments/status?attachmentIds=... 里 indexStatus=READY 的条数（在册 ${ids.length} 个附件）`,
          detail: { ids, ready: ready.length },
        };
      }
      case 'contract_total': {
        const list = await this.get(`/projects/${pid}/contracts`, null, `contracts:${pid}`);
        const rows = listOf(list) || [];
        const amounts = rows.map((c) => Number(c?.contractAmount ?? 0));
        const total = round2(amounts.reduce((a, b) => a + b, 0));
        return {
          kind, values: [String(rows.length), String(total)],
          note: `GET /api/projects/${pid}/contracts → ${rows.length} 份，contractAmount 求和=${total}`
            + `（口径：仅本项目自身关联合同，V12 project_contract；金额不含 changeAmount）`,
          detail: { count: rows.length, total, rows: rows.map((c) => ({ id: c.id, name: c.name, contractAmount: c.contractAmount })) },
        };
      }
      case 'contract_amount_total': {
        // 只问"合同总额"的题：不要顺手把合同**份数**也变成必须命中项（那是另一个问题）。
        const list = await this.get(`/projects/${pid}/contracts`, null, `contracts:${pid}`);
        const rows = listOf(list) || [];
        const total = round2(rows.map((c) => Number(c?.contractAmount ?? 0)).reduce((a, b) => a + b, 0));
        return {
          kind, values: [String(total)],
          note: `GET /api/projects/${pid}/contracts → contractAmount 求和=${total}（${rows.length} 份；口径：仅本项目自身关联合同，金额不含 changeAmount）`,
          detail: { count: rows.length, total },
        };
      }
      case 'budget_amount': {
        const d = await this.project(pid);
        if (d?.budgetAmount === null || d?.budgetAmount === undefined) throw new Error('项目详情 budgetAmount 为空');
        return {
          kind, values: [String(Number(d.budgetAmount))],
          note: `GET /api/projects/${pid} → budgetAmount（口径：概算/预算金额，单位元）`,
          detail: { budgetAmount: d.budgetAmount },
        };
      }
      case 'contract_amount_main': {
        const d = await this.project(pid);
        const v = d?.contractTotal ?? d?.contractAmount;
        if (v === null || v === undefined) throw new Error('项目详情 contractTotal/contractAmount 均为空');
        return {
          kind, values: [String(Number(v))],
          note: `GET /api/projects/${pid} → contractTotal（口径：主合同金额；后端 contractAmount(pj, inclChange=true) 实时汇总）`,
          detail: { contractTotal: d.contractTotal, contractAmount: d.contractAmount },
        };
      }
      case 'child_count': {
        const d = await this.project(pid);
        if (!Number.isInteger(d?.childCount)) throw new Error('项目详情 childCount 缺失');
        return {
          kind, values: [String(d.childCount)],
          note: `GET /api/projects/${pid} → childCount（口径：parent_id=该项目 的子项目数）`,
          detail: { childCount: d.childCount },
        };
      }
      case 'payment_records': {
        const list = await this.get(`/projects/${pid}/payments`, null, `payments:${pid}`);
        const rows = listOf(list) || [];
        const paid = round2(rows.reduce((a, p) => a + Number(p?.paidAmount ?? 0), 0));
        const values = rows.length === 0 ? ['0'] : [String(rows.length), String(paid)];
        return {
          kind, values,
          note: `GET /api/projects/${pid}/payments → ${rows.length} 条，paidAmount 合计=${paid}`,
          detail: { count: rows.length, paid },
        };
      }
      case 'project_type_status': {
        const d = await this.project(pid);
        return {
          kind, values: [String(d?.type ?? ''), String(d?.status ?? '')],
          note: `GET /api/projects/${pid} → type/status`,
          detail: { type: d?.type, status: d?.status },
        };
      }
      case 'project_name_code': {
        const d = await this.project(pid);
        const name = String(d?.name ?? '').replace(/（[^）]*）|\([^)]*\)/g, '');
        return {
          kind, values: [String(d?.code ?? ''), name].filter(Boolean),
          note: `GET /api/projects/${pid} → code/name（name 去掉括号后缀再比，规避全半角差异）`,
          detail: { code: d?.code, name: d?.name },
        };
      }
      case 'bid_amount': {
        const d = await this.project(pid);
        if (d?.bidAmount === null || d?.bidAmount === undefined) {
          throw new Error('项目详情 bidAmount 为空（库内未登记中标金额）——本题按无真值处理，不编造');
        }
        return {
          kind, values: [String(Number(d.bidAmount))],
          note: `GET /api/projects/${pid} → bidAmount`,
          detail: { bidAmount: d.bidAmount },
        };
      }
      default:
        throw new Error(`未实现的 truth.kind：${kind}`);
    }
  }
}

/**
 * 受控查询（§11.3/§11.4）交叉校验：**只在给了 scope_token 时才走**。
 * 拿不到就返回 null（调用方记 crossCheck skipped），绝不伪造令牌。
 */
async function resolveControlled(base, scopeToken, truth, timeoutMs) {
  const pid = truth.projectId;
  const kindMap = {
    phase_attachment_count: { entity: 'stats', filters: { projectId: pid, kind: 'phase_attachment_count' } },
    phase_attachment_count_all: { entity: 'stats', filters: { projectId: pid, kind: 'phase_attachment_count' } },
    phase_attachment_sum: { entity: 'stats', filters: { projectId: pid, kind: 'phase_attachment_count' } },
    contract_total: { entity: 'contracts', filters: { projectId: pid } },
    contract_amount_total: { entity: 'contracts', filters: { projectId: pid } },
    budget_amount: { entity: 'projects', filters: { projectId: pid } },
    contract_amount_main: { entity: 'projects', filters: { projectId: pid } },
    child_count: { entity: 'projects', filters: { projectId: pid } },
    payment_records: { entity: 'payments', filters: { projectId: pid } },
    project_type_status: { entity: 'projects', filters: { projectId: pid } },
    project_name_code: { entity: 'projects', filters: { projectId: pid } },
    bid_amount: { entity: 'projects', filters: { projectId: pid } },
  };
  const spec = kindMap[truth.kind];
  if (!spec) return null;
  const url = new URL(`/api/ai/query/${spec.entity}`, base.endsWith('/') ? base : `${base}/`).toString();
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${scopeToken}` },
      body: JSON.stringify({ filters: spec.filters, limit: 100 }),
      signal: ctl.signal,
    });
    if (!res.ok) return null;
    const body = JSON.parse(await res.text());
    const data = isPlainObject(body) && isPlainObject(body.data) ? body.data : body;
    const rows = Array.isArray(data?.rows) ? data.rows : [];
    return { entity: spec.entity, filters: spec.filters, values: controlledValues(truth, rows), caliber: data?.caliber ?? null, dataTime: data?.data_time ?? null, scope: data?.scope ?? null };
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** 从受控查询 rows 里取出与 truth.kind 对应的数字（口径必须与 resolveTruth 对齐）。 */
function controlledValues(truth, rows) {
  const first = rows[0] || {};
  switch (truth.kind) {
    case 'phase_attachment_count': {
      const phases = Array.isArray(first.phases) ? first.phases : [];
      const target = truth.phaseName
        ? phases.find((p) => p.phaseName === truth.phaseName)
        : phases[0];
      return target ? [String(target.attachmentCount)] : [];
    }
    case 'phase_attachment_count_all':
      return (Array.isArray(first.phases) ? first.phases : []).map((p) => String(p.attachmentCount));
    case 'phase_attachment_sum':
      return [String((Array.isArray(first.phases) ? first.phases : []).reduce((a, p) => a + Number(p.attachmentCount || 0), 0))];
    case 'contract_total': {
      const amounts = rows.map((c) => Number(c.contractAmount ?? 0));
      return [String(rows.length), String(round2(amounts.reduce((a, b) => a + b, 0)))];
    }
    case 'contract_amount_total': {
      const amounts = rows.map((c) => Number(c.contractAmount ?? 0));
      return [String(round2(amounts.reduce((a, b) => a + b, 0)))];
    }
    case 'budget_amount':
      return first.budgetAmount === undefined ? [] : [String(Number(first.budgetAmount))];
    case 'contract_amount_main':
      return first.contractAmount === undefined ? [] : [String(Number(first.contractAmount))];
    case 'child_count':
      return first.childCount === undefined ? [] : [String(Number(first.childCount))];
    case 'payment_records': {
      const paid = round2(rows.reduce((a, p) => a + Number(p.paidAmount ?? 0), 0));
      return rows.length === 0 ? ['0'] : [String(rows.length), String(paid)];
    }
    case 'project_type_status':
      return [String(first.type ?? ''), String(first.status ?? '')].filter(Boolean);
    case 'project_name_code':
      return [String(first.code ?? ''), String(first.name ?? '').replace(/（[^）]*）|\([^)]*\)/g, '')].filter(Boolean);
    case 'bid_amount':
      return first.bidAmount === undefined ? [] : [String(Number(first.bidAmount))];
    default:
      return [];
  }
}

// ─────────────────────── 请求 ───────────────────────

async function postChat(base, token, body, timeoutMs) {
  const url = new URL('/api/ai/chat', base.endsWith('/') ? base : `${base}/`).toString();
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  const started = Date.now();
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
      signal: ctl.signal,
    });
    const text = await res.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
      payload = { raw: text.slice(0, 500) };
    }
    return { ok: true, httpStatus: res.status, payload, elapsedMs: Date.now() - started };
  } catch (e) {
    const aborted = e && e.name === 'AbortError';
    return {
      ok: false,
      httpStatus: 0,
      payload: null,
      elapsedMs: Date.now() - started,
      error: aborted ? `请求超时（${timeoutMs}ms）` : (e && e.message) || String(e),
    };
  } finally {
    clearTimeout(timer);
  }
}

// ─────────────────────── 输出 ───────────────────────

function pad(s, width) {
  const str = String(s ?? '');
  // 中文按 2 列估算，够用即可（不引第三方 wcwidth）
  let w = 0;
  for (const ch of str) w += /[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]/.test(ch) ? 2 : 1;
  return str + ' '.repeat(Math.max(0, width - w));
}

function printDrySummary(setPath, json, v) {
  const rel = relative(REPO_ROOT, resolve(setPath)) || setPath;
  console.log('===== AI 冒烟评测集 · 数据集体检（--dry，不发任何请求） =====');
  console.log(`评测集：${rel}`);
  console.log(`版本：${json.version}   生成时间：${json.generatedAt}`);
  console.log(`题量：${v.stats.total}`);
  console.log('');
  console.log('各类别条数：');
  for (const c of CATEGORIES) {
    console.log(`  ${pad(c, 12)} ${v.stats.byCategory[c] || 0}`);
  }
  console.log('');
  console.log('字段体检：');
  console.log(`  结构化题带 truth       ${v.stats.structuredWithMetric} / ${(v.stats.byCategory.structured || 0)}`);
  console.log(`  带 must_contain        ${v.stats.withMustContain}`);
  console.log(`  带 must_contain_any    ${v.stats.withMustContainAny}`);
  console.log(`  带 must_call_tool      ${v.stats.withMustCallTool}`);
  console.log(`  负例题（expect_refusal）${v.stats.refusalCases}`);
  console.log(`  越权题（expect_error）  ${v.stats.permissionCases}`);
  console.log(`  弱判定题（expect_weak） ${v.stats.weakCases}`);
  console.log(`  truth.kind 分布        ${JSON.stringify(v.stats.truthKinds)}`);
  console.log('');
  console.log('citation_pages 标注情况：');
  console.log(`  已标注页码             ${v.stats.citationPagesLabeled}`);
  console.log(`  null（待人工补页码）   ${v.stats.citationPagesNull}`);
  console.log('');
  if (v.warnings.length > 0) {
    console.log(`⚠️  提醒 ${v.warnings.length} 条：`);
    for (const w of v.warnings) console.log(`  - ${w}`);
    console.log('');
  }
  if (v.problems.length > 0) {
    console.log(`❌ 体检不合格，${v.problems.length} 个问题：`);
    for (const p of v.problems) console.log(`  - ${p}`);
    console.log('');
    console.log('结论：体检失败（退出码 1）。修好数据集再跑非 dry。');
    return 1;
  }
  console.log(`✅ 体检通过：字段/类别/id/必填项齐备，${v.stats.citationPagesNull} 题待人工补页码，${v.stats.citationPagesLabeled} 题已标注页码。`);
  console.log('（本模式不发任何请求；要打真实问答请去掉 --dry 并给 --token/PM_TOKEN）');
  return 0;
}

/** 真值快照：`eval/truth-snapshot.json`——"上次用页面 API 算出来的真值"，用来发现数据漂移。 */
function loadTruthSnapshot() {
  const p = join(REPO_ROOT, 'eval', 'truth-snapshot.json');
  if (!existsSync(p)) return null;
  try {
    return JSON.parse(readFileSync(p, 'utf8'));
  } catch {
    return { error: `快照文件无法解析：${p}` };
  }
}

/** 比较"快照里的真值"与"刚算出来的真值"，返回漂移列表。 */
function compareSnapshot(snapshot, truths) {
  const drifts = [];
  if (!snapshot || !Array.isArray(snapshot.cases)) return drifts;
  for (const entry of snapshot.cases) {
    const live = truths.get(entry.id);
    if (!live) continue;
    const a = (entry.values || []).map(String).join('|');
    const b = (live.values || []).map(String).join('|');
    if (a !== b) drifts.push({ id: entry.id, kind: entry.kind, snapshot: entry.values, live: live.values });
  }
  return drifts;
}

/**
 * `--list-truth` 输出：把每道声明的真值算出来并列成表。
 *
 * 这一步**不需要 AI 服务**，只连主系统页面接口 —— 所以它是"评测集真值 == 用户页面所见"的自证：
 * 如果这张表和人工翻页面看到的数字不一致，说明评测集（或页面接口口径）有问题，先修数据集。
 * 阶段附件数会额外打印逐阶段明细与合计，便于核对。
 */
function printTruthTable(cases, truths, truthErrors, resolver, base, setPath, drifts, opts) {
  console.log('');
  console.log('===== 真值表（页面 API 现场算出，未调用 AI 服务） =====');
  console.log(`评测集：${relative(REPO_ROOT, resolve(setPath))}    基址：${base}`);
  console.log('');
  console.log(`${pad('题目', 12)} ${pad('truth.kind', 28)} ${pad('真值', 26)} 来源`);
  console.log('-'.repeat(120));
  let ok = 0;
  for (const c of cases) {
    const t = isPlainObject(c.expected?.truth) ? c.expected.truth : null;
    if (!t) continue;
    const r = truths.get(c.id);
    if (!r) {
      console.log(`${pad(c.id, 12)} ${pad(t.kind, 28)} ${pad(`❌ 解析失败`, 26)} ${truthErrors.get(c.id) || ''}`);
      continue;
    }
    ok += 1;
    console.log(`${pad(c.id, 12)} ${pad(t.kind, 28)} ${pad(r.values.join(' / '), 26)} ${r.note || ''}`);
  }
  console.log('');
  console.log('逐阶段附件数明细（口径：GET /api/attachments?bizType=PROJECT_PHASE&bizId={phaseId} 的条数，'
    + '逻辑删除由后端 @TableLogic 排除）：');
  console.log(`${pad('阶段 id', 10)} ${pad('阶段名', 22)} ${pad('排序', 6)} 附件数`);
  for (const [, r] of truths) {
    if (!r.detail || !Array.isArray(r.detail.rows)) continue;
    for (const row of r.detail.rows) {
      console.log(`${pad(row.phaseId, 10)} ${pad(row.phaseName, 22)} ${pad(row.sortNo, 6)} ${row.attachmentCount}`);
    }
    if (Number.isInteger(r.detail.sum)) console.log(`${pad('', 10)} ${pad('合计', 22)} ${pad('', 6)} ${r.detail.sum}`);
    break; // 阶段明细同一项目下相同，打一次即可
  }
  console.log('');
  console.log(`真值解析调用明细（共 ${resolver.calls.length} 次，同一项目/阶段已缓存）：`);
  const byPath = new Map();
  for (const call of resolver.calls) {
    const key = `${call.method} ${call.path} → HTTP ${call.status}`;
    byPath.set(key, (byPath.get(key) || 0) + 1);
  }
  for (const [k, n] of byPath) console.log(`  ${k}  ×${n}`);
  console.log('');
  const declared = cases.filter((c) => isPlainObject(c.expected?.truth)).length;

  // 数据漂移：快照（上次算的真值）vs 现在算出来的
  if (drifts && drifts.length > 0) {
    console.log(`⚠️  真值与 eval/truth-snapshot.json 不一致 ${drifts.length} 处 —— 说明库里数据变了，`);
    console.log('   数据集里写死的 must_contain 数字需要同步更新（跑 --write-truth 后按新值改）：');
    for (const d of drifts) {
      console.log(`  - ${d.id} (${d.kind})：快照 ${JSON.stringify(d.snapshot)} → 现在 ${JSON.stringify(d.live)}`);
    }
    console.log('');
  }

  if (truthErrors.size > 0) {
    console.log(`⚠️  ${truthErrors.size}/${declared} 题无可用真值（非 dry 时记 SKIP(no-truth)，不算失败）：`);
    for (const [id, err] of truthErrors) console.log(`  - ${id}: ${err}`);
    console.log('（已解析成功的题目真值即为上表，可直接与页面人工核对。）');
  }

  if (opts && opts.writeTruth) {
    const snapshot = {
      generatedAt: new Date().toISOString(),
      base,
      set: relative(REPO_ROOT, resolve(setPath)),
      note: '由 node scripts/eval-ai.mjs --write-truth 生成：真值来自主系统页面接口（见 eval/README.md「真值来源与局限」）。'
        + '本文件用于发现数据漂移，不是判定依据；判定依据是同一次运行现场算出的真值。',
      cases: cases
        .filter((c) => isPlainObject(c.expected?.truth) && truths.has(c.id))
        .map((c) => {
          const r = truths.get(c.id);
          return {
            id: c.id,
            kind: r.kind,
            truth: c.expected.truth,
            values: r.values,
            note: r.note || null,
            detail: r.detail || null,
          };
        }),
    };
    const outPath = join(REPO_ROOT, 'eval', 'truth-snapshot.json');
    writeFileSync(outPath, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8');
    console.log(`→ 真值快照已写入：${relative(REPO_ROOT, outPath)}（${snapshot.cases.length} 题）`);
  }

  console.log(`✅ 真值解析完成：${ok}/${declared} 成功。本模式未发任何 /api/ai/chat 请求。`);
  return 0;
}

function printRunSummary(rows, metrics, base, setPath) {
  console.log('');
  console.log('===== 逐题结果 =====');
  for (const r of rows) {
    const tag = r.status === 'PASS' ? 'PASS ' : r.status === 'SKIP' ? `SKIP(${r.skipReason || '-'})` : 'FAIL ';
    console.log(`${pad(tag, 16)} ${pad(r.id, 12)} ${pad(r.category, 11)} ${String(r.elapsedMs ?? '-').padStart(6)}ms  ${r.question.slice(0, 40)}`);
    for (const reason of r.reasons || []) console.log(`        ↳ ${reason}`);
  }

  const total = rows.length;
  const pass = rows.filter((r) => r.status === 'PASS').length;
  const fail = rows.filter((r) => r.status === 'FAIL').length;
  const skip = rows.filter((r) => r.status === 'SKIP').length;
  const avg = total === 0 ? 0 : Math.round(rows.reduce((a, r) => a + (r.elapsedMs || 0), 0) / total);

  console.log('');
  console.log('===== 汇总 =====');
  console.log(`评测集：${relative(REPO_ROOT, resolve(setPath))}    基址：${base}`);
  console.log(`总题数 ${total} ｜ 通过 ${pass} ｜ 失败 ${fail} ｜ 跳过 ${skip}`);
  console.log('');
  console.log('指标：');
  console.log(`  数字一致率      ${fmtRate(metrics.number)}     （答案数字 == 真值；真值来自页面 API，忽略千分位/空格/单位）`);
  console.log(`  引用命中率      ${fmtRate(metrics.citation)}   （已标注页码的题命中页数 / 应命中页数）`);
  console.log(`  工具选择正确率  ${fmtRate(metrics.tool)}       （toolTrace 里出现期望工具）`);
  console.log(`  拒答正确率      ${fmtRate(metrics.refusal)}    （negative 题出现如实拒答措辞）`);
  console.log(`  平均耗时        ${avg} ms（单题串行，含网络与模型时间）`);
  if (metrics.caliber) console.log(`  口径转述率      ${fmtRate(metrics.caliber)}    （caliber_required 题出现口径/数据时间表述；仅记账，不判失败）`);
  console.log(`  引用覆盖率      ${fmtRate(metrics.citationCoverage)}    （指定了 attachmentIds 的题是否给出 >=1 条引用）`);
  if (metrics.crossCheck && metrics.crossCheck.total > 0) {
    console.log(`  两条路径口径一致率 ${fmtRate(metrics.crossCheck)}  （页面 API vs §11 受控查询；交叉校验，不判失败）`);
  } else {
    console.log('  两条路径口径一致率   n/a（未提供 --scope-token/PM_SCOPE_TOKEN，跳过交叉校验）');
  }
  console.log('');
  console.log('跳过原因（三种，已区分）：');
  console.log(`  no-truth（该题无可用真值来源）      ${metrics.skipReasons['no-truth'] || 0}`);
  console.log(`  unlabeled（citation_pages=null）    ${metrics.skipReasons.unlabeled || 0}`);
  console.log(`  no-endpoint（接口不可达）           ${metrics.skipReasons['no-endpoint'] || 0}`);
  console.log(`  页面接口解析失败（真值拿不到）      ${metrics.truthResolveFailures || 0}`);
  console.log('');
  console.log(fail > 0 ? `❌ 有 ${fail} 题失败（退出码 1）` : `✅ 无失败（退出码 0）`);
}

function fmtRate(r) {
  if (!r || r.total === 0) return '   n/a（无适用题目）';
  const pct = ((r.hit / r.total) * 100).toFixed(1);
  return `${String(pct).padStart(5)}%  (${r.hit}/${r.total})`;
}

function aggregate(rows) {
  const metrics = {
    citation: { hit: 0, total: 0 },
    number: { hit: 0, total: 0 },
    tool: { hit: 0, total: 0 },
    refusal: { hit: 0, total: 0 },
    caliber: { hit: 0, total: 0 },
    citationCoverage: { hit: 0, total: 0 },
    crossCheck: { hit: 0, total: 0 },
    skipReasons: {},
    truthResolveFailures: 0,
  };
  for (const r of rows) {
    const c = r.checks || {};
    if (c.citationPages && !c.citationPages.skipped) {
      metrics.citation.hit += c.citationPages.hit;
      metrics.citation.total += c.citationPages.total;
    }
    // 数字一致率的分母：**有真值的题**的逐值比对（页面 API 算出来的 values）。
    if (c.truth && !c.truth.skipped) {
      metrics.number.hit += c.truth.hit;
      metrics.number.total += c.truth.total;
    }
    if (r.truthError) metrics.truthResolveFailures += 1;
    if (typeof c.toolCalled === 'boolean') {
      metrics.tool.total += 1;
      if (c.toolCalled) metrics.tool.hit += 1;
    }
    if (typeof c.refusal === 'boolean') {
      metrics.refusal.total += 1;
      if (c.refusal) metrics.refusal.hit += 1;
    }
    if (typeof c.caliber === 'boolean') {
      metrics.caliber.total += 1;
      if (c.caliber) metrics.caliber.hit += 1;
    }
    if (Number.isInteger(c.citationRequired)) {
      metrics.citationCoverage.total += 1;
      if (c.citationCoverage && c.citationCoverage.citations > 0) metrics.citationCoverage.hit += 1;
    }
    if (c.crossCheck && typeof c.crossCheck.agree === 'boolean') {
      metrics.crossCheck.total += 1;
      if (c.crossCheck.agree) metrics.crossCheck.hit += 1;
    }
    if (r.status === 'SKIP' && r.skipReason) {
      metrics.skipReasons[r.skipReason] = (metrics.skipReasons[r.skipReason] || 0) + 1;
    }
  }
  return metrics;
}

// ─────────────────────── main ───────────────────────

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`❌ ${e.message}`);
    console.error(usage());
    return 2;
  }
  if (opts.help) {
    console.log(usage());
    return 0;
  }
  if (!Number.isFinite(opts.timeout) || opts.timeout <= 0) {
    console.error('❌ --timeout 必须是正数（毫秒）');
    return 2;
  }

  const setPath = resolve(REPO_ROOT, opts.set);
  let json;
  try {
    ({ json } = loadDataset(setPath));
  } catch (e) {
    console.error(`❌ ${e.message}`);
    return 2;
  }

  const v = validateDataset(json);

  // ── dry：只体检（默认可用档，无服务也能当质量门）──
  if (opts.dry) {
    return printDrySummary(setPath, json, v);
  }

  // ── 非 dry：先过体检（数据集不合格就没必要打请求）──
  // --list-truth 不判失败：它的目的是"把真值算出来给人看/核对"，
  // 顺带发现的数据集口径问题以提醒形式带出，不阻塞。
  if (v.problems.length > 0) {
    if (opts.listTruth) {
      console.warn(`⚠️  数据集体检有 ${v.problems.length} 个问题（--list-truth 不阻塞，仅提示）：`);
      for (const p of v.problems) console.warn(`  - ${p}`);
    } else {
      console.error('❌ 数据集体检不合格，请先 --dry 修好：');
      for (const p of v.problems) console.error(`  - ${p}`);
      return 1;
    }
  }

  const token = opts.token || process.env.PM_TOKEN || '';
  if (!token) {
    console.error(`❌ 没有 token：${opts.listTruth ? '--list-truth' : '非 dry 模式'}必须给 --token <JWT> 或设置环境变量 PM_TOKEN。`);
    console.error('');
    console.error('怎么拿 token（任选一种）：');
    console.error(`  1) 浏览器登录主系统后，DevTools → Application → Local Storage 取 token 值；`);
    console.error(`  2) POST ${opts.base}/api/auth/login  body {"account":"<账号>","password":"<口令>"}，取响应 data.token；`);
    console.error('  3) PowerShell（注意别把 token 写进任何文件）：');
    console.error('     $env:PM_TOKEN = "<粘贴 JWT>"');
    console.error('     node scripts/eval-ai.mjs --set eval/smoke-30.json --list-truth');
    return 2;
  }
  if (!/^https?:\/\//.test(opts.base)) {
    console.error(`❌ --base 必须是 http(s) URL：${opts.base}`);
    return 2;
  }

  // 探活（连不上就别逐题等超时）
  try {
    const health = await fetch(new URL('/api/health', opts.base).toString(), { method: 'GET' });
    console.log(`→ 主系统探活：GET /api/health → HTTP ${health.status}`);
  } catch (e) {
    console.error(`❌ 连不上主系统 ${opts.base}：${(e && e.message) || e}`);
    console.error('   服务没起就别硬跑：先 start-dev.cmd（后端 :8080）或部署镜像模式。');
    return 2;
  }

  let cases = json.cases;
  if (opts.max > 0) cases = cases.slice(0, opts.max);

  const scopeToken = opts.scopeToken || process.env.PM_SCOPE_TOKEN || '';
  const resolver = new TruthResolver({ base: opts.base, token, timeoutMs: opts.timeout });

  // ── 真值解析（页面 API，逐题；同一项目/阶段的请求在 resolver 内缓存）──
  console.log(`→ 解析真值：只读调主系统页面接口（GET /api/projects/{id}、/api/attachments、`
    + `/api/projects/{id}/contracts、/api/projects/{id}/payments、/api/ai/attachments/status）`);
  const truths = new Map();
  const truthErrors = new Map();
  for (const c of cases) {
    const t = isPlainObject(c.expected?.truth) ? c.expected.truth : null;
    if (!t) continue;
    try {
      const resolved = await resolver.resolve(t);
      resolved.resolvedAt = new Date().toISOString();
      truths.set(c.id, resolved);
    } catch (e) {
      truthErrors.set(c.id, (e && e.message) || String(e));
    }
  }
  const resolvedCount = truths.size;
  const declaredCount = cases.filter((c) => isPlainObject(c.expected?.truth)).length;
  console.log(`→ 真值解析结果：${resolvedCount}/${declaredCount} 题解析成功`
    + (truthErrors.size > 0 ? `，${truthErrors.size} 题无可用真值（记 SKIP(no-truth)）` : ''));

  // 数据漂移检测：与上次写入的快照比对（有快照才比）
  const snapshot = loadTruthSnapshot();
  const drifts = compareSnapshot(snapshot, truths);
  if (drifts.length > 0) {
    console.log(`⚠️  检测到真值漂移 ${drifts.length} 处（eval/truth-snapshot.json 与当前库不一致）`
      + `——数据集里写死的数字可能已过期，详见下方真值表`);
  }

  if (scopeToken) {
    console.log('→ 受控查询交叉校验：已提供 scope_token，逐题额外调 POST /api/ai/query/{entity} 对账');
  } else {
    console.log('→ 受控查询交叉校验：未提供 --scope-token/PM_SCOPE_TOKEN，跳过（主真值仍为页面 API）');
  }

  // ── --list-truth：打印真值表后退出，绝不发 /api/ai/chat ──
  if (opts.listTruth) {
    return printTruthTable(cases, truths, truthErrors, resolver, opts.base, setPath, drifts, opts);
  }

  console.log(`→ 逐题请求 POST ${opts.base}/api/ai/chat（共 ${cases.length} 题，串行，超时 ${opts.timeout}ms）`);

  const rows = [];
  for (const c of cases) {
    const scope = isPlainObject(c.scope) ? c.scope : {};
    const body = { question: c.question };
    if (Number.isInteger(scope.projectId) && scope.projectId > 0) body.projectId = scope.projectId;
    if (Array.isArray(scope.attachmentIds) && scope.attachmentIds.length > 0) {
      body.attachmentIds = scope.attachmentIds;
    }
    const res = await postChat(opts.base, token, body, opts.timeout);

    // 交叉校验（可选，只记账）
    let xcheck = null;
    const t = isPlainObject(c.expected?.truth) ? c.expected.truth : null;
    if (scopeToken && t && truths.has(c.id)) xcheck = await resolveControlled(opts.base, scopeToken, t, opts.timeout);

    const judged = judge(c, res.httpStatus, res.payload, res.elapsedMs, truths.get(c.id) || null, xcheck);
    if (!truths.has(c.id) && t) {
      judged.checks.truthError = truthErrors.get(c.id) || '真值未解析（未实现或字段缺失）';
    }
    const answer = res.payload && (res.payload.data?.answer ?? res.payload.answer);
    const row = {
      id: c.id,
      category: c.category,
      question: c.question,
      note: c.note,
      scope,
      expected: c.expected,
      truth: truths.get(c.id) || null,
      truthError: truthErrors.get(c.id) || null,
      request: { url: '/api/ai/chat', body },
      httpStatus: res.httpStatus,
      requestError: res.error || null,
      elapsedMs: res.elapsedMs,
      status: judged.status,
      skipReason: judged.skipReason || null,
      reasons: judged.reasons,
      checks: judged.checks,
      answer: typeof answer === 'string' ? answer : null,
      citations: extractCitations(res.payload).map((x) => ({
        index: x.index ?? null,
        filename: x.filename ?? null,
        pageNo: x.pageNo ?? x.page_no ?? null,
        attachmentId: x.attachmentId ?? null,
      })),
      toolTrace: traceNames(res.payload),
      degraded: res.payload?.data?.degraded ?? res.payload?.degraded ?? null,
      notice: res.payload?.data?.notice ?? res.payload?.notice ?? null,
    };
    if (res.error && judged.status === 'PASS') {
      // 请求都失败了还判 PASS 说明判定逻辑有问题，直接改成 FAIL
      row.status = 'FAIL';
      row.reasons = [...row.reasons, `请求失败但判定为通过，判定逻辑异常：${res.error}`];
    }
    rows.push(row);
  }

  const metrics = aggregate(rows);
  printRunSummary(rows, metrics, opts.base, setPath);

  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const outPath = opts.out ? resolve(REPO_ROOT, opts.out) : join(REPO_ROOT, 'eval', `report-${stamp}.json`);
  const report = {
    version: json.version,
    set: relative(REPO_ROOT, setPath),
    base: opts.base,
    ranAt: new Date().toISOString(),
    mode: 'live',
    tokenPresent: true,
    scopeTokenPresent: Boolean(scopeToken),
    truthCalls: resolver.calls,
    truthDrift: drifts,
    summary: {
      total: rows.length,
      pass: rows.filter((r) => r.status === 'PASS').length,
      fail: rows.filter((r) => r.status === 'FAIL').length,
      skip: rows.filter((r) => r.status === 'SKIP').length,
    },
    metrics,
    byCategory: rows.reduce((acc, r) => {
      acc[r.category] = acc[r.category] || { total: 0, pass: 0, fail: 0, skip: 0 };
      acc[r.category].total += 1;
      acc[r.category][r.status === 'PASS' ? 'pass' : r.status === 'FAIL' ? 'fail' : 'skip'] += 1;
      return acc;
    }, {}),
    results: rows,
    datasetProblems: v.problems,
    datasetWarnings: v.warnings,
  };
  writeFileSync(outPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(`→ 报告已写入：${relative(REPO_ROOT, outPath)}`);

  return report.summary.fail > 0 ? 1 : 0;
}

/**
 * 退出方式：**只设 process.exitCode，绝不调 process.exit()**。
 * 实测（Node v24 / Windows）：main 结束后立刻 process.exit(0) 会在 stdout 仍持有
 * uv 异步句柄时触发 `Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), file src\win\async.c`
 * —— 汇总表已经打印出来，退出码却是 0xC0000409（-1073740791），CI 会误判成崩溃。
 * 设 exitCode 让事件循环自然收尾即可，退出码同样准确。
 */
main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch((e) => {
    console.error(`❌ 未预期的错误：${(e && e.stack) || e}`);
    process.exitCode = 2;
  });
