/* =========================================================
 * 演示数据层（Mock） —— 政府信息化项目管理系统 Demo
 * 仅用于界面演示，数据无任何真实含义
 * ========================================================= */

/* ---------- 基础字典 ---------- */
const TYPES = { HW: '硬件项目', SW: '软件项目' };

const STATUS_META = {
  RUN:   { text: '进行中', cls: 'b-run' },
  DONE:  { text: '已完结', cls: 'b-done' },
  PAUSE: { text: '暂停',   cls: 'b-pause' },
  STOP:  { text: '中止',   cls: 'b-stop' },
};

const PHASE_LABEL = {
  DONE: '已完成', ACT: '进行中', WAIT: '未开始',
  SKIP: '已跳过', STOP: '已中止',
};

const DEPTS = ['信息化建设处', '数据资源处', '规划发展处', '运维保障处'];
const USERS = [
  { id: 'zhang', name: '张伟', dept: '信息化建设处', role: '项目经办人' },
  { id: 'li',    name: '李娜', dept: '数据资源处',   role: '项目经办人' },
  { id: 'wang',  name: '王强', dept: '运维保障处',   role: '项目经办人' },
  { id: 'liu',   name: '刘敏', dept: '规划发展处',   role: '项目经办人' },
  { id: 'admin', name: '系统管理员', dept: '综合管理科', role: '管理员' },
];

/* ---------- 阶段模板（与设计稿 §4.3 / §4.4 一致） ---------- */
const TEMPLATES = {
  HW: [
    { n: '立项申报',     w: 5,  at: ['立项批复.pdf', '可行性研究报告.pdf', '资金预算批复.pdf'], dnote: '完成立项申报，取得批复文件并落实资金计划。' },
    { n: '招标采购',     w: 10, at: ['招标文件.pdf', '评标记录.pdf', '中标通知书.pdf'], dnote: '按政府采购程序完成招标，中标结果已公示。' },
    { n: '合同签订',     w: 5,  at: ['合同扫描件.pdf', '廉洁承诺书.pdf'], dnote: '完成合同签订与备案，明确验收及付款条款。' },
    { n: '到货验收',     w: 20, at: ['到货签收单.pdf', '装箱单.pdf', '设备清单.xlsx'], dnote: '设备按合同约定到场，完成到货签收与清点。' },
    { n: '安装调试',     w: 20, at: ['实施方案.pdf', '调试记录.docx', '集成测试报告.pdf'], dnote: '完成设备安装与系统联调，形成调试记录。' },
    { n: '初验',         w: 15, at: ['初验申请.pdf', '初验报告.pdf'], dnote: '组织初验并通过，出具初验报告。' },
    { n: '试运行',       w: 5,  at: ['试运行记录.pdf'], dnote: '试运行期间系统运行稳定。' },
    { n: '终验',         w: 15, at: ['终验报告.pdf', '竣工资料.pdf', '资产移交清单.xlsx'], dnote: '通过终验，完成资料与资产移交。' },
    { n: '质保运维',     w: 5,  at: ['质保服务记录.pdf', '质保金支付凭证.pdf'], dnote: '质保期内服务正常，质保金到期支付。' },
  ],
  SW: [
    { n: '立项申报',           w: 5,  at: ['立项批复.pdf', '可行性研究报告.pdf'], dnote: '完成立项申报，需求方向明确并取得批复。' },
    { n: '需求调研与规格',     w: 10, at: ['需求规格说明书.pdf', '需求评审记录.pdf', '原型图.pdf'], dnote: '完成需求调研与规格评审，形成需求基线。' },
    { n: '招标采购',           w: 10, at: ['招标文件.pdf', '中标通知书.pdf'], dnote: '完成招标采购，中标结果已公示。' },
    { n: '合同签订',           w: 5,  at: ['合同扫描件.pdf'], dnote: '完成合同签订与备案。' },
    { n: '设计与评审',         w: 10, at: ['概要设计.pdf', '详细设计.pdf', '数据库设计.pdf', '接口文档.docx'], dnote: '完成概要设计、详细设计与设计评审。' },
    { n: '开发实现',           w: 15, at: ['迭代计划.xlsx', '版本说明.docx', '代码仓库说明.docx'], dnote: '完成系统开发并提交测试。' },
    { n: '测试与测评',         w: 10, at: ['测试报告.pdf', '第三方测评报告.pdf', '整改记录.xlsx'], dnote: '完成系统测试及第三方测评整改。' },
    { n: '部署上线与试运行',   w: 10, at: ['部署方案.pdf', '上线申请.pdf', '用户培训签到表.xlsx'], dnote: '完成部署上线与试运行，开展用户培训。' },
    { n: '初验',               w: 10, at: ['初验申请.pdf', '初验报告.pdf'], dnote: '组织初验并通过。' },
    { n: '终验',               w: 10, at: ['终验报告.pdf', '源代码移交清单.xlsx', '运维移交资料.pdf'], dnote: '通过终验，完成源代码与资料移交。' },
    { n: '质保运维',           w: 5,  at: ['质保服务记录.pdf', '质保金支付凭证.pdf'], dnote: '质保期内服务正常，质保金到期支付。' },
  ],
};

