#!/usr/bin/env node
/**
 * ============================================================
 * 解析流水线端到端演示 / 验收（Node 18+，**不新增任何 npm 依赖**）
 *
 * 它扮演「AI 服务」这一侧：用 `X-AI-Service-Token` 把五个阶段（PARSE/CLEAN/CHUNK/EMBED/INDEX）
 * 与五类产物写进主系统，再用**用户 JWT** 读回来核对——所以它验证的是"链路真的通"，
 * 而不是"代码里看起来有"。
 *
 * 用法（仓库根）：
 *   node scripts/pipeline-demo.mjs --dry
 *   node scripts/pipeline-demo.mjs --base http://127.0.0.1:8180 --user admin --pass *** \
 *        --token <AI_SERVICE_TOKEN> [--task-id N]
 *
 * 参数（口令/令牌**只从参数或环境变量读，脚本里没有默认值**）：
 *   --base <url>      主系统基址（默认 http://127.0.0.1:8180；也可 PM_BASE）
 *   --user <账号>     登录账号（也可 PM_USER）
 *   --pass <口令>     登录口令（也可 PM_PASS；**不要把真实口令写进脚本/提交/贴图**）
 *   --token <令牌>    AI 服务令牌（X-AI-Service-Token；也可 AI_SERVICE_TOKEN）
 *   --task-id <N>     指定 attachment_ai_task 的 id；不给就取最近一条（用户侧 GET /api/ai/tasks）
 *   --dry             只校验参数与目标可达（GET /api/health），**不写任何数据**
 *   --timeout <ms>    单请求超时（默认 20000；也可 PM_TIMEOUT_MS）
 *   -h, --help        打印本说明
 *
 * 退出码：0 = 全部检查通过；1 = 有失败（并打印下一步命令）。
 *
 * ⚠️ 这是**写操作**（非 dry）：会给选中的 run 追加阶段行（attempts+1）与产物行（artifact_rev+1），
 *    并尝试把任务置为 DONE。演示请在测试/演示环境跑，别对着生产里正在跑的任务跑。
 * ⚠️ 日志**不打印令牌与口令**；错误文案里也只带 HTTP 码与主系统返回的 message。
 * ============================================================
 */

const INGEST_STAGES = ['PARSE', 'CLEAN', 'CHUNK', 'EMBED', 'INDEX'];
const STAGE_NAMES = {
  PARSE: '识别（解析 OCR/文本层）',
  CLEAN: '清洗（规则 C1~C8）',
  CHUNK: '切片',
  EMBED: '向量化',
  INDEX: '入库（索引）',
};
const ARTIFACT_TYPES = ['page_digest', 'clean_diff', 'chunk_manifest', 'embed_batch', 'index_result'];
const ARTIFACT_DESC = {
  page_digest: '页级摘要',
  clean_diff: '清洗前后对照摘要',
  chunk_manifest: '切片清单摘要',
  embed_batch: '向量化批次摘要',
  index_result: '索引写入结果摘要',
};
const DEFAULT_BASE = 'http://127.0.0.1:8180';
const DEFAULT_TIMEOUT_MS = 20000;
const CLEAN_SKIP_REASON = '清洗默认关闭（ai.clean.enabled=false / clean_rev=0）：本阶段无动作，切片输入 = 解析原文';

// ── 终端宽度（中文占 2 列，只为了表格对齐；算错不影响结论）──────────────
function displayWidth(text) {
  let width = 0;
  for (const ch of String(text)) {
    const code = ch.codePointAt(0);
    width += code >= 0x2e80 && code <= 0xa4cf ? 2 : 1;
  }
  return width;
}

function pad(text, width) {
  const s = String(text ?? '');
  return s + ' '.repeat(Math.max(0, width - displayWidth(s)));
}

/** 按显示宽度截断（表格里只放短标签，完整提示在表后用「失败项详情」打印）。 */
function truncateWidth(text, maxWidth) {
  let width = 0;
  let out = '';
  for (const ch of String(text)) {
    const w = displayWidth(ch);
    if (width + w > maxWidth) break;
    width += w;
    out += ch;
  }
  return out;
}

