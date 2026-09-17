/**
 * Demo 数据种子脚本 v4（父子项目 + 多合同 + 付款过程留痕 + 项目分工）
 *
 * 业务口径：
 *   * 一个（子）项目可签**多份合同**——施工主合同、监理服务、第三方测评、
 *     预算编制/方案评估各有独立合同（即使承包商相同也分开登记）；
 *   * 资金情况**以付款为主线**：一条记录 = 一笔款，含付款方式、经办人、
 *     发票号、凭证号、收款账户快照；
 *   * 项目分工按"模块 → 子模块"录入负责方、甲乙负责人、三个计划节点与进度。
 *
 * 走真实后端 REST API；会先清空现有项目（含子项目）再重建，可反复执行。
 * 用法：node scripts/seed-demo.mjs [baseUrl]   （默认 http://127.0.0.1:8088，admin/123456）
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8088';

async function api(method, path, body, token) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const resp = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  const json = await resp.json();
  if (!json || json.code !== 0) throw new Error(`${method} ${path} -> code=${json?.code} ${json?.message}`);
  return json.data;
}

const DAY = 24 * 3600 * 1000;
const addDays = (base, n) => new Date(new Date(base).getTime() + n * DAY).toISOString().slice(0, 10);
const round2 = (n) => Math.round(n * 100) / 100;

const PAY_NAME = {
  PREPAY: '预付款',
  ARRIVAL: '到货款',
  FIRST_ACCEPT: '初验款',
  FINAL_ACCEPT: '终验款',
  WARRANTY: '质保金',
};

/** 供应商档案：联系人 + 收款账户 + 乙方项目经理（多份合同可能同一家） */
const VENDORS = {
  浪潮云服务: {
    contact: '刘伟 / 0755-83001188',
    bank: '中国银行深圳福田支行',
    account: '7441029388770011234',
    pm: '刘伟（乙方项目经理）',
  },
  华信系统集成: {
    contact: '陈志强 / 0755-82556600',
    bank: '中国建设银行深圳科技园支行',
    account: '4420012345678901234',
    pm: '陈志强（乙方项目经理）',
  },
  太极计算机股份: {
    contact: '王海涛 / 010-58902200',
    bank: '中国工商银行北京海淀支行',
    account: '0200004509088123456',
    pm: '王海涛（乙方项目经理）',
  },
  慧眼安防工程: {
    contact: '李国栋 / 0755-26998877',
    bank: '招商银行深圳分行高新园支行',
    account: '755903881234501',
    pm: '李国栋（乙方项目经理）',
  },
  中软国际信息技术: {
    contact: '周敏 / 010-82861000',
    bank: '交通银行北京中关村支行',
    account: '110060210018001234567',
    pm: '周敏（乙方项目经理）',
  },
  正衡工程咨询: {
    contact: '赵鑫 / 0755-83227700',
    bank: '中信银行深圳分行营业部',
    account: '7441810182600123456',
    pm: '赵鑫（乙方项目负责人）',
  },
  赛宝测评中心: {
    contact: '孙立 / 020-87237000',
    bank: '中国银行广州天河支行',
    account: '688701234567890123',
    pm: '孙立（测评项目负责人）',
  },
};

/** 甲方经办人轮换，让数据看起来更真实 */
const OWNER_HANDLERS = ['张建国', '李慧敏', '王振宇', '陈晓峰', '刘晓芸'];
const OWNER_PMS = ['马晓东（甲方项目负责人）', '何丽娟（甲方项目负责人）', '徐立新（甲方项目负责人）'];

/** 项目分工模板：按项目类型给一套"像真的"模块/子模块 */
const DIVISION_TEMPLATES = {
  HW: [
    { name: '需求确认与深化设计', ownerSide: 'BOTH', subs: ['现场勘测与点位确认', '深化设计图纸评审'] },
    { name: '设备到货与验收', ownerSide: 'VENDOR', subs: ['设备到货签收', '开箱质检与序列号登记'] },
    { name: '安装与调试', ownerSide: 'VENDOR', subs: ['设备上架与布线', '系统联调'] },
    { name: '培训与移交', ownerSide: 'BOTH', subs: ['管理员操作培训', '随机备件与资料移交'] },
  ],
  SW: [
    { name: '需求调研与规格', ownerSide: 'BOTH', subs: ['业务需求调研', '需求规格评审'] },
    { name: '设计与评审', ownerSide: 'VENDOR', subs: ['概要设计', '数据库设计', '接口设计'] },
    { name: '开发实现', ownerSide: 'VENDOR', subs: ['功能开发', '单元测试与代码走查'] },
    { name: '测试与测评', ownerSide: 'BOTH', subs: ['系统测试', '第三方安全测评', '问题整改闭环'] },
    { name: '部署上线与试运行', ownerSide: 'VENDOR', subs: ['生产环境部署', '试运行保障'] },
  ],
};