/* 各阶段默认工期（天），用于生成计划时间 */
const DURS = {
  HW: [30, 45, 18, 20, 40, 18, 30, 20, 300],
  SW: [30, 35, 45, 18, 22, 55, 30, 30, 18, 20, 300],
};

/* 里程碑付款节点：trig = 触发该付款的阶段序号（该阶段完成后进入待付/已付） */
const PAYCONF = {
  HW: [
    { name: '预付款', trig: 2, ratio: 0.30 },
    { name: '到货款', trig: 3, ratio: 0.30 },
    { name: '初验款', trig: 5, ratio: 0.20 },
    { name: '终验款', trig: 7, ratio: 0.15 },
    { name: '质保金', trig: 8, ratio: 0.05 },
  ],
  SW: [
    { name: '预付款', trig: 3, ratio: 0.30 },
    { name: '初验款', trig: 8, ratio: 0.30 },
    { name: '终验款', trig: 9, ratio: 0.35 },
    { name: '质保金', trig: 10, ratio: 0.05 },
  ],
};

/* ---------- 工具 ---------- */
function addDays(iso, n) {
  const p = iso.split('-').map(Number);
  const dt = new Date(Date.UTC(p[0], p[1] - 1, p[2] + n));
  return dt.toISOString().slice(0, 10);
}
function rnd(a, b) { return a + Math.floor(Math.random() * (b - a + 1)); }
function pad(n) { return String(n).padStart(3, '0'); }

function extOf(name) { return (name.split('.').pop() || 'file').toUpperCase(); }

function mkFile(name, date, manager, idx) {
  const szMB = (Math.random() * 6.8 + 0.3).toFixed(1);
  return { name, ext: extOf(name), size: szMB + ' MB', up: manager, date };
}

function calcOverall(phases) {
  let totW = 0, got = 0;
  phases.forEach(p => {
    if (p.status === 'SKIP') return;
    totW += p.w;
    if (p.status === 'DONE') got += p.w;
    else if (p.status === 'ACT') got += p.w * (p.pct || 0) / 100;
  });
  return totW ? Math.round(got / totW * 100) : 0;
}

function curPhaseName(phases) {
  const a = phases.find(p => p.status === 'ACT');
  if (a) return a.name;
  const s = phases.find(p => p.status === 'STOP');
  if (s) return s.name;
  const w = phases.find(p => p.status === 'WAIT');
  if (w) return w.name;
  return '全部完成';
}

function buildPayments(spec, phases) {
  const conf = PAYCONF[spec.type];
  const contract = spec.contractAmount || 0;
  const doneAll = spec.status === 'DONE';
  return conf.map(c => {
    const trg = phases[c.trig];
    const trgDone = trg && (trg.status === 'DONE');
    const planAmount = Math.round(contract * c.ratio);
    let paidAmount = 0, paidDate = '', status = '待支付';
    if (doneAll && trgDone) {
      paidAmount = planAmount;
      paidDate = addDays(trg.actE || spec.finishDate, rnd(3, 12));
      status = '已支付';
    } else if (trgDone) {
      paidAmount = planAmount;
      paidDate = addDays(trg.actE, rnd(2, 10));
      status = '已支付';
    }
    return {
      name: c.name, ratio: c.ratio, planAmount,
      planDate: trg ? trg.planE : '',
      paidAmount, paidDate, status,
    };
  });
}

