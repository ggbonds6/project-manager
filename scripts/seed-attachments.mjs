/**
 * 附件 Mock 脚本 v2
 *
 * 三类附件，覆盖三个不同的业务归属（这是 V11 之后的重要区别）：
 *   ① 阶段附件   bizType=PROJECT_PHASE —— 流程进展各阶段的过程材料
 *   ② 合同附件   bizType=CONTRACT      —— 合同正本、法务意见书、会签表、授权委托书、履约保函…
 *   ③ 付款凭证   bizType=PAYMENT       —— 付款审批单、发票、付款凭证（报销所需）
 *
 * 生成的是**可预览的最小文件**（txt / 最小 pdf / csv），走真实上传接口。
 * 部分付款**故意少传一两类凭证**，用于验证"报销缺件提醒"功能（不是 bug）。
 *
 * 用法：node scripts/seed-attachments.mjs [baseUrl]
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8088';

async function api(method, path, body, token) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const resp = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  const json = await resp.json();
  if (!json || json.code !== 0) {
    throw new Error(`${method} ${path} -> code=${json?.code} ${json?.message}`);
  }
  return json.data;
}

// ---------- 各阶段典型文档（标题 + 扩展名） ----------
const DOCS_BY_PHASE = {
  立项申报: [
    { t: '立项批复', e: 'pdf' },
    { t: '项目可研报告', e: 'txt' },
    { t: '资金批复', e: 'pdf' },
  ],
  需求调研与规格: [
    { t: '需求规格说明书', e: 'txt' },
    { t: '需求评审记录', e: 'pdf' },
  ],
  招标采购: [
    { t: '招标文件', e: 'pdf' },
    { t: '评标记录', e: 'txt' },
    { t: '中标通知书', e: 'pdf' },
  ],
  // 合同正本等已改为挂在「合同」上（bizType=CONTRACT），此处只留过程性材料
  合同签订: [{ t: '廉洁承诺书', e: 'txt' }],
  到货验收: [
    { t: '到货签收单', e: 'pdf' },
    { t: '装箱单与设备清单', e: 'csv' },
  ],
  安装调试: [
    { t: '实施方案', e: 'pdf' },
    { t: '调试记录', e: 'txt' },
    { t: '集成测试报告', e: 'pdf' },
  ],
  设计与评审: [
    { t: '概要设计说明书', e: 'pdf' },
    { t: '数据库设计说明', e: 'txt' },
    { t: '接口文档', e: 'txt' },
  ],
  开发实现: [
    { t: '版本发布说明', e: 'txt' },
    { t: '代码仓库与分支说明', e: 'txt' },
  ],
  测试与测评: [
    { t: '系统测试报告', e: 'pdf' },
    { t: '第三方测评报告', e: 'pdf' },
    { t: '问题整改记录', e: 'txt' },
  ],
  部署上线与试运行: [
    { t: '上线申请', e: 'txt' },
    { t: '试运行记录', e: 'txt' },
    { t: '用户培训材料', e: 'pdf' },
  ],
  初验: [
    { t: '初验申请', e: 'txt' },
    { t: '初验报告', e: 'pdf' },
    { t: '遗留问题清单', e: 'csv' },
  ],
  试运行: [{ t: '试运行记录', e: 'txt' }],
  终验: [
    { t: '终验报告', e: 'pdf' },
    { t: '竣工资料移交清单', e: 'csv' },
    { t: '源代码移交清单', e: 'csv' },
  ],
  质保运维: [
    { t: '质保服务记录', e: 'txt' },
    { t: '质保期服务评价表', e: 'pdf' },
  ],
};
const FALLBACK_DOCS = [{ t: '阶段过程文档', e: 'txt' }];

/** 合同附件：按合同类型给不同组合（主合同材料最多） */
const CONTRACT_DOCS = {
  MAIN: [
    { t: '合同正本（含技术附件）', e: 'pdf', type: 'CONTRACT' },
    { t: '法务意见书', e: 'txt', type: 'LEGAL_OPINION' },
    { t: '合同会签表', e: 'txt', type: 'COUNTERSIGN' },
    { t: '授权委托书', e: 'pdf', type: 'AUTHORIZATION' },
    { t: '履约保函', e: 'pdf', type: 'PERFORMANCE_BOND' },
    { t: '中标通知书', e: 'pdf', type: 'WIN_NOTICE' },
  ],
  OTHER: [
    { t: '服务合同正本', e: 'pdf', type: 'CONTRACT' },
    { t: '法务意见书', e: 'txt', type: 'LEGAL_OPINION' },
    { t: '合同会签表', e: 'txt', type: 'COUNTERSIGN' },
  ],
};

/** 付款凭证：报销所需的典型三类 */
const PAYMENT_DOCS = [
  { t: '付款审批单', e: 'txt', type: 'PAY_APPROVAL' },
  { t: '增值税专用发票', e: 'pdf', type: 'INVOICE' },
  { t: '银行付款回单', e: 'pdf', type: 'PAY_VOUCHER' },
];