function printTable(rows) {
  const widths = [6, 34, 6, 30, 26, 8];
  const header = ['步骤', '请求', 'HTTP', '关键字段', '结论', '备注'];
  const line = (cells) => `| ${cells.map((c, i) => pad(c, widths[i])).join(' | ')} |`;
  console.log(line(header));
  console.log(`|${widths.map((w) => '-'.repeat(w + 2)).join('|')}|`);
  for (const row of rows) {
    console.log(line([row.step, row.request, row.http, row.key, row.verdict, row.note || '']));
  }
}

function usage() {
  return [
    '解析流水线端到端演示 / 验收（扮演 AI 侧上报，然后用用户 JWT 读回核对）',
    '',
    '用法：',
    '  node scripts/pipeline-demo.mjs --base http://127.0.0.1:8180 --user admin --pass *** \\',
    '       --token <AI_SERVICE_TOKEN> [--task-id N]',
    '  node scripts/pipeline-demo.mjs --dry',
    '',
    '选项：',
    '  --base <url>     主系统基址（默认 http://127.0.0.1:8180）',
    '  --user <账号>    登录账号（或环境变量 PM_USER）',
    '  --pass <口令>    登录口令（或环境变量 PM_PASS；脚本内无默认口令）',
    '  --token <令牌>   AI 服务令牌（或环境变量 AI_SERVICE_TOKEN）',
    '  --task-id <N>    指定 attachment_ai_task id；不给取最近一条',
    '  --dry            只 GET /api/health 校验参数与可达性，不写数据',
    '  --timeout <ms>   单请求超时（默认 20000）',
    '  -h, --help       本说明',
    '',
    '环境变量：PM_BASE / PM_USER / PM_PASS / AI_SERVICE_TOKEN / PM_TIMEOUT_MS',
    '',
    '退出码：0 全部通过；1 有失败（会打印下一步命令）。',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    base: process.env.PM_BASE || DEFAULT_BASE,
    user: process.env.PM_USER || '',
    pass: process.env.PM_PASS || '',
    token: process.env.AI_SERVICE_TOKEN || '',
    taskId: 0,
    dry: false,
    help: false,
    timeoutMs: Number(process.env.PM_TIMEOUT_MS || DEFAULT_TIMEOUT_MS),
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    const next = (name) => {
      const v = argv[i + 1];
      if (v === undefined || v.startsWith('--')) {
        throw new Error(`${name} 需要一个值`);
      }
      i += 1;
      return v;
    };
    switch (a) {
      case '--base': out.base = next('--base'); break;
      case '--user': out.user = next('--user'); break;
      case '--pass': out.pass = next('--pass'); break;
      case '--token': out.token = next('--token'); break;
      case '--task-id': {
        const raw = next('--task-id');
        const n = Number(raw);
        if (!Number.isInteger(n) || n <= 0) {
          throw new Error(`--task-id 必须是正整数，收到：${raw}`);
        }
        out.taskId = n;
        break;
      }
      case '--timeout': {
        const n = Number(next('--timeout'));
        if (!Number.isFinite(n) || n < 1000) {
          throw new Error('--timeout 必须是 ≥1000 的毫秒数');
        }
        out.timeoutMs = n;
        break;
      }
      case '--dry': out.dry = true; break;
      case '-h':
      case '--help': out.help = true; break;
      default:
        throw new Error(`未知参数：${a}（用 --help 看用法）`);
    }
  }
  out.base = String(out.base).replace(/\/+$/, '');
  return out;
}

function validate(args) {
  const problems = [];
  try {
    const u = new URL(args.base);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      problems.push(`--base 只支持 http/https：${args.base}`);
    }
  } catch {
    problems.push(`--base 不是合法 URL：${args.base}`);
  }
  if (!Number.isFinite(args.timeoutMs) || args.timeoutMs < 1000) {
    problems.push('超时必须是 ≥1000 的毫秒数');
  }
  if (!args.dry) {
    if (!args.user) problems.push('缺少 --user（登录账号）或环境变量 PM_USER');
    if (!args.pass) problems.push('缺少 --pass（登录口令）或环境变量 PM_PASS（不要把口令写进脚本）');
    if (!args.token) problems.push('缺少 --token（X-AI-Service-Token）或环境变量 AI_SERVICE_TOKEN');
  }
  return problems;
}