/** 把某项目的阶段推进：前 done 个完成，第 done+1(按名称) 进行中 */
async function advanceProject(token, id, done, ip, base) {
  const det = await api('GET', `/api/projects/${id}`, undefined, token);
  const byName = new Map(det.phases.map((p) => [p.phaseName, p]));
  let completed = 0;
  for (const ph of det.phases) {
    if (completed < done && (ph.status === 'NOT_STARTED' || ph.status === 'IN_PROGRESS')) {
      const planStart = addDays(base, (ph.sortNo - 1) * 45);
      await api('PUT', `/api/projects/${id}/phases/${ph.id}`, {
        status: 'DONE',
        percent: 100,
        planStartDate: planStart,
        planFinishDate: addDays(planStart, 45),
        actualStartDate: planStart,
        actualFinishDate: addDays(planStart, 45),
        managerUserId: 2,
        note: '演示：本阶段已完成，关键材料已归档。',
      }, token);
      completed++;
    }
  }
  if (ip && ip.name) {
    const ph = byName.get(ip.name);
    if (ph && ph.status === 'NOT_STARTED') {
      const planStart = addDays(base, (ph.sortNo - 1) * 45);
      await api('PUT', `/api/projects/${id}/phases/${ph.id}`, {
        status: 'IN_PROGRESS',
        percent: ip.percent,
        planStartDate: planStart,
        planFinishDate: addDays(planStart, 45),
        actualStartDate: planStart,
        managerUserId: 2,
        note: `演示：当前推进中（${ip.percent}%）。`,
      }, token);
    }
  }
}

/** 创建合同（含政府合同常见字段），返回合同 id */
async function createContract(token, spec) {
  const v = VENDORS[spec.vendor] || VENDORS['华信系统集成'];
  return api('POST', '/api/contracts', {
    name: spec.name,
    contractNo: spec.no,
    contractType: spec.type,
    contractStatus: spec.status || 'ACTIVE',
    partyA: spec.partyA,
    vendorName: spec.vendor,
    vendorContact: v.contact,
    bidType: spec.bidType || 'OPEN',
    bidAmount: round2(spec.amount * 1.02),
    contractAmount: round2(spec.amount),
    changeAmount: spec.change || 0,
    settleAmount: spec.settle || undefined,
    warrantyAmount: round2(spec.amount * 0.03),
    warrantyMonths: spec.type === 'MAIN' ? spec.months || 24 : 12,
    signDate: spec.signDate,
    effectiveDate: spec.signDate,
    startDate: spec.signDate,
    endDate: spec.endDate,
    payeeName: spec.vendor,
    payeeBank: v.bank,
    payeeAccount: v.account,
    acceptanceStandard:
      spec.type === 'MAIN'
        ? '按招标文件、投标响应文件及国家现行相关标准组织验收；由采购人组织专家验收，出具验收报告后视为通过。'
        : '按合同约定的服务内容与交付成果验收，出具书面验收意见后视为通过。',
    scopeRemark: spec.scopeRemark,
    remark: spec.remark,
    projectIds: spec.projectIds,
  }, token);
}