function classifyType(title) {
  const rules = [
    ['APPROVAL', /批复|立项/],
    ['FEASIBILITY', /可研/],
    ['BID_DOC', /招标文件/],
    ['EVAL_RECORD', /评标/],
    ['WIN_NOTICE', /中标/],
    ['ARRIVAL_DOC', /到货|装箱|设备清单/],
    ['ACCEPT_REPORT', /验收报告|初验|终验/],
    ['TEST_REPORT', /测试|测评/],
    ['IMPLEMENT_PLAN', /实施/],
    ['DEBUG_RECORD', /调试/],
    ['WARRANTY_DOC', /质保/],
    ['COMPLETE_DOC', /竣工|移交|培训|廉洁/],
    ['SOURCE_CODE', /源代码/],
    ['OTHER', /./],
  ];
  for (const [code, re] of rules) {
    if (re.test(title)) return code;
  }
  return 'OTHER';
}

// ---------- 简单文本文件生成 ----------
function makeTxt(ctx, docTitle) {
  const lines = [];
  lines.push(`【${docTitle}】`);
  lines.push(`项目：${ctx.project}`);
  if (ctx.phase) lines.push(`阶段：${ctx.phase}`);
  if (ctx.contract) lines.push(`合同：${ctx.contract}`);
  if (ctx.payment) lines.push(`款项：${ctx.payment}`);
  lines.push('—— 以下为演示（Mock）生成内容 ——');
  lines.push('');
  lines.push('本文件用于项目管理系统附件功能演示：展示附件在流程进展、合同管理、资金情况');
  lines.push('三处的展示、下载与预览行为。内容不代表任何真实批复、合同或报告。');
  lines.push('');
  lines.push(
    '本系统为政府信息化项目内部管理工具，覆盖立项、招标采购、合同、实施、验收、质保等全过程档案管理，并按"一笔付款一条记录"的方式留存付款过程材料。'.repeat(
      3 + (docTitle.length % 3),
    ),
  );
  lines.push('');
  lines.push(`生成时间：${new Date().toISOString().slice(0, 10)}`);
  return lines.join('\r\n');
}

function makeCsv(ctx, docTitle) {
  const rows = [
    ['序号', '文档/条目名称', '数量/金额', '备注'],
    ['1', `${docTitle}样本条目A`, '1', '演示'],
    ['2', `${docTitle}样本条目B`, '2', '演示'],
    ['3', `${ctx.project}归档材料`, ctx.phase || ctx.contract || '—', 'Mock'],
    ['4', '签字确认', '已确认', '演示'],
  ];
  return rows.map((r) => r.join(',')).join('\r\n');
}