// ── HTTP（原生 fetch；超时用 AbortController，Node 18 可用）─────────────
async function call(args, method, path, { jwt = '', serviceToken = '', body } = {}) {
  const url = `${args.base}${path}`;
  const headers = { Accept: 'application/json' };
  if (jwt) headers.Authorization = `Bearer ${jwt}`;
  if (serviceToken) headers['X-AI-Service-Token'] = serviceToken;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), args.timeoutMs);
  try {
    const res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { status: res.status, json, text };
  } catch (err) {
    const cause = err?.cause?.code || err?.cause?.message;
    const reason = err?.name === 'AbortError'
      ? `请求超时（${args.timeoutMs}ms）`
      : (err?.code || err?.message || String(err)) + (cause ? `：${cause}` : '');
    return { status: 0, json: null, text: '', error: reason };
  } finally {
    clearTimeout(timer);
  }
}

function messageOf(res) {
  const msg = res?.json?.message;
  return typeof msg === 'string' && msg.trim() ? msg.trim().slice(0, 300) : '';
}

/**
 * 失败分流：把 HTTP 码翻译成"下一步该做什么"。
 * 不打印任何凭据；只给命令与配置项名字。
 */
function hintFor(scope, res) {
  if (!res || res.status === 0) {
    return `服务不可达（${res?.error || 'connect failed'}）。先起服务：start-dev.cmd（后端 :8180）或 docker compose up -d；再核对 --base 是否为 http://127.0.0.1:8180`;
  }
  const msg = messageOf(res);
  if (res.status === 401) {
    if (scope === 'service') {
      return 'X-AI-Service-Token 与主系统不一致。核对 main/.env 与 ai/.env 的 AI_SERVICE_TOKEN 是同一个值（改完 restart main/ai）；用户 JWT 不能调 /api/ai/pipeline/**（那条前缀只认服务令牌）';
    }
    return '登录被拒。检查 --user/--pass（或 PM_USER/PM_PASS）；账号是否停用/被锁。口令不要写进脚本或提交';
  }
  if (res.status === 403) {
    return 'pipeline_token 不属于本 run（本脚本默认不带它）。去掉 X-AI-Pipeline-Token 或确认 run_id 正确';
  }
  if (res.status === 404) {
    if (scope === 'run') return 'run 不存在：确认 --task-id 是 attachment_ai_task.id（不是附件 id），且该任务未被物理删除；先 GET /api/ai/runs 看可见运行';
    return '资源不存在（检查路径/参数）';
  }
  if (res.status === 409) {
    return '状态被并发改写/租约失效。重新查 run 详情后按当前状态重试；不要在 409 上直接重发同一报文';
  }
  if (res.status === 400) {
    if (msg.includes('非法状态迁移')) {
      return '主系统状态机拒绝该终态（常见：任务还是 QUEUED）。本脚本不替你 claim：让 AI 服务领取该任务（RUNNING）或选一条 RUNNING/DONE 的任务再跑';
    }
    return `报文被拒：${msg || '看主系统日志'}（400 不要重试，按提示改报文）`;
  }
  if (res.status === 429) return '被限流/队列满：按退避重试，不要形成重试风暴（看 AI 侧 /health?with_metrics=true 的 queues）';
  if (res.status >= 500) return '主系统内部错误：看 pm.sh logs main backend 的栈与现场参数';
  return msg || `HTTP ${res.status}`;
}

function printFailureHints(rows) {
  const failed = rows.filter((r) => r.verdict !== 'OK' && r.full);
  if (!failed.length) return;
  console.error('\n失败项详情（下一步）：');
  for (const r of failed) console.error(`  · ${r.step} ${r.request}\n    ${r.full}`);
}

function fail(rows, message, commands) {
  console.error(`\n✗ ${message}`);
  if (commands && commands.length) {
    console.error('下一步：');
    for (const c of commands) console.error(`  ${c}`);
  }
  if (rows.length) {
    console.error('');
    printTable(rows);
    printFailureHints(rows);
  }
  process.exit(1);
}