/** 登记付款节点（含付款过程留痕） */
async function addPayment(token, spec) {
  const v = VENDORS[spec.vendor] || VENDORS['华信系统集成'];
  const paid = spec.status === 'UNPAID' ? 0 : round2(spec.paidAmount || 0);
  const st = spec.status || (paid > 0 ? 'PAID' : 'UNPAID');
  await api('POST', '/api/payments', {
    projectId: spec.projectId,
    contractId: spec.contractId,
    nodeCode: spec.node,
    nodeName: spec.nodeName || PAY_NAME[spec.node],
    conditionDesc: spec.condition,
    planAmount: round2(spec.planAmount),
    planDate: spec.planDate,
    paidAmount: paid,
    paidDate: paid > 0 ? spec.paidDate : undefined,
    status: st,
    remark: spec.remark,
    // ── 付款过程留痕 ──
    payMethod: paid > 0 ? spec.payMethod || 'TRANSFER' : undefined,
    handler: paid > 0 ? spec.handler : undefined,
    invoiceNo: paid > 0 ? spec.invoiceNo : undefined,
    voucherNo: paid > 0 ? spec.voucherNo : undefined,
    payeeName: paid > 0 ? spec.vendor : undefined,
    payeeBank: paid > 0 ? v.bank : undefined,
    payeeAccount: paid > 0 ? v.account : undefined,
  }, token);
}

/**
 * 主合同的全套付款里程碑。
 * level：已推进到第几档（0=未付款，1=已付预付款，…，4=前四档已付，5=全部付清）
 * partial=true 时第 level 档只付 60%，用于验证"与计划差额"提示。
 */
async function seedMainPayments(token, ctx) {
  const { contractId, projectId, amount, vendor, start, level, handler, invoiceBase, voucherBase } = ctx;
  const nodes = [
    { node: 'PREPAY', pct: 0.3, cond: '合同生效并收到合规发票后 15 个工作日内', offset: 15 },
    { node: 'ARRIVAL', pct: 0.4, cond: '设备到货验收合格，签署到货签收单后 20 个工作日内', offset: 120 },
    { node: 'FIRST_ACCEPT', pct: 0.2, cond: '初验合格并出具初验报告后 30 个工作日内', offset: 240 },
    { node: 'FINAL_ACCEPT', pct: 0.07, cond: '终验合格、资料移交完整后 30 个工作日内', offset: 360 },
    { node: 'WARRANTY', pct: 0.03, cond: '质保期满且无未整改问题后 30 个工作日内', offset: 720 },
  ];
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i];
    const plan = round2(amount * n.pct);
    const paid = i < level ? plan : i === level && ctx.partial ? round2(plan * 0.6) : 0;
    await addPayment(token, {
      projectId,
      contractId,
      node: n.node,
      condition: n.cond,
      planAmount: plan,
      planDate: addDays(start, n.offset),
      paidAmount: paid,
      paidDate: addDays(start, n.offset + 5),
      status: paid >= plan && paid > 0 ? 'PAID' : paid > 0 ? 'PART' : 'UNPAID',
      vendor,
      handler,
      invoiceNo: `0443${invoiceBase}${String(i + 1).padStart(2, '0')}`,
      voucherNo: `JZ-${voucherBase}-${String(i + 1).padStart(3, '0')}`,
      payMethod: n.node === 'WARRANTY' ? 'OFFSET' : 'TRANSFER',
      remark: n.node === 'WARRANTY' ? '质保金，质保期满且无未整改问题后支付。' : undefined,
    });
  }
}

/** 服务类合同（监理/测评/咨询）：节点少 */
async function seedServicePayments(token, ctx) {
  const { contractId, projectId, amount, vendor, start, level, handler, invoiceBase, voucherBase } = ctx;
  const nodes = [
    { node: 'PREPAY', pct: 0.5, cond: '服务合同生效后 15 个工作日内', offset: 20 },
    { node: 'FINAL_ACCEPT', pct: 0.5, cond: '提交全部服务成果并验收通过后 30 个工作日内', offset: 300 },
  ];
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i];
    const plan = round2(amount * n.pct);
    const paid = i < level ? plan : 0;
    await addPayment(token, {
      projectId,
      contractId,
      node: n.node,
      condition: n.cond,
      planAmount: plan,
      planDate: addDays(start, n.offset),
      paidAmount: paid,
      paidDate: addDays(start, n.offset + 3),
      status: paid > 0 ? 'PAID' : 'UNPAID',
      vendor,
      handler,
      invoiceNo: paid > 0 ? `0443${invoiceBase}9${i}` : undefined,
      voucherNo: paid > 0 ? `JZ-${voucherBase}-S${i + 1}` : undefined,
    });
  }
}