// 最小可用 PDF（ASCII 文本内容，浏览器可直接预览）
function makePdf(lines) {
  const esc = (s) => String(s).replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const content = lines
    .map((l, i) => `BT /F1 11 Tf 60 ${730 - 28 * i} Td (${esc(l)}) Tj ET`)
    .join('\n');
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  objs.forEach((body, i) => {
    const n = i + 1;
    offsets[n] = pdf.length;
    pdf += `${n} 0 obj\n${body}\nendobj\n`;
  });
  const xrefStart = pdf.length;
  pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  for (let i = 1; i <= objs.length; i++) {
    pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF`;
  return pdf;
}

function buildContent(ext, ctx, docTitle) {
  if (ext === 'pdf') {
    const lines = [`Project Manager Demo - ${docTitle}`, `Project: ${ctx.project}`];
    if (ctx.phase) lines.push(`Phase: ${ctx.phase}`);
    if (ctx.contract) lines.push(`Contract: ${ctx.contract}`);
    if (ctx.payment) lines.push(`Payment: ${ctx.payment}`);
    lines.push('--------------------------------------------------');
    lines.push('This is a generated sample PDF for attachment preview.');
    lines.push('Date: ' + new Date().toISOString().slice(0, 10));
    return makePdf(lines);
  }
  if (ext === 'csv') {
    return makeCsv(ctx, docTitle);
  }
  return makeTxt(ctx, docTitle);
}

/** 统一上传入口 */
async function uploadFile(token, spec) {
  const fileName = `${spec.title}.${spec.ext}`;
  const content = buildContent(spec.ext, spec.ctx, spec.title);
  const fd = new FormData();
  fd.append('file', new Blob([content], { type: 'application/octet-stream' }), fileName);
  fd.append('projectId', String(spec.projectId));
  fd.append('bizType', spec.bizType);
  fd.append('bizId', String(spec.bizId));
  if (spec.attachType) fd.append('attachType', spec.attachType);
  await api('POST', '/api/attachments/upload', fd, token);
}

const nameCache = new Map();

/** 简易并发池 */
async function pool(items, worker, limit) {
  let i = 0;
  const results = [];
  async function run() {
    while (i < items.length) {
      const idx = i++;
      try {
        results[idx] = await worker(items[idx]);
      } catch (e) {
        results[idx] = e.message || String(e);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
  return results;
}

async function main() {
  const start = Date.now();
  const login = await api('POST', '/api/auth/login', { account: 'admin', password: '123456' });
  const token = login.token;
  const list = await api('GET', '/api/projects?page=1&size=200', undefined, token);
  // 核算单元 = 子项目；无子项目的顶层项目本身作为核算单元
  const units = [];
  for (const t of list.records) {
    const kids = (await api('GET', `/api/projects?page=1&size=300&parentId=${t.id}`, undefined, token)).records;
    if (kids.length) units.push(...kids);
    else units.push(t);
  }
  console.log(`共 ${units.length} 个核算单元，开始补齐附件（阶段 / 合同 / 付款三类）...`);

  let totalFiles = 0;
  let wiped = 0;
  let skippedVoucher = 0;

  for (const p of units) {
    nameCache.set(p.id, p.name);
    const detail = await api('GET', `/api/projects/${p.id}`, undefined, token);
    const existing = await api('GET', `/api/projects/${p.id}/attachments`, undefined, token);

    // 清掉该项目已有附件，保证可重复执行
    for (const a of existing) {
      await api('DELETE', `/api/attachments/${a.id}`, undefined, token);
      wiped++;
    }

    const tasks = [];

    // ── ① 阶段附件 ──
    for (const ph of detail.phases) {
      const docs = DOCS_BY_PHASE[ph.phaseName] || FALLBACK_DOCS;
      for (const spec of docs) {
        tasks.push({
          projectId: p.id,
          bizType: 'PROJECT_PHASE',
          bizId: ph.id,
          attachType: classifyType(spec.t),
          title: spec.t,
          ext: spec.e,
          ctx: { project: p.name, phase: ph.phaseName },
        });
      }
    }

    // ── ② 合同附件 ──
    const contracts = await api('GET', `/api/projects/${p.id}/contracts`, undefined, token);
    for (const c of contracts) {
      const docs = c.contractType === 'MAIN' ? CONTRACT_DOCS.MAIN : CONTRACT_DOCS.OTHER;
      for (const spec of docs) {
        tasks.push({
          projectId: p.id,
          bizType: 'CONTRACT',
          bizId: c.id,
          attachType: spec.type,
          title: spec.t,
          ext: spec.e,
          ctx: { project: p.name, contract: c.name },
          bucket: 'contract',
        });
      }
    }

    // ── ③ 付款凭证（只为"已付"的款上传；部分故意少传，用于验证缺件提醒）──
    const payments = await api('GET', `/api/projects/${p.id}/payments`, undefined, token);
    for (const pay of payments) {
      if (pay.status === 'UNPAID' || !(pay.paidAmount > 0)) continue;
      // 每 5 笔里有 2 笔故意缺一类材料（缺发票 / 缺审批单），模拟真实报销缺件
      const missingIdx = pay.id % 5 === 0 ? 1 : pay.id % 5 === 3 ? 0 : -1;
      if (missingIdx >= 0) skippedVoucher++;
      PAYMENT_DOCS.forEach((spec, i) => {
        if (i === missingIdx) return;
        tasks.push({
          projectId: p.id,
          bizType: 'PAYMENT',
          bizId: pay.id,
          attachType: spec.type,
          title: spec.t,
          ext: spec.e,
          ctx: {
            project: p.name,
            payment: `${pay.nodeName || pay.nodeCode}（实付 ${pay.paidAmount} 元）`,
          },
          bucket: 'payment',
        });
      });
    }

    const errs = await pool(tasks, (t) => uploadFile(token, t), 10);
    const failed = errs.filter(Boolean).length;
    totalFiles += tasks.length - failed;
    const nPhase = tasks.filter((t) => t.bizType === 'PROJECT_PHASE').length;
    const nContract = tasks.filter((t) => t.bucket === 'contract').length;
    const nPayment = tasks.filter((t) => t.bucket === 'payment').length;
    if (failed) {
      console.log(`✗ ${p.name}：失败 ${failed}/${tasks.length}（首个错误：${errs.find(Boolean)}）`);
    } else {
      console.log(
        `✓ ${p.name}：阶段 ${nPhase} · 合同 ${nContract} · 付款凭证 ${nPayment}`,
      );
    }
  }

  // ── 汇总 ──
  let allAtts = 0;
  const byBiz = { PROJECT_PHASE: 0, CONTRACT: 0, PAYMENT: 0, PROJECT: 0 };
  for (const p of units) {
    const atts = await api('GET', `/api/projects/${p.id}/attachments`, undefined, token);
    allAtts += atts.length;
    for (const a of atts) byBiz[a.bizType] = (byBiz[a.bizType] || 0) + 1;
  }
  console.log('========================================');
  console.log(`耗时 ${((Date.now() - start) / 1000).toFixed(1)}s，清除旧附件 ${wiped} 个，新增 ${totalFiles} 个`);
  console.log(
    `其中：阶段附件 ${byBiz.PROJECT_PHASE} · 合同附件 ${byBiz.CONTRACT} · 付款凭证 ${byBiz.PAYMENT}` +
      (byBiz.PROJECT ? ` · 项目级 ${byBiz.PROJECT}` : ''),
  );
  console.log(`故意少传凭证的付款记录：${skippedVoucher} 笔（用于验证「报销缺件提醒」）`);
  console.log(`库内项目附件合计 ${allAtts} 个`);
}

main().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