function row(step, request, res, key, verdict, note) {
  const full = note || '';
  const short = displayWidth(full) > 30 ? `${truncateWidth(full, 28)}…` : full;
  return {
    step,
    request,
    http: res.status === 0 ? 'ERR' : String(res.status),
    key: key ?? '',
    verdict: verdict ?? (res.status >= 200 && res.status < 300 ? 'OK' : 'FAIL'),
    note: short,
    full,
  };
}

/** 取任务（用户 JWT） */
async function loadTask(args, jwt, rows) {
  if (args.taskId) {
    const res = await call(args, 'GET', `/api/ai/tasks/${args.taskId}`, { jwt });
    const ok = res.status === 200 && res.json?.code === 0 && res.json?.data;
    rows.push(row('②选任务', `GET /api/ai/tasks/${args.taskId}`, res,
      ok ? `taskId=${args.taskId}` : '', ok ? 'OK' : 'FAIL', ok ? '' : hintFor('user', res)));
    if (!ok) fail(rows, '取指定任务失败', ['确认 --task-id 是 attachment_ai_task 的 id（用户侧接口 GET /api/ai/tasks 可查）']);
    return res.json.data;
  }
  const res = await call(args, 'GET', '/api/ai/tasks?page=1&size=50', { jwt });
  const records = res.json?.data?.records;
  const ok = res.status === 200 && res.json?.code === 0 && Array.isArray(records) && records.length > 0;
  rows.push(row('②选任务', 'GET /api/ai/tasks?page=1&size=50', res,
    ok ? `最近一条 taskId=${records[0].taskId}` : '', ok ? 'OK' : 'FAIL',
    ok ? '' : (Array.isArray(records) && records.length === 0 ? '没有 AI 解析任务' : hintFor('user', res))));
  if (!ok) {
    fail(rows, '没有可用的 attachment_ai_task', [
      '先在界面上对一个附件点「解析」（或 POST /api/ai/attachments/{id}/parse，需 ADMIN/MANAGER），再重跑本脚本',
      '也可用 --task-id <N> 指定一条已有任务',
    ]);
  }
  return records[0];
}

