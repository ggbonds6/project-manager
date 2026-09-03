/**
 * Demo 数据种子脚本 v3（父子项目 + 子项目独立合同）
 * 业务口径（v1.4）：分了子项目后，每个子项目独立签订合同——
 * 即使承包商相同，也按子项目分别登记合同；总项目仅作汇总容器，
 * 提供公用附件/信息，不再有自身阶段流程。顶层独立项目（无子项目）仍按单项目处理。
 *
 * 走真实后端 REST API；会先清空现有项目（含子项目）再重建。
 * 用法：node scripts/seed-demo.mjs [baseUrl]   （默认 http://127.0.0.1:8080，admin/123456）
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8080';

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
const PAY_NAME = { PREPAY: '预付款', ARRIVAL: '到货款', FIRST_ACCEPT: '初验款', FINAL_ACCEPT: '终验款', WARRANTY: '质保金' };

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

/** 为合同登记一笔未付里程碑（金额按比例） */
async function addPayment(token, contractId, ownerProjectId, node, amount) {
  await api('POST', '/api/payments', {
    projectId: ownerProjectId,
    contractId,
    nodeCode: node,
    nodeName: PAY_NAME[node],
    planAmount: round2(amount),
    paidAmount: 0,
    status: 'UNPAID',
  }, token);
}

/** 创建合同并覆盖若干(子)项目 */
async function createContract(token, name, no, amount, projectIds, vendor) {
  return api('POST', '/api/contracts', {
    name,
    contractNo: no,
    vendorName: vendor,
    contractAmount: round2(amount),
    changeAmount: 0,
    scopeRemark: `覆盖 ${projectIds.length} 个子项目`,
    projectIds,
  }, token);
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

  // ============ 总项目一：1 个独立合同 + 2 个子项目共享合同 ============
  const root1 = await mkProject('市政务云平台扩容工程（总项目）', 'SW', '市大数据局', null, '2025-03-10', 7200000);
  const a = await mkProject('云资源池扩容', 'HW', '市大数据局', root1, '2025-03-20', 3200000);
  const b = await mkProject('灾备中心建设', 'HW', '市大数据局', root1, '2025-04-01', 2200000);
  const c = await mkProject('安全等保改造', 'SW', '市大数据局', root1, '2025-04-15', 1800000);

  await advanceProject(token, a, 4, { name: '安装调试', percent: 60 }, '2025-03-20');
  await advanceProject(token, b, 3, { name: '到货验收', percent: 20 }, '2025-04-01');
  await advanceProject(token, c, 2, { name: '招标采购', percent: 40 }, '2025-04-15');

  // 每个子项目独立签订合同（同一供应商也可分开登记）
  const cA = await createContract(token, '云资源池扩容合同', 'HT-2025-ROOT1-A', 3000000, [a], '浪潮云服务');
  await addPayment(token, cA, a, 'PREPAY', 900000);
  const cB = await createContract(token, '灾备中心建设合同', 'HT-2025-ROOT1-B', 1400000, [b], '华信系统集成');
  await addPayment(token, cB, b, 'PREPAY', 420000);
  const cC = await createContract(token, '安全等保改造合同', 'HT-2025-ROOT1-C', 1200000, [c], '华信系统集成');
  await addPayment(token, cC, c, 'PREPAY', 360000);

  // ============ 总项目二：5 个子项目各自独立合同（同供应商也分开签订） ============
  const root2 = await mkProject('智慧园区一体化建设（总项目）', 'HW', '市政务服务中心', null, '2025-09-01', 9800000);
  const names2 = ['楼宇自控与BA', '综合布线', '视频监控', '一卡通门禁', '信息发布与导视'];
  const types2 = ['HW', 'HW', 'HW', 'SW', 'SW'];
  const kids2 = [];
  for (let i = 0; i < names2.length; i++) {
    kids2[i] = await mkProject(names2[i], types2[i], '市政务服务中心', root2, addDays('2025-09-05', (i + 1) * 12), 1900000);
  }
  for (let i = 0; i < kids2.length; i++) {
    await advanceProject(token, kids2[i], Math.min(3, 1 + i), i === 0 ? { name: '招标采购', percent: 30 } : { name: '需求调研与规格', percent: 30 }, addDays('2025-09-05', (i + 1) * 12));
  }
  for (let i = 0; i < kids2.length; i++) {
    const vendor = i < 3 ? '太极计算机股份' : '慧眼安防工程';
    const ck = await createContract(token, `${names2[i]}合同`, `HT-2025-ROOT2-${String(i + 1).padStart(2, '0')}`, 1600000 + i * 120000, [kids2[i]], vendor);
    await addPayment(token, ck, kids2[i], 'PREPAY', round2((1600000 + i * 120000) * 0.3));
  }

  // ============ 顶层独立项目（无子项目，单项目形态） ============
  const alone = await mkProject('中小学教育信息化改造（单项目）', 'SW', '市教育局', null, '2024-06-20', 2100000);
  await advanceProject(token, alone, 7, { name: '部署上线与试运行', percent: 50 }, '2024-06-20');
  const cAlone = await createContract(token, '教育信息化改造合同', 'HT-2024-ALONE', 1980000, [alone], '中软国际信息技术');
  await addPayment(token, cAlone, alone, 'PREPAY', 594000);

  // ============ 汇总 ============
  const top = (await api('GET', '/api/projects?page=1&size=500', undefined, token)).records;
  let leaves = 0;
  for (const r of top) {
    const kids = (await api('GET', `/api/projects?parentId=${r.id}&page=1&size=100`, undefined, token)).records;
    leaves += kids.length > 0 ? kids.length : 1;
  }
  let pays = 0;
  for (const r of top) pays += (await api('GET', `/api/projects/${r.id}/payments`, undefined, token)).length;
  console.log('========================================');
  console.log(`耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  console.log(`顶层项目 ${top.length} 个，核算单元(含子项目) ${leaves} 个，付款记录 ${pays} 笔`);
  console.log('合同：每个（子）项目独立登记（同供应商也各自合同）；总项目容器无自身合同');
}

main().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