function buildLogs(spec, phases, user) {
  const logs = [];
  logs.push({ tm: spec.approveDate + ' 09:12', who: user, act: '创建项目并完成立项信息录入' });
  phases.forEach(p => {
    if (p.status === 'DONE' && p.actE) {
      logs.push({ tm: p.actE + ' 15:40', who: user, act: '推进阶段：将「' + p.name + '」标记为完成' });
    }
    p.files.forEach(f => {
      logs.push({ tm: f.date + ' 10:2' + (p.i % 5), who: f.up, act: '上传附件：' + f.name });
    });
  });
  logs.sort((x, y) => (x.tm < y.tm ? 1 : -1));
  return logs.slice(0, 14);
}

/* ---------- 项目构建 ---------- */
function buildProject(spec) {
  const type = spec.type;
  const tmpl = TEMPLATES[type];
  const durs = DURS[type];
  const doneAll = spec.status === 'DONE';
  const stopIdx = spec.status === 'STOP' ? spec.cur : -1;
  const skip = spec.skip || {};
  const year = spec.approveDate.slice(0, 4);
  const code = (type === 'HW' ? 'YJ-' : 'RJ-') + year + '-' + pad(spec.no);

  const phases = [];
  let prev = spec.approveDate;
  for (let i = 0; i < tmpl.length; i++) {
    const t = tmpl[i];
    const planS = addDays(prev, rnd(5, 14));
    const planE = addDays(planS, durs[i]);
    let status, pct = 0, actS = '', actE = '';
    if (skip[i]) status = 'SKIP';
    else if (doneAll) { status = 'DONE'; actS = planS; actE = (i === tmpl.length - 1 && spec.finishDate) ? spec.finishDate : planE; }
    else if (i === stopIdx) { status = 'STOP'; actS = planS; }
    else if (i < spec.cur) { status = 'DONE'; actS = planS; actE = planE; }
    else if (i === spec.cur) { status = 'ACT'; pct = spec.curPct || 0; actS = planS; }
    else status = 'WAIT';

    const files = [];
    if (status === 'DONE' || status === 'ACT' || status === 'STOP') {
      const pool = t.at || [];
      const cnt = Math.min(pool.length, status === 'DONE' ? rnd(1, 2) : 1);
      for (let k = 0; k < cnt; k++) files.push(mkFile(pool[k], actS || planS, spec.manager, k));
      const extra = (spec.files || {})[i] || [];
      extra.forEach((nm, k) => files.push(mkFile(nm, actS || planS, spec.manager, k + cnt)));
    }
    const p = {
      i, name: t.n, w: t.w, status, pct, planS, planE, actS, actE,
      note: (spec.notes || {})[i] || t.dnote, files,
    };
    phases.push(p);
    if (status !== 'WAIT') prev = actE || planS;
  }

  const payments = buildPayments(spec, phases);
  const paidTotal = payments.reduce((s, p) => s + p.paidAmount, 0);
  const contractTotal = (spec.contractAmount || 0) + (spec.changeAmount || 0);

  /* 最近更新时间（用于列表排序展示） */
  const doneActs = phases.filter(p => p.actE).map(p => p.actE).sort();
  const updated = spec.updated || doneActs[doneActs.length - 1] || spec.approveDate;

  const proj = Object.assign({}, spec, {
    code, typeText: TYPES[type],
    statusText: STATUS_META[spec.status].text,
    contractTotal, paidTotal,
    unpaid: Math.max(contractTotal - paidTotal, 0),
    overall: calcOverall(phases),
    curStage: curPhaseName(phases),
    phases, payments, updated,
    logs: buildLogs(spec, phases, spec.manager),
  });
  return proj;
}