/** 项目分工：按模板生成 模块 → 子模块，进度随阶段推进程度变化 */
async function seedDivisions(token, projectId, type, level, seed) {
  const tpl = DIVISION_TEMPLATES[type] || DIVISION_TEMPLATES.SW;
  const vendorPm = seed.pm;
  const ownerPm = OWNER_PMS[seed.idx % OWNER_PMS.length];
  const base = seed.start;
  let sort = 0;
  for (let i = 0; i < tpl.length; i++) {
    const m = tpl[i];
    const moduleDone = i < level;
    const moduleDoing = i === level;
    const progress = moduleDone ? 100 : moduleDoing ? 45 : 0;
    const status = moduleDone ? 'DONE' : moduleDoing ? 'DOING' : 'TODO';
    const planDev = addDays(base, 60 + i * 50);
    const planTest = addDays(base, 85 + i * 50);
    const planOnline = addDays(base, 100 + i * 50);
    const parentId = await api('POST', '/api/divisions', {
      projectId,
      parentId: null,
      name: m.name,
      ownerSide: m.ownerSide,
      ownerName: m.ownerSide === 'VENDOR' ? undefined : ownerPm,
      vendorOwner: m.ownerSide === 'OWNER' ? undefined : vendorPm,
      planDevDate: planDev,
      planTestDate: planTest,
      planOnlineDate: planOnline,
      progress,
      status,
      remark: moduleDoing ? '演示：当前推进中，甲方配合事项见子模块备注。' : undefined,
      sortNo: ++sort,
    }, token);

    for (let k = 0; k < m.subs.length; k++) {
      const sub = m.subs[k];
      const subProgress = moduleDone ? 100 : moduleDoing ? Math.max(10, 60 - k * 15) : 0;
      const subStatus = moduleDone ? 'DONE' : moduleDoing ? (k === m.subs.length - 1 ? 'RISK' : 'DOING') : 'TODO';
      await api('POST', '/api/divisions', {
        projectId,
        parentId,
        name: sub,
        ownerSide: k % 2 === 0 ? 'VENDOR' : 'BOTH',
        ownerName: k % 2 === 0 ? undefined : ownerPm,
        vendorOwner: vendorPm,
        planDevDate: planDev,
        planTestDate: planTest,
        planOnlineDate: planOnline,
        progress: subProgress,
        status: subStatus,
        remark: subStatus === 'RISK' ? '演示：依赖甲方提供的基础数据尚未到位，存在延期风险。' : undefined,
        sortNo: k + 1,
      }, token);
    }
  }
}

async function wipeAllProjects(token) {
  const top = (await api('GET', '/api/projects?page=1&size=500', undefined, token)).records;
  for (const root of top) {
    const kids = (await api('GET', `/api/projects?page=1&size=500&parentId=${root.id}`, undefined, token)).records;
    for (const k of kids) await api('DELETE', `/api/projects/${k.id}`, undefined, token);
    await api('DELETE', `/api/projects/${root.id}`, undefined, token);
  }
  // 兜底孤儿
  const again = (await api('GET', '/api/projects?page=1&size=500', undefined, token)).records;
  for (const r of again) await api('DELETE', `/api/projects/${r.id}`, undefined, token);
}