async function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(`✗ ${err.message}`);
    console.error(`\n${usage()}`);
    process.exit(1);
  }
  if (args.help) {
    console.log(usage());
    return;
  }
  const problems = validate(args);
  if (problems.length) {
    console.error('✗ 参数校验没通过：');
    for (const p of problems) console.error(`  - ${p}`);
    console.error(`\n${usage()}`);
    process.exit(1);
  }

  const rows = [];
  console.log('解析流水线演示 / 验收');
  console.log(`  目标：${args.base}${args.dry ? '（--dry：只探活，不写数据）' : ''}`);

  // ── ① 目标可达 ────────────────────────────────────────────────
  const health = await call(args, 'GET', '/api/health');
  const healthOk = health.status === 200 && health.json?.code === 0;
  const db = health.json?.data?.db;
  rows.push(row('①探活', 'GET /api/health', health, healthOk ? `db=${db}` : '', healthOk ? 'OK' : 'FAIL',
    healthOk ? '' : hintFor('public', health)));
  if (!healthOk) {
    fail(rows, '目标不可达', [
      `确认服务在 ${args.base}：start-dev.cmd（后端 :8180）或 docker compose ps`,
      '若服务在别的端口/主机，用 --base 指定（部署态是 http://<服务器IP>:8180）',
    ]);
  }
  if (db !== 'up') {
    console.log(`  ⚠️ 进程可达但数据库不是 up（db=${db}）：--dry 只能证明"端口通"，登录/读任务会失败。`);
  }
  if (args.dry) {
    printTable(rows);
    console.log('\n✓ --dry 通过：参数合法、目标可达，未写任何数据。');
    if (db !== 'up') {
      console.log('  ⚠️ 未验证：登录与流水线写链路（数据库 down）。修好库后去掉 --dry 再跑一次。');
    }
    return;
  }

  // ── ② 登录（用户 JWT）────────────────────────────────────────
  const login = await call(args, 'POST', '/api/auth/login', {
    body: { account: args.user, password: args.pass },
  });
  const jwt = login.json?.data?.token;
  const loginOk = login.status === 200 && login.json?.code === 0 && typeof jwt === 'string' && jwt.length > 0;
  rows.push(row('①登录', 'POST /api/auth/login', login, loginOk ? '拿到用户 JWT' : '', loginOk ? 'OK' : 'FAIL',
    loginOk ? '' : hintFor('user', login)));
  if (!loginOk) fail(rows, '登录失败', ['核对 --user/--pass（或 PM_USER/PM_PASS）后重跑']);

  // ── ③ 选任务 ────────────────────────────────────────────────
  const task = await loadTask(args, jwt, rows);
  const taskId = task.taskId;
  const runId = `run-${taskId}`;
  console.log(`  任务：taskId=${taskId} attachmentId=${task.attachmentId} status=${task.status} → runId=${runId}`);
  if (task.status === 'QUEUED') {
    console.log('  ⚠️ 任务当前是 QUEUED：主系统状态机只允许 RUNNING→终态，/finish 会被 400 拒绝。');
  }

  // ── ④ 五个阶段：每个先 RUNNING 再终态；CLEAN 报 SKIPPED ────────
  let stageFail = 0;
  for (const stage of INGEST_STAGES) {
    const running = await call(args, 'POST', `/api/ai/pipeline/runs/${runId}/stages/${stage}`, {
      serviceToken: args.token,
      body: { status: 'RUNNING', item_count: 0, input_digest: `demo：${stage} 开始` },
    });
    const runningOk = running.status === 200 && running.json?.code === 0;
    if (!runningOk) stageFail += 1;
    rows.push(row(`③${stage}`, `POST …/stages/${stage} RUNNING`, running,
      runningOk ? `status=${running.json?.data?.status} attempts=${running.json?.data?.attempts}` : '',
      runningOk ? 'OK' : 'FAIL', runningOk ? '' : hintFor('service', running)));
    if (!runningOk) fail(rows, `${stage} 的阶段上报（RUNNING）失败`, ['先修上一条失败：401 见令牌核对，404 见 taskId 核对']);

    const terminal = stage === 'CLEAN'
      ? { status: 'SKIPPED', error_msg: CLEAN_SKIP_REASON, item_count: 0, elapsed_ms: 1 }
      : { status: 'DONE', item_count: 3, item_failed: 0, elapsed_ms: 5,
        input_digest: `demo：${stage} 输入`, output_digest: `demo：${stage} 输出` };
    const done = await call(args, 'POST', `/api/ai/pipeline/runs/${runId}/stages/${stage}`, {
      serviceToken: args.token,
      body: terminal,
    });
    const doneOk = done.status === 200 && done.json?.code === 0
      && done.json?.data?.status === terminal.status;
    if (!doneOk) stageFail += 1;
    rows.push(row(`③${stage}`, `POST …/stages/${stage} ${terminal.status}`, done,
      doneOk ? `status=${done.json?.data?.status} attempts=${done.json?.data?.attempts}` : '',
      doneOk ? 'OK' : 'FAIL', doneOk ? '' : hintFor('service', done)));
    if (!doneOk) fail(rows, `${stage} 的阶段上报（${terminal.status}）失败`, ['按上一条提示修正后重跑']);
  }

  // ── ⑤ 五类产物各一条摘要 ─────────────────────────────────────
  let artifactFail = 0;
  for (const type of ARTIFACT_TYPES) {
    const res = await call(args, 'POST', `/api/ai/pipeline/runs/${runId}/artifacts`, {
      serviceToken: args.token,
      body: {
        artifact_type: type,
        content: { demo: true, artifact: type, note: `流水线演示：${ARTIFACT_DESC[type]}` },
        object_key: `demo/${runId}/${type}.json`,
        char_count: 64,
        item_count: type === 'chunk_manifest' ? 5 : 3,
      },
    });
    const ok = res.status === 200 && res.json?.code === 0;
    if (!ok) artifactFail += 1;
    rows.push(row(`④产物`, `POST …/artifacts ${type}`, res,
      ok ? `artifact_type=${res.json?.data?.artifactType ?? res.json?.data?.artifact_type} rev=${res.json?.data?.artifactRev ?? res.json?.data?.artifact_rev}` : '',
      ok ? 'OK' : 'FAIL', ok ? '' : hintFor('service', res)));
    if (!ok) fail(rows, `产物 ${type} 追加失败`, ['按上一条提示修正后重跑']);
  }

  // ── ⑥ 终态 ──────────────────────────────────────────────────
  const finishBody = { status: 'DONE' };
  if (task.docId) finishBody.doc_id = task.docId;
  const finish = await call(args, 'POST', `/api/ai/pipeline/runs/${runId}/finish`, {
    serviceToken: args.token,
    body: finishBody,
  });
  const finishOk = finish.status === 200 && finish.json?.code === 0;
  rows.push(row('⑤终态', 'POST …/finish DONE', finish,
    finishOk ? `status=${finish.json?.data?.status}` : '', finishOk ? 'OK' : 'FAIL',
    finishOk ? (finish.json?.data?.note ? 'task 已 DONE（note 见响应）' : '') : hintFor('service', finish)));
  if (!finishOk) {
    fail(rows, '终态上报失败', [hintFor('service', finish)]);
  }

  // ── ⑦ 用用户 JWT 读回来核对（这才是"链路通"的证据）────────────
  const listPath = `/api/ai/runs?attachment_id=${task.attachmentId}&page=1&size=20`;
  const list = await call(args, 'GET', listPath, { jwt });
  const records = list.json?.data?.records;
  const hit = Array.isArray(records) ? records.find((r) => r.run_id === runId || r.runId === runId) : undefined;
  const listOk = list.status === 200 && list.json?.code === 0 && Boolean(hit);
  rows.push(row('⑥列表', `GET /api/ai/runs?attachment_id=${task.attachmentId}`, list,
    listOk ? `run_id=${runId} 可见` : '', listOk ? 'OK' : 'FAIL',
    listOk ? '' : hintFor('user', list)));
  if (!listOk) fail(rows, '用户侧列表里看不到这次运行', ['核对 run_id 是否为 run-<taskId>；GET /api/ai/runs 是否被 status/attachment_id 过滤掉']);

  const detail = await call(args, 'GET', `/api/ai/runs/${runId}/pipeline`, { jwt });
  const stages = Array.isArray(detail.json?.data?.stages) ? detail.json.data.stages : [];
  const artifacts = Array.isArray(detail.json?.data?.artifacts) ? detail.json.data.artifacts : [];
  const stageKeys = new Set(stages.map((s) => String(s.stageKey ?? s.stage_key ?? '').toUpperCase()));
  const missingStages = INGEST_STAGES.filter((s) => !stageKeys.has(s));
  const artifactTypes = new Set(artifacts.map((a) => a.artifactType ?? a.artifact_type));
  const missingArtifacts = ARTIFACT_TYPES.filter((t) => !artifactTypes.has(t));
  const detailOk = detail.status === 200 && detail.json?.code === 0
    && stages.length >= 5 && missingStages.length === 0 && missingArtifacts.length === 0;
  rows.push(row('⑥详情', `GET /api/ai/runs/${runId}/pipeline`, detail,
    detailOk ? `stages=${stages.length} artifacts=${artifacts.length}` : '',
    detailOk ? 'OK' : 'FAIL',
    detailOk ? '' : `缺阶段[${missingStages.join(',')}] 缺产物[${missingArtifacts.join(',')}]`));

  console.log('');
  printTable(rows);
  const failed = rows.filter((r) => r.verdict !== 'OK').length;
  if (failed === 0 && stageFail === 0 && artifactFail === 0 && detailOk) {
    console.log(`\n✓ 通过：5 个阶段（CLEAN=SKIPPED）+ 5 类产物 + 终态 DONE 都已落库，用户侧读回可见。`);
    console.log(`  run_id=${runId}；界面入口：AI 与知识库 → 解析流水线 → 详情（打开即重取全量）。`);
  } else {
    console.error(`\n✗ 有 ${failed} 项未通过（见上表）。`);
    printFailureHints(rows);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(`✗ 未预期的错误：${err?.message || err}`);
  process.exit(1);
});