/* ---------- 重点项目（详细演示用，含阶段备注与附件覆盖） ---------- */
const SPECS = [
  { no: 1, name: '市政务云平台扩容（一期）', type: 'HW', status: 'RUN', cur: 3, curPct: 60,
    unit: '市大数据管理局', dept: '信息化建设处', manager: '张伟',
    vendor: '神州数码信息系统有限公司', approveNo: 'X发改〔2024〕58号', approveDate: '2024-03-15',
    budget: 8600000, bidAmount: 7980000, contractAmount: 7980000, changeAmount: 0,
    planFinish: '2025-06-30', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-04-28',
    notes: {
      0: '项目建议书与可研报告获市发改委批复（X发改〔2024〕58 号），资金已列入 2024 年度部门预算，概算 860 万元。',
      2: '合同已备案。付款条款：预付款 30%、到货款 30%、初验款 20%、终验款 15%、质保金 5%。',
      3: '首批 120 台国产服务器已到场并签收，正在逐台核对配置清单；剩余 30 台网络设备预计下周到货。',
    },
    files: {
      0: ['X发改〔2024〕58号-立项批复.pdf'],
      3: ['到货签收单-第一批次.pdf', '设备配置核对表.xlsx'],
    } },

  { no: 2, name: '“一网通办”政务服务门户升级改造', type: 'SW', status: 'RUN', cur: 6, curPct: 40,
    unit: '市大数据管理局', dept: '规划发展处', manager: '刘敏',
    vendor: '太极计算机股份有限公司', approveNo: 'X发改〔2024〕97号', approveDate: '2024-06-10',
    budget: 4200000, bidAmount: 3860000, contractAmount: 3860000, changeAmount: 0,
    planFinish: '2025-08-31', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-05-06',
    notes: {
      1: '需求规格说明书已通过甲方评审并基线化，共梳理业务事项 148 项。',
      6: '系统测试已完成三轮回归，目前正在开展第三方测评与等保二级测评整改。',
    },
    files: { 6: ['系统测试报告-第三轮.docx', '等保测评整改清单.xlsx'] } },

  { no: 3, name: '市电子政务外网安全加固项目', type: 'HW', status: 'DONE', cur: 99,
    unit: '市大数据管理局', dept: '运维保障处', manager: '王强',
    vendor: '启明星辰信息技术集团股份有限公司', approveNo: 'X发改〔2023〕31号', approveDate: '2023-05-08',
    budget: 5600000, bidAmount: 5120000, contractAmount: 5120000, changeAmount: 0,
    finishDate: '2024-04-20', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-04-20',
    notes: {
      0: '根据市网络安全攻防演练结果立项，防护对象为外网 16 个核心业务系统。',
      8: '质保金已于 2025-04 到期支付，项目档案归档，流程完结。',
    } },

  { no: 4, name: 'XX 区基层治理数据平台（一期）', type: 'SW', status: 'RUN', cur: 7, curPct: 80,
    unit: 'XX 区政务服务数据管理局', dept: '数据资源处', manager: '李娜',
    vendor: '数字广东网络建设有限公司', approveNo: 'XX区发改〔2024〕22号', approveDate: '2024-09-02',
    budget: 9800000, bidAmount: 9150000, contractAmount: 9150000, changeAmount: 0,
    planFinish: '2025-10-31', source: '单位自建', fundSource: '区财政', bidType: '竞争性磋商', updated: '2025-05-12',
    notes: {
      7: '系统已上线试运行 2 周，完成 5 场用户培训（累计 320 人次），正在收集试运行问题，准备组织初验。',
    },
    files: { 7: ['上线申请单.pdf', '用户培训签到表.xlsx', '试运行问题清单.xlsx'] } },

  { no: 5, name: '市人力资源档案数字化项目', type: 'HW', status: 'PAUSE', cur: 4, curPct: 20,
    unit: '市人力资源和社会保障局', dept: '信息化建设处', manager: '张伟',
    vendor: '航天信息股份有限公司', approveDate: '2024-04-20',
    budget: 3200000, bidAmount: 2980000, contractAmount: 2980000, changeAmount: 0,
    planFinish: '2025-12-31', source: '单位自建', fundSource: '单位自筹', bidType: '竞争性谈判', updated: '2025-04-02',
    notes: {
      4: '因甲方档案库房机房改造延期，安装调试工作暂停，恢复时间待甲方另行通知。',
    } },

  { no: 6, name: '市政务数据共享交换平台（二期）', type: 'SW', status: 'RUN', cur: 2, curPct: 50,
    unit: '市大数据管理局', dept: '数据资源处', manager: '李娜',
    vendor: '浪潮软件股份有限公司', approveNo: 'X发改〔2025〕10号', approveDate: '2025-02-14',
    budget: 6500000, bidAmount: 6020000, contractAmount: 6020000, changeAmount: 0,
    planFinish: '2026-03-31', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-05-08',
    notes: {
      2: '已完成开标评标，中标结果正在公示期（5 个工作日）。',
    } },

  { no: 7, name: '市视频监控联网平台扩容（四期）', type: 'HW', status: 'RUN', cur: 2, curPct: 30,
    unit: '市公安局', dept: '信息化建设处', manager: '张伟',
    vendor: '杭州海康威视数字技术股份有限公司', approveDate: '2024-08-15',
    budget: 12000000, bidAmount: 11350000, contractAmount: 11350000, changeAmount: 0,
    planFinish: '2025-12-31', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-04-15',
    notes: { 2: '合同正在走单位内部流转会签流程。' } },

  { no: 8, name: '市信用信息平台升级改造', type: 'SW', status: 'DONE', cur: 99,
    unit: '市大数据管理局', dept: '规划发展处', manager: '刘敏',
    vendor: '江苏润和软件股份有限公司', approveDate: '2023-02-20',
    budget: 3500000, bidAmount: 3240000, contractAmount: 3240000, changeAmount: 0,
    finishDate: '2023-12-15', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2024-01-10' },

  { no: 9, name: '市政务机房 UPS 及配电系统改造', type: 'HW', status: 'RUN', cur: 1, curPct: 15,
    unit: '市大数据管理局', dept: '运维保障处', manager: '王强',
    vendor: '科华数据股份有限公司', approveDate: '2025-04-01',
    budget: 2150000, bidAmount: 1930000, contractAmount: 1930000, changeAmount: 0,
    planFinish: '2025-12-31', source: '单位自建', fundSource: '单位自筹', bidType: '竞争性磋商', updated: '2025-04-30' },

  { no: 10, name: '市医保结算系统容灾建设项目', type: 'HW', status: 'RUN', cur: 2, curPct: 20,
    unit: '市医疗保障局', dept: '运维保障处', manager: '王强',
    vendor: '华为技术有限公司', approveDate: '2024-11-05',
    budget: 9200000, bidAmount: 8600000, contractAmount: 8600000, changeAmount: 0,
    planFinish: '2026-02-28', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-03-20' },

  { no: 11, name: '“i 政府”移动端应用整合项目', type: 'SW', status: 'RUN', cur: 4, curPct: 50,
    unit: '市大数据管理局', dept: '规划发展处', manager: '刘敏',
    vendor: '腾讯云计算（北京）有限责任公司', approveDate: '2024-10-12',
    budget: 2700000, bidAmount: 2470000, contractAmount: 2470000, changeAmount: 0,
    planFinish: '2025-09-30', source: '单位自建', fundSource: '单位自筹', bidType: '竞争性磋商', updated: '2025-04-22' },

  { no: 12, name: '市政务短信平台迁移项目', type: 'SW', status: 'STOP', cur: 2,
    unit: '市大数据管理局', dept: '运维保障处', manager: '王强',
    vendor: '中国移动通信集团', approveDate: '2023-11-20',
    budget: 1000000, bidAmount: 860000, contractAmount: 860000, changeAmount: 0,
    source: '单位自建', fundSource: '单位自筹', bidType: '单一来源', updated: '2024-06-18',
    notes: { 2: '因业务整合至省统一短信平台，本项目于 2024-06 终止，已完成合同清算与供应商结算。' } },

  /* ---- 填充项目（保证分页与筛选效果） ---- */
  { no: 13, name: '市政务数据资源目录梳理服务', type: 'SW', status: 'RUN', cur: 1, curPct: 60,
    unit: '市大数据管理局', dept: '数据资源处', manager: '李娜',
    vendor: '中电科新型智慧城市研究院', approveDate: '2025-01-10',
    budget: 1100000, bidAmount: 980000, contractAmount: 980000, planFinish: '2025-09-30', source: '上级下发', fundSource: '财政拨款', bidType: '竞争性磋商', updated: '2025-05-10' },

  { no: 14, name: '市电子证照系统升级项目', type: 'SW', status: 'RUN', cur: 4, curPct: 40,
    unit: '市大数据管理局', dept: '规划发展处', manager: '刘敏',
    vendor: '万达信息股份有限公司', approveDate: '2024-09-05',
    budget: 3200000, bidAmount: 2960000, contractAmount: 2960000, planFinish: '2025-10-31', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-04-18' },

  { no: 15, name: '市政务网站群集约化改造（三期）', type: 'SW', status: 'DONE', cur: 99,
    unit: '市大数据管理局', dept: '信息化建设处', manager: '张伟',
    vendor: '开普云信息科技股份有限公司', approveDate: '2023-03-06',
    budget: 3400000, bidAmount: 3120000, contractAmount: 3120000, finishDate: '2023-11-28', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2023-12-20' },

  { no: 16, name: '市机房动环监控改造项目（二期）', type: 'HW', status: 'RUN', cur: 3, curPct: 10,
    unit: '市大数据管理局', dept: '运维保障处', manager: '王强',
    vendor: '深圳市计通智能技术有限公司', approveDate: '2024-07-02',
    budget: 1600000, bidAmount: 1450000, contractAmount: 1450000, planFinish: '2025-06-30', source: '单位自建', fundSource: '单位自筹', bidType: '竞争性谈判', updated: '2025-03-12' },

  { no: 17, name: '市政务云灾备资源扩容采购', type: 'HW', status: 'RUN', cur: 1, curPct: 10,
    unit: '市财政局', dept: '信息化建设处', manager: '张伟',
    vendor: '浪潮电子信息产业股份有限公司', approveDate: '2025-03-18',
    budget: 3100000, bidAmount: 2860000, contractAmount: 2860000, planFinish: '2025-11-30', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-04-25' },

  { no: 18, name: '市国土空间基础信息平台', type: 'SW', status: 'RUN', cur: 5, curPct: 35,
    unit: '市自然资源局', dept: '规划发展处', manager: '刘敏',
    vendor: '北京超图软件股份有限公司', approveDate: '2024-04-08',
    budget: 4500000, bidAmount: 4150000, contractAmount: 4150000, planFinish: '2025-12-31', source: '上级下发', fundSource: '财政拨款', bidType: '公开招标', updated: '2025-03-30' },

  { no: 19, name: '市城管视频智能分析试点项目', type: 'HW', status: 'RUN', cur: 2, curPct: 15,
    unit: '市城市管理和综合执法局', dept: '信息化建设处', manager: '张伟',
    vendor: '浙江大华技术股份有限公司', approveDate: '2024-12-06',
    budget: 3600000, bidAmount: 3280000, contractAmount: 3280000, planFinish: '2026-01-31', source: '单位自建', fundSource: '区级资金', bidType: '公开招标', updated: '2025-02-10' },

  { no: 20, name: '市社区网格化管理系统（三期）', type: 'SW', status: 'PAUSE', cur: 7, curPct: 30,
    unit: 'XX 区政务服务数据管理局', dept: '数据资源处', manager: '李娜',
    vendor: '中软国际科技服务有限公司', approveDate: '2024-05-20',
    budget: 5800000, bidAmount: 5420000, contractAmount: 5420000, changeAmount: 0,
    planFinish: '2025-12-31', source: '单位自建', fundSource: '区财政', bidType: '公开招标', updated: '2025-01-15',
    notes: { 7: '因区划调整后网格划分标准待定，上线试运行暂停，等待业务部门明确口径。' } },
];

/* ---------- 组装数据集 ---------- */
let PROJECTS = [];
SPECS.forEach((s, idx) => {
  s.no = s.no || (idx + 1);
  PROJECTS.push(buildProject(s));
});
/* 按更新时间倒序，方便列表默认展示 */
PROJECTS.sort((a, b) => (a.updated < b.updated ? 1 : -1));

function getProject(id) { return PROJECTS.find(p => p.code === id); }

/* 选项集合（供筛选下拉使用） */
const UNIT_OPTIONS = [...new Set(PROJECTS.map(p => p.unit))].sort();
const YEAR_OPTIONS = [...new Set(PROJECTS.map(p => p.approveDate.slice(0, 4)))].sort().reverse();
const CURRENT_USER = { id: 'zhang', name: '张伟', dept: '信息化建设处', role: '项目经办人' };