/** 一个核算单元（子项目/独立项目）的完整数据：多合同 + 付款 + 分工 */
async function seedUnit(token, unit) {
  const { id, name, type, vendor, amount, start, end, level, idx, partyA } = unit;
  const v = VENDORS[vendor] || VENDORS['华信系统集成'];
  const handler = OWNER_HANDLERS[idx % OWNER_HANDLERS.length];
  const mainAmount = round2(amount * 0.88);
  const superviseAmount = round2(amount * 0.025);
  const testAmount = round2(amount * 0.035);
  let count = 0;

  // ① 施工主合同
  const mainId = await createContract(token, {
    name: `${name}施工合同`,
    no: `HT-${start.slice(0, 4)}-${String(idx + 1).padStart(3, '0')}-MAIN`,
    type: 'MAIN',
    partyA,
    vendor,
    amount: mainAmount,
    months: 24,
    signDate: addDays(start, 20),
    endDate: end,
    change: idx % 3 === 0 ? round2(mainAmount * 0.02) : 0,
    settle: level >= 4 ? mainAmount : undefined,
    scopeRemark: `覆盖「${name}」全部建设内容`,
    projectIds: [id],
  });
  count++;
  await seedMainPayments(token, {
    contractId: mainId,
    projectId: id,
    amount: mainAmount,
    vendor,
    start,
    level,
    partial: idx % 4 === 1, // 个别项目出现"部分支付"，用于验证差额提示
    handler,
    invoiceBase: String(idx + 1).padStart(2, '0'),
    voucherBase: `2026-${String(idx + 1).padStart(2, '0')}`,
  });

  // ② 监理服务合同
  const supId = await createContract(token, {
    name: `${name}监理服务合同`,
    no: `HT-${start.slice(0, 4)}-${String(idx + 1).padStart(3, '0')}-SUP`,
    type: 'SUPERVISE',
    partyA,
    vendor: '正衡工程咨询',
    amount: superviseAmount,
    signDate: addDays(start, 25),
    endDate: end,
    scopeRemark: '施工阶段全过程监理',
    projectIds: [id],
  });
  count++;
  await seedServicePayments(token, {
    contractId: supId,
    projectId: id,
    amount: superviseAmount,
    vendor: '正衡工程咨询',
    start,
    level: level >= 2 ? 1 : 0,
    handler,
    invoiceBase: `S${idx + 1}`,
    voucherBase: `2026-S${String(idx + 1).padStart(2, '0')}`,
  });

  // ③ 第三方测评合同（软件类项目才有）
  if (type === 'SW') {
    const testId = await createContract(token, {
      name: `${name}第三方测评服务合同`,
      no: `HT-${start.slice(0, 4)}-${String(idx + 1).padStart(3, '0')}-TEST`,
      type: 'TEST',
      partyA,
      vendor: '赛宝测评中心',
      amount: testAmount,
      signDate: addDays(start, 30),
      endDate: end,
      scopeRemark: '系统功能与安全测评',
      projectIds: [id],
    });
    count++;
    await seedServicePayments(token, {
      contractId: testId,
      projectId: id,
      amount: testAmount,
      vendor: '赛宝测评中心',
      start,
      level: level >= 3 ? 1 : 0,
      handler,
      invoiceBase: `T${idx + 1}`,
      voucherBase: `2026-T${String(idx + 1).padStart(2, '0')}`,
    });
  }

  // ④ 预算编制咨询合同（部分项目才有）
  if (idx % 3 === 0) {
    const budgetAmount = round2(amount * 0.012);
    const budId = await createContract(token, {
      name: `${name}预算编制咨询服务合同`,
      no: `HT-${start.slice(0, 4)}-${String(idx + 1).padStart(3, '0')}-BUD`,
      type: 'BUDGET',
      partyA,
      vendor: '正衡工程咨询',
      amount: budgetAmount,
      signDate: addDays(start, 5),
      endDate: addDays(start, 90),
      scopeRemark: '项目预算编制与审核配合',
      projectIds: [id],
    });
    count++;
    await seedServicePayments(token, {
      contractId: budId,
      projectId: id,
      amount: budgetAmount,
      vendor: '正衡工程咨询',
      start,
      level: 1,
      handler,
      invoiceBase: `B${idx + 1}`,
      voucherBase: `2026-B${String(idx + 1).padStart(2, '0')}`,
    });
  }

  // 项目分工
  await seedDivisions(token, id, type, level, { pm: v.pm, idx, start });

  return count;
}

