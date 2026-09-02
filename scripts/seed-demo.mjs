/**
 * Demo 数据种子脚本
 * 通过后端 REST API 向 project_manager 灌入演示项目（项目+阶段推进+付款+附件+日志）。
 * 用法：node scripts/seed-demo.mjs [baseUrl]
 * 默认 baseUrl = http://127.0.0.1:8080
 * 账号：admin / 123456
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8080';
const TOKEN = null;

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

const DAY = 24 * 3600 * 1000;
const addDays = (base, days) => {
  const d = new Date(base.getTime() + days * DAY);
  return d.toISOString().slice(0, 10);
};
const round2 = (n) => Math.round(n * 100) / 100;

// 里程碑付款默认比例（与设计稿 §5.4 示例近似）
const PAY_RATIO = { PREPAY: 0.3, ARRIVAL: 0.3, FIRST_ACCEPT: 0.2, FINAL_ACCEPT: 0.15, WARRANTY: 0.05 };
const PAY_NAME = { PREPAY: '预付款', ARRIVAL: '到货款', FIRST_ACCEPT: '初验款', FINAL_ACCEPT: '终验款', WARRANTY: '质保金' };

// 各阶段的关键结果字段（按阶段名注入，增强真实感）
const RESULT_BY_PHASE = {
  立项申报: (i) => ({ 批复文号: `X发改〔20${(20 + i) % 100}〕${String(12 + i * 7).padStart(2, '0')}号`, 批复日期: addDays(new Date(), -600 + i * 20) }),
  招标采购: (i) => ({ 招标方式: i % 2 ? '公开招标' : '竞争性磋商', 中标供应商: '—', 中标日期: addDays(new Date(), -500 + i * 15) }),
  合同签订: (i) => ({ 合同编号: `HT-202${i % 10}-${String(100 + i * 3)}`, 签约日期: addDays(new Date(), -430 + i * 15) }),
  到货验收: () => ({ 签收结论: '设备与清单一致，验收合格', 到货日期: addDays(new Date(), -300) }),
  初验: () => ({ 初验结论: '通过', 遗留问题: '无重大遗留，个别优化项限期整改' }),
  终验: () => ({ 终验结论: '通过', 竣工资料移交: '已完成' }),
};

const VENDORS = ['华信系统集成有限公司', '中软国际信息技术', '浪潮云服务有限公司', '蓝桥软件科技', '慧眼安防工程', '神州数码控股', '太极计算机股份', '天翼云科技', '东方国信', '启明星辰信息'];
const OWNERS = ['市大数据局', '市政务服务中心', '市教育局', '市卫健委', '市公安局'];

// 场景：done=已完成阶段名；ip=进行中[{name,percent}]；projectStatus 可选
const SCENES = [
  { name: '市政务云平台扩容（一期）', type: 'HW', owner: OWNERS[0], vendor: VENDORS[0], approve: '2025-03-10', budget: 5200000, contract: 4980000, year: 2025,
    done: ['立项申报', '招标采购', '合同签订', '到货验收', '安装调试', '初验', '试运行', '终验'], ip: [{ name: '质保运维', percent: 50 }], payPaidNodes: ['PREPAY', 'ARRIVAL', 'FIRST_ACCEPT', 'FINAL_ACCEPT'], payPlanNode: 'WARRANTY' },
  { name: '市直部门高清视频会议系统改造', type: 'HW', owner: OWNERS[1], vendor: VENDORS[3], approve: '2024-10-20', budget: 1260000, contract: 1198000, year: 2024,
    done: ['立项申报', '招标采购', '合同签订', '到货验收'], ip: [{ name: '安装调试', percent: 70 }], payPaidNodes: ['PREPAY', 'ARRIVAL'], payPlanNode: 'FIRST_ACCEPT' },
  { name: 'XX 局国产化办公终端替换项目', type: 'HW', owner: OWNERS[2], vendor: VENDORS[1], approve: '2026-01-15', budget: 860000, contract: 812000, year: 2026,
    done: ['立项申报'], ip: [{ name: '招标采购', percent: 30 }], payPaidNodes: [], payPlanNode: 'PREPAY', projectStatus: 'RUN' },
  { name: '一体化网络安全防护加固项目', type: 'HW', owner: OWNERS[3], vendor: VENDORS[8], approve: '2025-08-05', budget: 2400000, contract: 2310000, year: 2025,
    done: ['立项申报', '招标采购', '合同签订'], ip: [{ name: '到货验收', percent: 20 }], payPaidNodes: ['PREPAY'], payPlanNode: 'ARRIVAL', projectStatus: 'PAUSE' },
  { name: '数据机房动力环境监控升级', type: 'HW', owner: OWNERS[4], vendor: VENDORS[4], approve: '2025-05-12', budget: 780000, contract: 742000, year: 2025,
    done: ['立项申报'], ip: [], projectStatus: 'STOP', remark: '因机房改造计划调整中止，后续另行启动', payPaidNodes: [], payPlanNode: null },
  { name: '政务服务"一网通办"平台（二期）', type: 'SW', owner: OWNERS[1], vendor: VENDORS[2], approve: '2025-04-18', budget: 6800000, contract: 6500000, year: 2025,
    done: ['立项申报', '需求调研与规格', '招标采购', '合同签订', '设计与评审', '开发实现', '测试与测评'], ip: [{ name: '部署上线与试运行', percent: 60 }], payPaidNodes: ['PREPAY', 'ARRIVAL'], payPlanNode: 'FIRST_ACCEPT' },
  { name: '基层治理综合信息平台建设', type: 'SW', owner: OWNERS[0], vendor: VENDORS[5], approve: '2025-11-03', budget: 3900000, contract: 3720000, year: 2025,
    done: ['立项申报', '需求调研与规格', '招标采购'], ip: [{ name: '合同签订', percent: 60 }], payPaidNodes: [], payPlanNode: 'PREPAY' },
  { name: '智慧教育云课堂服务平台', type: 'SW', owner: OWNERS[2], vendor: VENDORS[6], approve: '2026-02-10', budget: 1950000, contract: 1880000, year: 2026,
    done: ['立项申报'], ip: [{ name: '需求调研与规格', percent: 25 }], payPaidNodes: [], payPlanNode: null },
  { name: '医院信息系统(HIS)升级改造项目', type: 'SW', owner: OWNERS[3], vendor: VENDORS[7], approve: '2024-06-30', budget: 4300000, contract: 4150000, year: 2024,
    done: ['立项申报', '需求调研与规格', '招标采购', '合同签订', '设计与评审', '开发实现', '测试与测评', '部署上线与试运行', '初验'], ip: [{ name: '终验', percent: 85 }], payPaidNodes: ['PREPAY', 'ARRIVAL', 'FIRST_ACCEPT'], payPlanNode: 'FINAL_ACCEPT' },
  { name: '公共安全视频监控联网整合(四期)', type: 'SW', owner: OWNERS[4], vendor: VENDORS[9], approve: '2025-09-22', budget: 3150000, contract: 2980000, year: 2025,
    done: ['立项申报', '需求调研与规格'], ip: [{ name: '招标采购', percent: 45 }], payPaidNodes: [], payPlanNode: 'PREPAY' },
];

async function main() {
  const start = Date.now();
  const login = await api('POST', '/api/auth/login', { account: 'admin', password: '123456' });
  const token = login.token;
  const users = await api('GET', '/api/users', undefined, token);
  const manager = users.find((u) => u.account === 'jingban01') || users[0];
  const depts = await api('GET', '/api/depts', undefined, token);
  const dept = depts.find((d) => d.name === '信息化处') || depts[0];

  let okCount = 0;
  const detailCache = [];
  for (const scene of SCENES) {
    try {
      const created = await api('POST', '/api/projects', {
        name: scene.name,
        type: scene.type,
        status: scene.projectStatus || 'RUN',
        ownerUnit: scene.owner,
        ownerDeptId: dept.id,
        managerUserId: manager.id,
        memberIds: users.map((u) => u.id),
        vendorName: scene.vendor,
        vendorContact: '张工 138****0011',
        approveNo: `X发改〔2025〕${String(20 + SCENES.indexOf(scene) * 3).padStart(2, '0')}号`,
        budgetAmount: scene.budget,
        fundSource: 'GOV',
        bidType: SCENES.indexOf(scene) % 2 ? 'NEGO' : 'OPEN',
        bidAmount: round2(scene.contract * 0.995),
        contractNo: `HT-20${String(20 + SCENES.indexOf(scene)).slice(-2)}-${String(100 + SCENES.indexOf(scene) * 4)}`,
        contractAmount: scene.contract,
        changeAmount: 0,
        approveDate: scene.approve,
        planStartDate: scene.approve,
        planFinishDate: addDays(new Date(scene.approve), 400),
        contentSummary: `演示数据：${scene.name}（${scene.type === 'HW' ? '硬件' : '软件'}项目）。`,
        projectSource: 'UPPER',
        remark: scene.remark || '',
      }, token);
      const id = created;
      const detail = await api('GET', `/api/projects/${id}`, undefined, token);
      detailCache.push(detail);

      // 阶段推进
      const byName = new Map(detail.phases.map((p) => [p.phaseName, p]));
      const idxOf = (p) => p.sortNo;

      for (const name of scene.done) {
        const ph = byName.get(name);
        if (!ph) continue;
        const base = new Date(scene.approve);
        const offset = (idxOf(ph) - 1) * 55;
        const planStart = addDays(base, offset);
        const planFinish = addDays(base, offset + 60);
        const rf = RESULT_BY_PHASE[name];
        const resultFields = rf
          ? { ...rf(SCENES.indexOf(scene)) }
          : { 完成情况: '已完成（演示）' };
        await api('PUT', `/api/projects/${id}/phases/${ph.id}`, {
          status: 'DONE',
          percent: 100,
          planStartDate: planStart,
          planFinishDate: planFinish,
          actualStartDate: planStart,
          actualFinishDate: planFinish,
          managerUserId: manager.id,
          note: `演示：本阶段已完成，关键材料已归档。`,
          resultFields,
        }, token);
      }
      for (const item of scene.ip || []) {
        const ph = byName.get(item.name);
        if (!ph) continue;
        const base = new Date(scene.approve);
        const offset = (idxOf(ph) - 1) * 55;
        const planStart = addDays(base, offset);
        const planFinish = addDays(base, offset + 60);
        await api('PUT', `/api/projects/${id}/phases/${ph.id}`, {
          status: 'IN_PROGRESS',
          percent: item.percent,
          planStartDate: planStart,
          planFinishDate: planFinish,
          actualStartDate: planStart,
          managerUserId: manager.id,
          note: `演示：当前推进中（完成 ${item.percent}%）。`,
        }, token);
      }

      // 付款记录
      const contract = scene.contract;
      const today = new Date();
      for (const node of scene.payPaidNodes || []) {
        const amount = round2(contract * PAY_RATIO[node]);
        await api('POST', '/api/payments', {
          projectId: id,
          nodeCode: node,
          nodeName: PAY_NAME[node],
          conditionDesc: '演示：按合同里程碑支付',
          planAmount: amount,
          planDate: addDays(today, -120),
          paidAmount: amount,
          paidDate: addDays(today, -100),
          status: 'PAID',
        }, token);
      }
      if (scene.payPlanNode) {
        const amount = round2(contract * PAY_RATIO[scene.payPlanNode]);
        await api('POST', '/api/payments', {
          projectId: id,
          nodeCode: scene.payPlanNode,
          nodeName: PAY_NAME[scene.payPlanNode],
          conditionDesc: '演示：待支付（里程碑未到）',
          planAmount: amount,
          planDate: addDays(today, 60),
          paidAmount: 0,
          paidDate: null,
          status: 'UNPAID',
        }, token);
      }

      // 状态收尾（PAUSE/STOP/DONE 等）
      if (scene.projectStatus && scene.projectStatus !== 'RUN') {
        const upd = { ...detail, status: scene.projectStatus };
        delete upd.phases;
        await api('PUT', `/api/projects/${id}`, upd, token);
      }

      // 部分项目补 1~2 个附件（阶段附件 / 付款凭证）
      if (SCENES.indexOf(scene) % 3 === 0) {
        const firstPhase = detail.phases[0];
        const txt = `【演示附件】${scene.name}\n阶段：${firstPhase.phaseName}\n模拟内容：立项批复等过程文档样例，用于验证附件上传/下载/预览功能。`;
        await uploadText(token, id, 'PROJECT_PHASE', firstPhase.id, `立项批复-演示${SCENES.indexOf(scene) + 1}.txt`, txt);
      }
      if (SCENES.indexOf(scene) % 5 === 1 && (scene.payPaidNodes || []).length) {
        const pays = await api('GET', `/api/projects/${id}/payments`, undefined, token);
        const paid = pays.find((p) => p.status === 'PAID');
        if (paid) {
          const txt = `【演示附件】${scene.name} - 付款凭证\n节点：${paid.nodeName}，金额：${paid.paidAmount} 元\n模拟付款凭证扫描件。`;
          await uploadText(token, id, 'PAYMENT', paid.id, `付款凭证-${paid.nodeName}-演示.txt`, txt);
        }
      }
      okCount++;
      console.log(`✓ ${scene.name}（${id}）`);
    } catch (e) {
      console.log(`✗ ${scene.name} 失败: ${e.message}`);
    }
  }

  // 汇总输出
  const list = await api('GET', '/api/projects?page=1&size=100', undefined, token);
  const byStatus = {};
  const byType = {};
  for (const p of list.records) {
    byStatus[p.status] = (byStatus[p.status] || 0) + 1;
    byType[p.type] = (byType[p.type] || 0) + 1;
  }
  const pays = [];
  const atts = [];
  for (const d of detailCache) {
    pays.push(...(await api('GET', `/api/projects/${d.id}/payments`, undefined, token)));
    atts.push(...(await api('GET', `/api/projects/${d.id}/attachments`, undefined, token)));
  }
  console.log('========================================');
  console.log(`耗时 ${((Date.now() - start) / 1000).toFixed(1)}s`);
  console.log(`成功项目 ${okCount}/${SCENES.length}，库内项目总数 ${list.total}`);
  console.log(`状态分布 ${JSON.stringify(byStatus)}  类型分布 ${JSON.stringify(byType)}`);
  console.log(`付款记录 ${pays.length} 条，累计实付 ${round2(pays.reduce((s, p) => s + (p.paidAmount || 0), 0) / 10000)} 万元`);
  console.log(`附件 ${atts.length} 个`);
}

async function uploadText(token, projectId, bizType, bizId, fileName, text) {
  const fd = new FormData();
  fd.append('file', new Blob([text], { type: 'text/plain;charset=utf-8' }), fileName);
  fd.append('projectId', String(projectId));
  fd.append('bizType', bizType);
  fd.append('bizId', String(bizId));
  fd.append('attachType', bizType === 'PAYMENT' ? 'PAY_VOUCHER' : 'APPROVAL');
  await api('POST', '/api/attachments/upload', fd, token);
}

main().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