async function main() {
  const t0 = Date.now();
  const login = await api('POST', '/api/auth/login', { account: 'admin', password: '123456' });
  const token = login.token;

  console.log('清空旧演示项目（含子项目）...');
  await wipeAllProjects(token);

  const mkProject = async (name, type, owner, parentId, approve, budget) =>
    api('POST', '/api/projects', {
      name, type, ownerUnit: owner, managerUserId: 2, parentId: parentId ?? null,
      approveDate: approve, planStartDate: approve, planFinishDate: addDays(approve, 420),
      budgetAmount: budget, contentSummary: `演示：${name}。`, projectSource: 'UPPER',
    }, token);

  let unitIdx = 0;

  // ============ 总项目一：市政务云平台扩容工程 ============
  const root1 = await mkProject('市政务云平台扩容工程（总项目）', 'SW', '市大数据局', null, '2025-03-10', 7200000);
  const partyA1 = '市大数据局';
  const kids1 = [
    { name: '云资源池扩容', type: 'HW', vendor: '浪潮云服务', budget: 3200000, approve: '2025-03-20', level: 3 },
    { name: '灾备中心建设', type: 'HW', vendor: '华信系统集成', budget: 2200000, approve: '2025-04-01', level: 2 },
    { name: '安全等保改造', type: 'SW', vendor: '华信系统集成', budget: 1800000, approve: '2025-04-15', level: 1 },
  ];
  for (const k of kids1) {
    const id = await mkProject(k.name, k.type, partyA1, root1, k.approve, k.budget);
    const ipByType = k.type === 'HW' ? { name: '安装调试', percent: 60 } : { name: '开发实现', percent: 55 };
    await advanceProject(token, id, Math.min(4, k.level + 1), ipByType, k.approve);
    await seedUnit(token, {
      id, name: k.name, type: k.type, vendor: k.vendor, amount: k.budget,
      start: k.approve, end: addDays(k.approve, 420), level: k.level, idx: unitIdx++, partyA: partyA1,
    });
  }

  // ============ 总项目二：智慧园区一体化建设 ============
  const root2 = await mkProject('智慧园区一体化建设（总项目）', 'HW', '市政务服务中心', null, '2025-09-01', 9800000);
  const partyA2 = '市政务服务中心';
  const names2 = ['楼宇自控与BA', '综合布线', '视频监控', '一卡通门禁', '信息发布与导视'];
  const types2 = ['HW', 'HW', 'HW', 'SW', 'SW'];
  for (let i = 0; i < names2.length; i++) {
    const approve = addDays('2025-09-05', (i + 1) * 12);
    const budget = 1900000 + i * 50000;
    const id = await mkProject(names2[i], types2[i], partyA2, root2, approve, budget);
    const level = Math.min(4, 1 + (i % 3));
    const ip = i === 0 ? { name: '招标采购', percent: 30 } : { name: '需求调研与规格', percent: 30 };
    await advanceProject(token, id, Math.min(4, level + 1), ip, approve);
    await seedUnit(token, {
      id,
      name: names2[i],
      type: types2[i],
      vendor: i < 3 ? '太极计算机股份' : '慧眼安防工程',
      amount: budget,
      start: approve,
      end: addDays(approve, 420),
      level,
      idx: unitIdx++,
      partyA: partyA2,
    });
  }

  // ============ 顶层独立项目（无子项目，单项目形态） ============
  const alone = await mkProject('中小学教育信息化改造（单项目）', 'SW', '市教育局', null, '2024-06-20', 2100000);
  await advanceProject(token, alone, 7, { name: '部署上线与试运行', percent: 50 }, '2024-06-20');
  await seedUnit(token, {
    id: alone,
    name: '中小学教育信息化改造',
    type: 'SW',
    vendor: '中软国际信息技术',
    amount: 2100000,
    start: '2024-06-20',
    end: '2025-08-20',
    level: 4,
    idx: unitIdx++,
    partyA: '市教育局',
  });

  // ============ 汇总 ============
  const top = (await api('GET', '/api/projects?page=1&size=500', undefined, token)).records;
  const units = [];
  for (const r of top) {
    const kids = (await api('GET', `/api/projects?parentId=${r.id}&page=1&size=100`, undefined, token)).records;
    units.push(...(kids.length ? kids : [r]));
  }
  let pays = 0;
  let divs = 0;
  let contracts = 0;
  let paidSum = 0;
  for (const u of units) {
    const ps = await api('GET', `/api/projects/${u.id}/payments`, undefined, token);
    pays += ps.length;
    paidSum += ps.reduce((s, p) => s + (p.paidAmount || 0), 0);
    divs += (await api('GET', `/api/projects/${u.id}/divisions`, undefined, token)).length;
    contracts += (await api('GET', `/api/projects/${u.id}/contracts`, undefined, token)).length;
  }
  console.log('========================================');
  console.log(`耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  console.log(`顶层项目 ${top.length} 个，核算单元(含子项目) ${units.length} 个`);
  console.log(`合同 ${contracts} 份（主合同 + 监理 + 第三方测评 + 预算编制）`);
  console.log(`付款记录 ${pays} 笔，累计已付 ${paidSum.toLocaleString('zh-CN')} 元；项目分工 ${divs} 条`);
  console.log('下一步：node scripts/seed-attachments.mjs  （补齐合同附件与付款凭证）');
}

main().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
