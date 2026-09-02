/* =========================================================
 * 应用层（Demo）—— 视图渲染 + 交互
 * 纯前端演示：状态保存在内存中，刷新即重置
 * ========================================================= */

/* 演示用"今天"（保持界面时间一致） */
const TODAY = '2025-05-15';
const PAGE_SIZE = 8;

const STATE = {
  authed: false,
  view: 'dashboard',          // dashboard | list | detail | stats | sys
  pid: null,
  tab: 'flow',                // detail 内部页签
  list: { mode: 'list', page: 1, kw: '', type: '', status: '', unit: '', year: '' },
  stats: { year: '', type: '', status: '' },
};

/* ---------------- 工具 ---------------- */
const $id = s => document.getElementById(s);
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g,
    m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
}
function money(n) { return n ? '¥ ' + Math.round(n).toLocaleString('zh-CN') : '—'; }
function moneyW(n) {
  if (!n) return '—';
  const w = n / 10000;
  return (w >= 100 ? Math.round(w) : w.toFixed(1)).toLocaleString('zh-CN') + '万';
}
function statusBadge(st) {
  const m = STATUS_META[st];
  return '<span class="badge ' + m.cls + '">' + m.text + '</span>';
}
function phBadge(st) {
  const cls = st === 'DONE' ? 'b-fin' : st === 'ACT' ? 'b-act' : st === 'WAIT' ? 'b-wait' : st === 'SKIP' ? 'b-skip' : 'b-stop';
  return '<span class="badge ' + cls + '">' + PHASE_LABEL[st] + '</span>';
}
function typeTag(t) {
  return '<span class="tag ' + (t === 'HW' ? 'b-purple' : 'b-run') + '">' + TYPES[t] + '</span>';
}
function progressBar(v, warn) {
  const cls = v >= 100 ? 'ok' : (warn ? 'warn' : '');
  return '<div class="prog"><div class="track"><div class="in ' + cls + '" style="width:' + Math.min(v, 100) + '%"></div></div><b>' + v + '%</b></div>';
}
function isOverdue(p) { return p.status === 'ACT' && p.planE && p.planE < TODAY; }
function overdueDays(p) {
  return Math.max(1, Math.round((Date.parse(TODAY) - Date.parse(p.planE)) / 86400000));
}
function diffDays(a, b) {
  return Math.round((Date.parse(b) - Date.parse(a)) / 86400000);
}
function extCls(ext) {
  ext = (ext || '').toLowerCase();
  if (ext === 'pdf') return 'b-stop';
  if (['doc', 'docx'].includes(ext)) return 'b-run';
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'b-done';
  if (['png', 'jpg', 'jpeg', 'gif'].includes(ext)) return 'b-purple';
  if (['zip', 'rar', '7z'].includes(ext)) return 'b-pause';
  return 'b-gray';
}
function toast(msg) {
  let box = $id('toast');
  if (!box) { box = document.createElement('div'); box.id = 'toast'; document.body.appendChild(box); }
  const t = document.createElement('div');
  t.className = 'toast'; t.textContent = msg;
  box.appendChild(t);
  setTimeout(() => { t.style.opacity = 0; t.style.transition = 'opacity .3s'; setTimeout(() => t.remove(), 320); }, 2200);
}
function readListKw() {
  const el = $id('list-kw');
  if (el) STATE.list.kw = el.value.trim();
}

/* ---------------- 登录 / 布局骨架 ---------------- */
function boot() {
  STATE.authed = localStorage.getItem('pms_auth') === '1';
  if (STATE.authed) renderApp();
  else renderLogin();
}

function renderLogin() {
  $id('app').innerHTML =
    '<div class="login-wrap"><div class="login-card">' +
      '<div class="login-logo"><div class="logo-badge">政</div><div>' +
        '<div class="login-title">政府信息化项目管理系统</div>' +
        '<div class="login-sub" style="font-size:12px;color:#86909c;margin-top:2px;">内部使用 · 演示版（Mock 数据）</div></div></div>' +
      '<div class="field"><label>账号</label><input id="login-user" value="admin"></div>' +
      '<div class="field"><label>密码</label><input id="login-pass" type="password" value="123456"></div>' +
      '<button class="login-btn" data-act="login">登 录</button>' +
      '<div class="login-tip">演示账号 <code>admin / 123456</code>　按 <code>Enter</code> 亦可登录</div>' +
    '</div></div>';
  setTimeout(() => { const i = $id('login-user'); if (i) i.focus(); }, 50);
}

function renderApp() {
  const menu = [
    { v: 'dashboard', t: '首页工作台' },
    { v: 'list', t: '项目管理' },
    { v: 'stats', t: '项目统计' },
    { v: 'sys', t: '系统管理（示例）' },
  ];
  const activeView = STATE.view === 'detail' ? 'list' : STATE.view;
  const crumb = STATE.view === 'detail'
    ? '项目详情：' + (getProject(STATE.pid) ? getProject(STATE.pid).name : '')
    : menu.find(m => m.v === STATE.view).t;

  $id('app').innerHTML =
    '<div class="shell">' +
      '<aside class="side">' +
        '<div class="brand"><div class="logo-badge">政</div><b>项目管理</b></div>' +
        '<div class="menu">' +
          menu.map(m =>
            '<div class="mi' + (m.v === activeView ? ' on' : '') + '" data-act="go" data-view="' + m.v + '">' +
              '<span class="ic">' + (m.v === 'dashboard' ? '⌂' : m.v === 'list' ? '▤' : m.v === 'stats' ? '◫' : '⚙') + '</span>' +
              m.t + '</div>').join('') +
        '</div>' +
        '<div class="side-foot">演示版 v0.1 · 数据为 Mock</div>' +
      '</aside>' +
      '<div class="main">' +
        '<header class="topbar">' +
          '<div class="crumb">' + esc(crumb) + '</div>' +
          '<div class="userbox">' +
            '<div class="avatar">' + (CURRENT_USER.name || '张')[0] + '</div>' +
            '<div>' + esc(CURRENT_USER.name) + ' <span class="muted">(' + CURRENT_USER.role + ')</span></div>' +
            '<button class="btn-link" data-act="logout">退出</button>' +
          '</div>' +
        '</header>' +
        '<main class="view" id="view"></main>' +
      '</div>' +
    '</div>';
  renderView();
}

function renderView() {
  const box = $id('view');
  if (!box) return;
  if (STATE.view === 'dashboard') box.innerHTML = viewDashboard();
  else if (STATE.view === 'list') box.innerHTML = viewList();
  else if (STATE.view === 'detail') box.innerHTML = viewDetail();
  else if (STATE.view === 'stats') box.innerHTML = viewStats();
  else box.innerHTML = viewSys();
  box.scrollTop = 0;
}

/* ================= 1. 首页工作台 ================= */
function viewDashboard() {
  const all = PROJECTS;
  const running = all.filter(p => p.status === 'RUN');
  const thisYear = TODAY.slice(0, 4);
  const newYear = all.filter(p => p.approveDate.slice(0, 4) === thisYear);
  const done = all.filter(p => p.status === 'DONE');
  const overdue = all.filter(p => p.phases.some(isOverdue));
  const totContract = all.reduce((s, p) => s + p.contractTotal, 0);
  const totPaid = all.reduce((s, p) => s + p.paidTotal, 0);

  const stat = [
    { t: '项目总数', v: all.length, c: '#1677ff', f: '全部形态' },
    { t: '进行中', v: running.length, c: '#13c2c2', f: '含已进入流程项目' },
    { t: thisYear + ' 年新建', v: newYear.length, c: '#722ed1', f: '按立项日期' },
    { t: '已完结', v: done.length, c: '#52c41a', f: '终验通过并归档' },
    { t: '逾期预警', v: overdue.length, c: '#ff4d4f', f: '当前阶段计划逾期', },
    { t: '合同总额', v: moneyW(totContract), c: '#0958d9', big: false },
    { t: '累计实付', v: moneyW(totPaid), c: '#389e0d', big: false },
  ];

  /* 我的待办：当前用户负责项目中的"进行中"阶段 */
  const myTodos = [];
  PROJECTS.forEach(p => {
    if (p.manager !== CURRENT_USER.name) return;
    p.phases.forEach(ph => {
      if (ph.status === 'ACT') myTodos.push({ proj: p, ph });
    });
  });

  /* 逾期预警列表 */
  const overRows = overdue.slice(0, 6).map(p => {
    const ph = p.phases.find(isOverdue);
    return '<div class="log-item"><div class="dot2" style="background:#ff4d4f"></div><div style="flex:1">' +
      '<a class="nm" data-act="open" data-id="' + p.code + '">' + esc(p.name) + '</a>' +
      '<div class="muted" style="font-size:12px">阶段「' + ph.name + '」计划 ' + ph.planE + ' 完成，已逾期 <span style="color:#ff4d4f">' + overdueDays(ph) + '</span> 天</div></div>' +
      '<div class="tm">' + esc(p.manager) + '</div></div>';
  }).join('');

  /* 近期计划初验/终验 */
  const dueChecks = [];
  PROJECTS.forEach(p => {
    p.phases.forEach(ph => {
      if (!ph.planE) return;
      const d = diffDays(TODAY, ph.planE);
      if (d >= 0 && d <= 60 && (ph.name.includes('初验') || ph.name.includes('终验')) && (ph.status === 'ACT' || ph.status === 'WAIT')) {
        dueChecks.push({ proj: p, ph, d });
      }
    });
  });
  dueChecks.sort((a, b) => a.d - b.d);
  const checkRows = dueChecks.slice(0, 6).map(x =>
    '<div class="log-item"><div class="dot2" style="background:#faad14"></div><div style="flex:1">' +
    '<a class="nm" data-act="open" data-id="' + x.proj.code + '">' + esc(x.proj.name) + '</a>' +
    '<div class="muted" style="font-size:12px">计划 <span style="color:#ad6800">' + x.ph.planE + '</span> 开展「' + x.ph.name + '」　剩余 ' + x.d + ' 天</div></div>' +
    '<div class="tm">' + esc(x.ph.name.slice(0, 2)) + '</div></div>').join('');

  const todoRows = myTodos.slice(0, 6).map(x =>
    '<div class="log-item"><div class="dot2"></div><div style="flex:1">' +
    '<a class="nm" data-act="open" data-id="' + x.proj.code + '">' + esc(x.proj.name) + '</a>' +
    '<div class="muted" style="font-size:12px">当前阶段「' + x.ph.name + '」进度 ' + x.ph.pct + '%，计划完成 ' + x.ph.planE + (isOverdue(x.ph) ? '　<span style="color:#ff4d4f">已逾期</span>' : '') + '</div></div>' +
    '<div class="tm">' + statusBadge(x.proj.status) + '</div></div>').join('');

  const recent = PROJECTS.slice(0, 6).map(p =>
    '<tr><td class="nm" data-act="open" data-id="' + p.code + '">' + esc(p.name) + '</td>' +
    '<td class="cell-min">' + typeTag(p.type) + '</td>' +
    '<td class="cell-min">' + esc(p.curStage) + '</td>' +
    '<td class="cell-min" style="width:130px">' + progressBar(p.overall, false) + '</td>' +
    '<td class="cell-min">' + statusBadge(p.status) + '</td>' +
    '<td class="cell-min muted">' + p.updated + '</td></tr>').join('');

  return head('首页工作台', '单位信息化项目总体情况一览 · 演示数据截止 ' + TODAY) +
    '<div class="stat-grid grid">' + stat.map(s =>
      '<div class="card stat-card"><div class="bar" style="background:' + s.c + '"></div>' +
      '<div class="t">' + s.t + '</div><div class="v"' + (s.big === false ? ' style="font-size:19px"' : '') + '>' + s.v +
      (s.big === false ? '' : '<small>个/笔</small>') + '</div><div class="f">' + s.f + '</div></div>').join('') + '</div>' +
    '<div class="grid" style="grid-template-columns:1fr 1fr;margin-top:14px">' +
      '<div class="card card-pad"><div class="hd"><h4 style="font-size:14px">我的待办（' + myTodos.length + '）</h4></div>' +
        (todoRows || '<div class="empty">暂无待推进阶段</div>') + '</div>' +
      '<div class="card card-pad"><div class="hd"><h4 style="font-size:14px">近期计划初验 / 终验（60 天内）</h4></div>' +
        (checkRows || '<div class="empty">近期无验收节点</div>') + '</div>' +
    '</div>' +
    '<div class="grid" style="grid-template-columns:1fr 1fr;margin-top:14px">' +
      '<div class="card card-pad"><div class="hd"><h4 style="font-size:14px;color:#ff4d4f">逾期预警</h4></div>' +
        (overRows || '<div class="empty">暂无逾期阶段</div>') + '</div>' +
      '<div class="card card-pad"><div class="hd"><h4 style="font-size:14px">最近更新项目</h4></div>' +
        '<table class="tbl"><tbody>' + recent + '</tbody></table></div>' +
    '</div>';
}

/* ================= 2. 项目管理（列表 / 卡片） ================= */
function filteredProjects(extra) {
  const f = Object.assign({}, STATE.list, extra || {});
  let arr = PROJECTS.filter(p => {
    if (f.type && p.type !== f.type) return false;
    if (f.status && p.status !== f.status) return false;
    if (f.unit && p.unit !== f.unit) return false;
    if (f.year && p.approveDate.slice(0, 4) !== f.year) return false;
    if (f.kw) {
      const hay = [p.name, p.code, p.vendor, p.approveNo, p.unit].join(' ').toLowerCase();
      if (!hay.includes(f.kw.toLowerCase())) return false;
    }
    return true;
  });
  return arr;
}

function selOpts(opts, cur, allLabel) {
  return '<option value="">' + allLabel + '</option>' + opts.map(o =>
    '<option value="' + esc(o) + '"' + (o === cur ? ' selected' : '') + '>' + esc(o) + '</option>').join('');
}

function pager(total, page) {
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  let nums = [];
  if (pages <= 7) for (let i = 1; i <= pages; i++) nums.push(i);
  else {
    nums = [1];
    let s = Math.max(2, page - 1), e = Math.min(pages - 1, page + 1);
    if (page <= 3) e = 4;
    if (page >= pages - 2) s = pages - 3;
    if (s > 2) nums.push('…');
    for (let i = s; i <= e; i++) nums.push(i);
    if (e < pages - 1) nums.push('…');
    nums.push(pages);
  }
  return '<div class="pager"><span class="info">共 ' + total + ' 条 · 第 ' + page + '/' + pages + ' 页</span>' +
    '<button class="pg" data-act="page" data-p="' + (page - 1) + '"' + (page <= 1 ? ' disabled' : '') + '>上一页</button>' +
    nums.map(n => typeof n === 'number'
      ? '<button class="pg' + (n === page ? ' on' : '') + '" data-act="page" data-p="' + n + '">' + n + '</button>'
      : '<span class="muted">…</span>').join('') +
    '<button class="pg" data-act="page" data-p="' + (page + 1) + '"' + (page >= pages ? ' disabled' : '') + '>下一页</button></div>';
}

function viewList() {
  const f = STATE.list;
  const arr = filteredProjects();
  const pages = Math.max(1, Math.ceil(arr.length / PAGE_SIZE));
  const page = Math.min(f.page, pages);
  const slice = arr.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const selType = '<select class="sel js-lf" data-k="type"><option value="">全部类型</option>' +
    Object.keys(TYPES).map(k => '<option value="' + k + '"' + (k === f.type ? ' selected' : '') + '>' + TYPES[k] + '</option>').join('') + '</select>';
  const selStatus = '<select class="sel js-lf" data-k="status"><option value="">全部状态</option>' +
    Object.keys(STATUS_META).map(k => '<option value="' + k + '"' + (k === f.status ? ' selected' : '') + '>' + STATUS_META[k].text + '</option>').join('') + '</select>';
  const selUnit = '<select class="sel js-lf" data-k="unit">' + selOpts(UNIT_OPTIONS, f.unit, '全部甲方单位') + '</select>';
  const selYear = '<select class="sel js-lf" data-k="year">' + selOpts(YEAR_OPTIONS, f.year, '全部年度') + '</select>';

  const toolbar =
    '<div class="toolbar"><div class="filters">' +
      '<div class="g"><label>关键字</label><input id="list-kw" class="inp" placeholder="项目名称 / 编号 / 供应商 / 批复文号" value="' + esc(f.kw) + '"></div>' +
      '<div class="g"><label>类型</label>' + selType + '</div>' +
      '<div class="g"><label>状态</label>' + selStatus + '</div>' +
      '<div class="g"><label>甲方单位</label>' + selUnit + '</div>' +
      '<div class="g"><label>年度</label>' + selYear + '</div>' +
      '<button class="btn btn-p" data-act="search">查 询</button>' +
      '<button class="btn btn-ghost" data-act="reset">重 置</button>' +
    '</div>' +
    '<div style="display:flex;gap:10px;align-items:center">' +
      '<div class="radios">' +
        '<button class="radio-btn' + (f.mode === 'list' ? ' on' : '') + '" data-act="mode" data-mode="list">列表视图</button>' +
        '<button class="radio-btn' + (f.mode === 'card' ? ' on' : '') + '" data-act="mode" data-mode="card">卡片视图</button>' +
      '</div>' +
      '<button class="btn btn-p" data-act="newproj">＋ 新建项目</button>' +
    '</div></div>';

  let body;
  if (f.mode === 'list') {
    body = '<div class="card"><table class="tbl"><thead><tr>' +
      '<th>项目名称 / 编号</th><th>类型</th><th>甲方单位</th><th>承接部门</th><th>负责人</th>' +
      '<th>当前阶段</th><th style="width:120px">整体进度</th><th class="cell-min">合同金额</th><th>状态</th><th class="cell-min">操作</th>' +
      '</tr></thead><tbody>' +
      slice.map(p =>
        '<tr>' +
        '<td><div class="nm" data-act="open" data-id="' + p.code + '">' + esc(p.name) + '</div>' +
        '<div class="muted" style="font-size:11px">' + p.code + '</div></td>' +
        '<td class="cell-min">' + typeTag(p.type) + '</td>' +
        '<td class="cell-min">' + esc(p.unit) + '</td>' +
        '<td class="cell-min">' + esc(p.dept) + '</td>' +
        '<td class="cell-min">' + esc(p.manager) + '</td>' +
        '<td class="cell-min">' + esc(p.curStage) + '</td>' +
        '<td>' + progressBar(p.overall, false) + '</td>' +
        '<td class="cell-min" style="font-variant-numeric:tabular-nums">' + (p.contractAmount ? money(p.contractTotal) : '—') + '</td>' +
        '<td class="cell-min">' + statusBadge(p.status) + '</td>' +
        '<td class="cell-min"><a class="btn-link" data-act="open" data-id="' + p.code + '">详情</a></td>' +
        '</tr>').join('') + '</tbody></table>' +
      (slice.length ? '' : '<div class="empty">未查询到符合条件的项目，请调整筛选条件</div>') +
      '</div>';
  } else {
    body = '<div class="cardgrid">' + slice.map(p =>
      '<div class="pcard" data-act="open" data-id="' + p.code + '">' +
        '<div class="row1"><div><div class="nm">' + esc(p.name) + '</div><div class="code">' + p.code + ' · ' + esc(p.unit) + '</div></div>' +
        statusBadge(p.status) + '</div>' +
        '<div><div style="display:flex;gap:8px;flex-wrap:wrap">' + typeTag(p.type) + '<span class="badge b-gray">' + esc(p.curStage) + '</span></div></div>' +
        '<div class="prog"><div style="flex:1"><div class="muted" style="font-size:12px;margin-bottom:4px">整体进度</div>' + progressBar(p.overall, false) + '</div></div>' +
        '<div class="kv"><span>负责人 <b>' + esc(p.manager) + '</b></span><span>合同 <b>' + (p.contractAmount ? money(p.contractTotal) : '—') + '</b></span></div>' +
        '<div class="kv"><span>承接部门 <b>' + esc(p.dept) + '</b></span><span>更新 ' + p.updated + '</span></div>' +
      '</div>').join('') + '</div>' +
      (slice.length ? '' : '<div class="card empty">未查询到符合条件的项目，请调整筛选条件</div>');
  }

  return head('项目管理', '政府信息化项目台账 · 支持列表 / 卡片视图、分类筛选、关键字检索与分页') +
    toolbar + body + pager(arr.length, page);
}

/* ================= 3. 项目详情 ================= */
function viewDetail() {
  const p = getProject(STATE.pid);
  if (!p) return '<div class="card empty">项目不存在或已删除</div>';

  const tabs = [
    { k: 'flow', t: '流程进展', extra: p.phases.length },
    { k: 'info', t: '项目信息' },
    { k: 'fund', t: '资金情况', extra: p.payments.length },
    { k: 'files', t: '附件中心', extra: p.phases.reduce((s, x) => s + x.files.length, 0) },
    { k: 'log', t: '操作日志' },
  ];

  const tabContent = {
    flow: tabFlow(p), info: tabInfo(p), fund: tabFund(p), files: tabFiles(p), log: tabLog(p),
  }[STATE.tab];

  return '<div class="detail-back" data-act="go" data-view="list">← 返回项目管理</div>' +
    '<div class="proj-head">' +
      '<div style="flex:1;min-width:260px">' +
        '<div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">' +
          '<span class="pn">' + esc(p.name) + '</span>' + typeTag(p.type) + statusBadge(p.status) +
        '</div>' +
        '<div class="meta"><span>编号 ' + p.code + '</span><span>甲方：' + esc(p.unit) + '</span>' +
        '<span>承接部门：' + esc(p.dept) + '</span><span>负责人：' + esc(p.manager) + '</span>' +
        '<span>立项：' + p.approveDate + '</span></div>' +
      '</div>' +
      '<div class="acts"><div class="stat">' +
        '<div class="it"><div class="l">整体进度</div><div class="n" style="color:#73d13d">' + p.overall + '%</div></div>' +
        '<div class="it"><div class="l">当前阶段</div><div class="n" style="font-size:14px">' + esc(p.curStage) + '</div></div>' +
        '<div class="it"><div class="l">合同总额</div><div class="n" style="font-size:14px">' + moneyW(p.contractTotal) + '</div></div>' +
        '<div class="it"><div class="l">累计实付</div><div class="n" style="font-size:14px">' + moneyW(p.paidTotal) + '</div></div>' +
      '</div>' +
      '<div style="margin-top:12px;display:flex;gap:8px">' +
        '<button class="btn btn-ghost" style="color:#fff;border-color:rgba(255,255,255,.4)" data-act="editinfo">编辑基本信息</button>' +
        '<button class="btn btn-ghost" style="color:#fff;border-color:rgba(255,255,255,.4)" data-act="export">导出项目档案</button>' +
      '</div></div>' +
    '</div>' +
    '<div class="tabs">' + tabs.map(t =>
      '<div class="tab' + (t.k === STATE.tab ? ' on' : '') + '" data-act="tab" data-tab="' + t.k + '">' + t.t +
      (t.extra != null ? ' <span class="muted">' + t.extra + '</span>' : '') + '</div>').join('') + '</div>' +
    '<div>' + tabContent + '</div>';
}

/* ---- 详情页签：流程进展（时间线 + 每阶段附件） ---- */
function phaseItem(p, proj) {
  const od = isOverdue(p);
  const odText = od ? '<div style="background:#fff1f0;color:#cf1322;border-radius:6px;padding:6px 12px;font-size:12px;margin-bottom:8px">⚠ 本阶段计划完成日期 ' + p.planE + ' 已逾期 ' + overdueDays(p) + ' 天</div>' : '';
  const meta =
    '<div class="ph-meta">' +
      '<span class="it"><b>计划</b>' + p.planS + ' ~ ' + p.planE + '</span>' +
      (p.actS ? '<span class="it"><b>实际开始</b>' + p.actS + '</span>' : '') +
      (p.actE ? '<span class="it"><b>实际完成</b>' + p.actE + '</span>' : '') +
      '<span class="it"><b>负责人</b>' + esc(proj.manager) + '</span>' +
      (p.pct ? '<span class="it"><b>完成比例</b>' + p.pct + '%</span>' : '') +
    '</div>';
  const note = p.note ? '<div class="ph-note">' + esc(p.note) + '</div>' : '';
  const chips = p.files.length
    ? '<div class="chips">' + p.files.map(f =>
        '<span class="chip" data-act="file" data-f="' + esc(f.name) + '"><span class="ext">' + f.ext + '</span>' +
        esc(f.name) + '<span class="sz">' + f.size + '</span></span>').join('') + '</div>'
    : '<div class="muted" style="font-size:12px">暂无附件</div>';
  const actBtns = p.status !== 'WAIT'
    ? '<div style="display:flex;gap:6px;flex-shrink:0">' +
        '<button class="btn btn-ghost btn-sm" data-act="phase-demo" data-t="记录/编辑阶段信息">记录</button>' +
        '<button class="btn btn-p btn-sm" data-act="phase-demo" data-t="为该阶段上传附件">上传附件</button></div>'
    : '<span class="muted" style="font-size:12px;flex-shrink:0">等待前置阶段完成</span>';

  return '<div class="tl-item ' + p.status.toLowerCase() + '">' +
    '<div class="phase-card' + (od ? ' overdue' : '') + '">' +
      '<div class="phase-head"><div class="tt">' + phBadge(p.status) +
        '<span class="no">STEP ' + (p.i + 1) + ' · 权重 ' + p.w + '</span>' +
        '<span class="nm">' + esc(p.name) + '</span></div>' + actBtns + '</div>' +
      odText + meta + note + chips +
      (p.status === 'ACT' ? '<div class="mt8">' + progressBar(p.pct, false) + '</div>' : '') +
    '</div></div>';
}

function tabFlow(p) {
  return '<div class="card card-pad" style="margin-bottom:14px">' +
    '<div style="font-size:13px;color:var(--text2);line-height:1.8">' +
      '按 <b>' + esc(p.name) + '</b> 的生命周期自上而下推进（' + (p.type === 'HW' ? '硬件项目流程 · 9 个阶段' : '软件项目流程 · 11 个阶段') + '）。' +
      '每个阶段可记录经办信息并上传<b>阶段附件</b>；已完成阶段自动汇总进入整体进度。' +
    '</div></div>' +
    '<div class="tl">' + p.phases.map(ph => phaseItem(ph, p)).join('') + '</div>' +
    '<div class="card card-pad mt14" style="background:#fffbe6;border:1px solid #ffe58f">' +
      '<div style="font-size:12px;color:#ad6800">演示说明：真实系统中，点击「记录 / 上传附件」将打开阶段详情编辑（经办记录、关键结果字段、拖拽上传）。本 Demo 仅展示交互入口与数据形态。</div></div>';
}

/* ---- 详情页签：项目信息 ---- */
function kvItem(k, v) { return '<div class="info-item"><div class="k">' + k + '</div><div class="v">' + (v == null || v === '' ? '<span class="muted">—</span>' : v) + '</div></div>'; }

function tabInfo(p) {
  const pauseNote = p.status === 'PAUSE' || p.status === 'STOP'
    ? '<div class="card card-pad mt8" style="background:#fff1f0;border:1px solid #ffccc7;font-size:13px;color:#cf1322;margin-top:14px">' +
      (p.status === 'PAUSE' ? '【暂停说明】' : '【中止说明】') + esc((p.notes && (p.notes[p.cur] || p.notes[2])) || (p.status === 'PAUSE' ? '项目暂停中。' : '项目已中止。')) + '</div>' : '';
  const summary = p.contentSummary ||
    ('本项目属' + p.typeText + '。立项时间 ' + p.approveDate + '，概算 ' + money(p.budget) +
     '，合同金额 ' + money(p.contractAmount) + '，由 ' + esc(p.unit) + ' 委托、' + esc(p.dept) + ' 牵头管理。' +
     '建设与验收过程按阶段推进，详见「流程进展」页签。');

  const sec = (t, items) =>
    '<div class="info-sec"><div class="t">' + t + '</div><div class="info-grid">' + items + '</div></div>';

  return sec('项目标识', [
    kvItem('项目名称', esc(p.name)), kvItem('项目编号', p.code),
    kvItem('项目类型', typeTag(p.type)), kvItem('项目状态', statusBadge(p.status)),
    kvItem('项目来源', p.source || null), kvItem('整体进度', p.overall + '%'),
  ]) +
  sec('建设主体', [
    kvItem('甲方单位', esc(p.unit)), kvItem('承接部门', esc(p.dept)),
    kvItem('项目经理', esc(p.manager)), kvItem('参与人员', '李娜、王强等（示例）'),
    kvItem('供应商', esc(p.vendor || '')), kvItem('供应商联系人', p.vendorContact || null),
  ]) +
  sec('批复与预算', [
    kvItem('批复文号', esc(p.approveNo || '')), kvItem('概算 / 预算金额', money(p.budget)),
    kvItem('资金来源', esc(p.fundSource || '')), kvItem('立项日期', p.approveDate),
  ]) +
  sec('招标与合同', [
    kvItem('招标方式', esc(p.bidType || '')), kvItem('中标金额', money(p.bidAmount)),
    kvItem('合同金额', money(p.contractAmount)), kvItem('变更金额', p.changeAmount ? money(p.changeAmount) : '无'),
    kvItem('合同当前总额', money(p.contractTotal)),
  ]) +
  sec('时间计划', [
    kvItem('计划开始', p.planStart || p.approveDate), kvItem('计划完成', esc(p.planFinish || '')),
    kvItem('实际完成（终验）', esc(p.finishDate || '')),
    kvItem('最近更新', p.updated),
  ]) +
  sec('建设内容', [
    kvItem('建设内容摘要', esc(summary)),
    kvItem('主要成果物', esc(p.deliverables || '按阶段形成批复、合同、验收报告及运维移交资料（详见各阶段附件）')),
  ]) +
  pauseNote +
  '<div class="card card-pad mt8" style="font-size:12px;color:#86909c">' +
  '注：字段结构依据《政府信息化项目管理系统设计方案 v0.1》第 5 章设计，可在评审后增删调整。</div>';
}

/* ---- 详情页签：资金情况 ---- */
function tabFund(p) {
  const rate = p.contractTotal ? Math.round(p.paidTotal / p.contractTotal * 100) : 0;
  const cards = [
    { t: '预算金额（概算）', v: money(p.budget), c: '#0958d9' },
    { t: '合同当前总额', v: money(p.contractTotal), c: '#1677ff', f: '合同 + 变更' },
    { t: '累计实付', v: money(p.paidTotal), c: '#52c41a', f: '合同执行率 ' + rate + '%' },
    { t: '待付金额', v: money(p.unpaid), c: '#faad14', f: '含质保金等未付节点' },
  ];
  const rows = p.payments.map(py =>
    '<tr><td class="fw6">' + py.name + ' <span class="muted">(' + Math.round(py.ratio * 100) + '%)</span></td>' +
    '<td class="cell-min" style="font-variant-numeric:tabular-nums">' + money(py.planAmount) + '</td>' +
    '<td class="cell-min">' + (py.planDate || '—') + '</td>' +
    '<td class="cell-min" style="font-variant-numeric:tabular-nums">' + (py.paidAmount ? money(py.paidAmount) : '—') + '</td>' +
    '<td class="cell-min">' + (py.paidDate || '—') + '</td>' +
    '<td class="cell-min">' + (py.status === '已支付' ? '<span class="badge b-fin">已支付</span>' : '<span class="badge b-pause">待支付</span>') + '</td></tr>').join('');

  return '<div class="stat-grid grid" style="grid-template-columns:repeat(4,1fr)">' + cards.map(c =>
    '<div class="card stat-card"><div class="bar" style="background:' + c.c + '"></div>' +
    '<div class="t">' + c.t + '</div><div class="v" style="font-size:19px">' + c.v + '</div>' +
    (c.f ? '<div class="f">' + c.f + '</div>' : '') + '</div>').join('') + '</div>' +
    '<div class="card mt14"><div class="card-pad" style="display:flex;justify-content:space-between;align-items:center;padding-bottom:12px">' +
      '<h4 style="font-size:14px">合同里程碑付款节点</h4>' +
      '<button class="btn btn-ghost btn-sm" data-act="addpay">＋ 新增付款记录</button></div>' +
    '<table class="tbl"><thead><tr><th>付款节点</th><th>计划金额</th><th>计划付款日期</th><th>实付金额</th><th>实际付款日期</th><th>状态</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>' +
    '<div class="card-pad" style="padding-top:12px;font-size:12px;color:#86909c">' +
    '触发条件与默认比例：预付款于合同签订后支付；到货款于到货验收合格后支付；初验/终验款于对应验收通过后支付；质保金于质保期满支付（比例可按合同在流程模板中调整）。</div></div>';
}

/* ---- 详情页签：附件中心 ---- */
function tabFiles(p) {
  let n = 0;
  const rows = [];
  p.phases.forEach(ph => {
    ph.files.forEach(f => {
      n++;
      rows.push('<tr><td class="cell-min muted">' + esc(ph.name) + '</td>' +
        '<td><span class="chip" data-act="file" data-f="' + esc(f.name) + '"><span class="ext">' + f.ext + '</span>' + esc(f.name) + '</span></td>' +
        '<td class="cell-min">' + (ph.status === 'DONE' ? '<span class="badge b-fin">' + PHASE_LABEL.DONE + '</span>' : phBadge(ph.status)) + '</td>' +
        '<td class="cell-min">' + f.size + '</td>' +
        '<td class="cell-min">' + esc(f.up) + '</td>' +
        '<td class="cell-min">' + f.date + '</td></tr>');
    });
  });
  return '<div class="card card-pad" style="margin-bottom:14px;display:flex;justify-content:space-between;align-items:center">' +
    '<div><b>' + n + '</b> 个附件 · 全部按所属阶段归档（另在「流程进展」中各阶段可预览）</div>' +
    '<div style="display:flex;gap:8px"><button class="btn btn-ghost btn-sm" data-act="batch-download">批量下载</button>' +
    '<button class="btn btn-p btn-sm" data-act="phase-demo" data-t="选择项目阶段上传附件">上传附件</button></div></div>' +
    '<div class="card"><table class="tbl"><thead><tr><th>所属阶段</th><th>文件名</th><th>阶段状态</th><th>大小</th><th>上传人</th><th>上传时间</th></tr></thead>' +
    '<tbody>' + (rows.join('') || '<tr><td colspan="6"><div class="empty">暂无附件</div></td></tr>') + '</tbody></table></div>';
}

/* ---- 详情页签：操作日志 ---- */
function tabLog(p) {
  return '<div class="card card-pad">' +
    p.logs.map(l =>
      '<div class="log-item"><div class="dot2"></div><div style="flex:1"><span class="who">' + esc(l.who) + '</span>' +
      esc(l.act) + '</div><div class="tm">' + l.tm + '</div></div>').join('') +
    '</div>';
}

/* ================= 4. 项目统计 ================= */
function niceMax(v) {
  const m = v * 1.15;
  const p = Math.pow(10, Math.floor(Math.log10(m || 1)));
  return Math.ceil(m / p) * p;
}
function donutHtml(items, total) {
  if (!total) return '<div class="empty">当前条件下无项目数据</div>';
  let acc = 0;
  const segs = items.map(it => {
    const a = acc, p = total ? it.v / total * 100 : 0;
    acc += p;
    return { c: it.c, pct: p, from: a, to: acc, label: it.l };
  }).filter(x => x.pct > 0);
  const grad = 'conic-gradient(' + segs.map(x =>
    x.c + ' ' + x.from.toFixed(2) + '% ' + x.to.toFixed(2) + '%').join(',') + ')';
  const lg = items.map(it =>
    '<div class="li"><span class="dot" style="background:' + it.c + '"></span><span class="nm">' + it.l + '</span>' +
    '<div class="track"><div class="in" style="width:' + (total ? it.v / total * 100 : 0) + '%;background:' + it.c + '"></div></div>' +
    '<span class="cnt">' + it.v + ' 个 · ' + (total ? Math.round(it.v / total * 100) : 0) + '%</span></div>').join('');
  return '<div class="donut-wrap"><div class="donut" style="background:' + grad + '">' +
    '<div class="hole"><b>' + total + '</b><span>项目总数</span></div></div>' +
    '<div class="donut-lg">' + lg + '</div></div>';
}
function lineSvg(months, values) {
  const W = 640, H = 210, L = 48, R = 14, T = 16, B = 30;
  const maxV = Math.max.apply(null, values.concat([1])) * 1.12;
  const iw = W - L - R, ih = H - T - B;
  const x = i => L + iw * (months.length <= 1 ? 0.5 : i / (months.length - 1));
  const y = v => T + ih - (v / maxV) * ih;
  const pts = values.map((v, i) => x(i).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
  let grid = '';
  for (let g = 0; g <= 4; g++) {
    const yy = T + ih * g / 4;
    const val = maxV * (1 - g / 4);
    grid += '<line x1="' + L + '" y1="' + yy + '" x2="' + (W - R) + '" y2="' + yy + '" stroke="#f0f1f3" stroke-width="1"/>' +
      '<text x="' + (L - 6) + '" y="' + (yy + 4) + '" text-anchor="end" font-size="10" fill="#86909c">' +
      (val >= 100 ? Math.round(val) : val.toFixed(1)) + '</text>';
  }
  const dots = values.map((v, i) =>
    '<circle cx="' + x(i).toFixed(1) + '" cy="' + y(v).toFixed(1) + '" r="3" fill="#1677ff"/>' +
    (i === values.length - 1 ? '<text x="' + (x(i) - 6) + '" y="' + (y(v) - 7) + '" font-size="10" fill="#0958d9" text-anchor="end">' + v.toFixed(1) + '</text>' : '')).join('');
  const xl = months.map((m, i) => (i % 3 === 0 || i === months.length - 1
    ? '<text x="' + x(i) + '" y="' + (H - 8) + '" text-anchor="middle" font-size="10" fill="#86909c">' + m + '</text>' : '')).join('');
  return '<svg viewBox="0 0 ' + W + ' ' + H + '">' + grid +
    '<polyline fill="none" stroke="#69b1ff" stroke-width="2" points="' + pts + '"/>' +
    dots + xl + '</svg>';
}
function monthAdd(m, k) {
  const p = m.split('-');
  let y = +p[0], mo = +p[1] + k;
  y += Math.floor((mo - 1) / 12); mo = ((mo - 1) % 12 + 12) % 12 + 1;
  return y + '-' + String(mo).padStart(2, '0');
}

function viewStats() {
  const st = STATE.stats;
  const arr = PROJECTS.filter(p => {
    if (st.type && p.type !== st.type) return false;
    if (st.status && p.status !== st.status) return false;
    if (st.year && p.approveDate.slice(0, 4) !== st.year) return false;
    return true;
  });

  const statusItems = ['RUN', 'DONE', 'PAUSE', 'STOP'].map(k => ({
    l: STATUS_META[k].text, v: arr.filter(p => p.status === k).length, c: { RUN: '#1677ff', DONE: '#52c41a', PAUSE: '#faad14', STOP: '#ff4d4f' }[k],
  }));
  const typeItems = ['HW', 'SW'].map(k => ({ l: TYPES[k], v: arr.filter(p => p.type === k).length, c: k === 'HW' ? '#1677ff' : '#722ed1' }));

  /* 年度资金分组柱状 */
  const years = [...new Set(arr.map(p => p.approveDate.slice(0, 4)))].sort();
  const yearRows = years.map(y => {
    const ps = arr.filter(p => p.approveDate.slice(0, 4) === y);
    return {
      y,
      budget: ps.reduce((s, p) => s + p.budget, 0),
      contract: ps.reduce((s, p) => s + p.contractTotal, 0),
      paid: ps.reduce((s, p) => s + p.paidTotal, 0),
    };
  });
  const maxM = niceMax(Math.max(1, ...yearRows.map(r => Math.max(r.budget, r.contract, r.paid))));
  const barRows = yearRows.map(r => {
    const col = (v, c, label) => {
      const h = Math.max(v ? Math.round(v / maxM * 130) : 2, 2);
      const show = h > 22;
      return '<div class="col" title="' + label + '：' + moneyW(v) + '"><div class="bar" style="height:' + h + 'px;background:' + c + '">' +
        (show ? '<span style="font-size:10px;color:#fff;position:relative;top:-14px">' + moneyW(v) + '</span>' : '') + '</div></div>';
    };
    return '<div class="row"><div class="cat">' + r.y + ' 年</div><div class="bars" style="height:170px">' +
      col(r.budget, '#a3c6ff', '预算') + col(r.contract, '#1677ff', '合同') + col(r.paid, '#52c41a', '实付') +
      '</div></div>';
  }).join('');

  /* 流程漏斗：宏观阶段在办项目数 */
  const stageKey = p => {
    const i = p.phases.findIndex(x => x.status === 'ACT');
    if (i < 0) return p.status === 'DONE' ? null : '其他';
    const ti = i;
    return p.type === 'HW'
      ? (ti === 0 ? '立项' : ti === 1 ? '招投标' : ti === 2 ? '合同签订' : ti <= 4 ? '建设实施' : ti === 5 ? '初验' : ti === 6 ? '建设实施' : ti === 7 ? '终验' : '质保运维')
      : (ti === 0 ? '立项' : ti === 1 ? '需求设计' : ti === 2 ? '招投标' : ti === 3 ? '合同签订' : ti <= 7 ? '建设实施' : ti === 8 ? '初验' : ti === 9 ? '终验' : '质保运维');
  };
  const stageOrder = ['立项', '需求设计', '招投标', '合同签订', '建设实施', '初验', '终验', '质保运维', '其他'];
  const stageCount = {};
  arr.forEach(p => { const k = stageKey(p); if (k) stageCount[k] = (stageCount[k] || 0) + 1; });
  const stageMax = Math.max(1, ...Object.values(stageCount));
  const stageColors = ['#69b1ff', '#91caff', '#4096ff', '#1677ff', '#0958d9', '#13c2c2', '#36cfc9', '#5cdbd3', '#a0d911'];
  const funnelRows = stageOrder.filter(k => stageCount[k]).map((k, i) =>
    '<div class="hbar-row"><span class="nm">' + k + '</span>' +
    '<div class="track"><div class="in" style="width:' + (stageCount[k] / stageMax * 100) + '%;background:' + stageColors[i % stageColors.length] + '">' +
    (stageCount[k] / stageMax > 0.15 ? stageCount[k] : '') + '</div></div>' +
    '<span class="cnt">' + stageCount[k] + ' 个</span></div>').join('');

  /* 实付累计趋势 */
  const paidMonths = [];
  arr.forEach(p => p.payments.forEach(py => { if (py.paidDate) paidMonths.push(py.paidDate.slice(0, 7)); }));
  paidMonths.sort();
  let mStart = paidMonths[0] || '2024-01';
  let mEnd = paidMonths[paidMonths.length - 1] || TODAY.slice(0, 7);
  if (mEnd < mStart) { const t = mStart; mStart = mEnd; mEnd = t; }
  const months = [];
  let mm = mStart;
  let guard = 0;
  while (mm <= mEnd && guard < 60) { months.push(mm); mm = monthAdd(mm, 1); guard++; }
  const byMonth = {};
  paidMonths.forEach(m => { byMonth[m] = (byMonth[m] || 0) + 1; });
  /* 月度金额（按付款记录平均分摊不可取，直接用付款记录总数做量级，改用金额求和） */
  const monthlyAmt = {};
  arr.forEach(p => p.payments.forEach(py => {
    if (py.paidDate) monthlyAmt[py.paidDate.slice(0, 7)] = (monthlyAmt[py.paidDate.slice(0, 7)] || 0) + py.paidAmount;
  }));
  let cum = 0;
  const lineVals = months.map(m => {
    cum += (monthlyAmt[m] || 0) / 10000;
    return Math.round(cum * 10) / 10;
  });
  const lineHtml = months.length && lineVals.some(v => v > 0)
    ? '<div class="line-wrap">' + lineSvg(months, lineVals) + '</div>'
    : '<div class="empty">当前条件下暂无已支付记录</div>';

  /* 单位排名 */
  const unitMap = {};
  arr.forEach(p => {
    if (!unitMap[p.unit]) unitMap[p.unit] = { n: 0, contract: 0, paid: 0 };
    unitMap[p.unit].n++;
    unitMap[p.unit].contract += p.contractTotal;
    unitMap[p.unit].paid += p.paidTotal;
  });
  const unitRows = Object.keys(unitMap).map(u => Object.assign({ u }, unitMap[u])).sort((a, b) => b.contract - a.contract).slice(0, 8);

  const overCount = arr.filter(p => p.phases.some(isOverdue)).length;
  const overL = arr.filter(p => p.phases.some(isOverdue)).map(p => {
    const ph = p.phases.find(isOverdue);
    return '<tr><td class="nm" data-act="open" data-id="' + p.code + '">' + esc(p.name) + '</td>' +
      '<td class="cell-min">' + esc(ph.name) + '</td>' +
      '<td class="cell-min">' + ph.planE + '</td>' +
      '<td class="cell-min" style="color:#ff4d4f">逾期 ' + overdueDays(ph) + ' 天</td></tr>';
  }).join('');

  const filters = '<div class="card card-pad" style="margin-bottom:14px"><div class="filters">' +
    '<div class="g"><label>年度</label><select class="sel js-sf" data-k="year">' + selOpts(YEAR_OPTIONS, st.year, '全部年度') + '</select></div>' +
    '<div class="g"><label>类型</label><select class="sel js-sf" data-k="type"><option value="">全部类型</option>' +
    Object.keys(TYPES).map(k => '<option value="' + k + '"' + (k === st.type ? ' selected' : '') + '>' + TYPES[k] + '</option>').join('') + '</select></div>' +
    '<div class="g"><label>状态</label><select class="sel js-sf" data-k="status"><option value="">全部状态</option>' +
    Object.keys(STATUS_META).map(k => '<option value="' + k + '"' + (k === st.status ? ' selected' : '') + '>' + STATUS_META[k].text + '</option>').join('') +
    '</select></div>' +
    '<button class="btn btn-ghost" data-act="stat-reset">重 置</button>' +
    '<span class="muted">当前口径：' + arr.length + ' 个项目</span></div></div>';

  return head('项目统计', '进度评判 · 资金使用 · 周期效率（数据随上方筛选联动）') + filters +
    '<div class="chart-grid grid">' +
      chartCard('项目状态构成', donutHtml(statusItems, arr.length)) +
      chartCard('按立项年度：预算 vs 合同 vs 实付', legendHtml([
        { l: '预算金额', c: '#a3c6ff' }, { l: '合同金额', c: '#1677ff' }, { l: '累计实付', c: '#52c41a' }]) +
        '<div class="bchart">' + (yearRows.length ? barRows : '<div class="empty">当前条件下无数据</div>') + '</div>') +
      chartCard('项目流程阶段分布（当前所处环节）', funnelRows || '<div class="empty">当前条件下无进行中项目</div>') +
      chartCard('累计实付金额趋势（万元）', '<div class="legend" style="margin-bottom:8px"><span class="li"><span class="dot" style="background:#1677ff"></span>累计实付</span></div>' + lineHtml) +
    '</div>' +
    '<div class="grid" style="grid-template-columns:2fr 1fr;margin-top:14px">' +
      '<div class="card card-pad"><div class="hd"><h4 style="font-size:14px">甲方单位资金投入排名</h4></div>' +
        '<table class="tbl"><thead><tr><th>甲方单位</th><th>项目数</th><th>合同总额</th><th>累计实付</th><th style="width:130px">执行率</th></tr></thead><tbody>' +
        unitRows.map(u => {
          const r = u.contract ? Math.round(u.paid / u.contract * 100) : 0;
          return '<tr><td class="fw6">' + esc(u.u) + '</td><td>' + u.n + ' 个</td>' +
            '<td style="font-variant-numeric:tabular-nums">' + money(u.contract) + '</td>' +
            '<td style="font-variant-numeric:tabular-nums">' + money(u.paid) + '</td><td>' + progressBar(r, r < 50) + '</td></tr>';
        }).join('') + '</tbody></table></div>' +
      '<div class="card card-pad"><div class="hd"><h4 style="font-size:14px;color:#ff4d4f">逾期阶段预警</h4></div>' +
        (overCount ? '<table class="tbl"><tbody>' + overL + '</tbody></table>' : '<div class="empty">当前条件下无逾期阶段</div>') + '</div>' +
    '</div>';
}

function chartCard(title, inner) {
  return '<div class="card card-pad"><div class="hd"><h4>' + title + '</h4></div>' + inner + '</div>';
}
function legendHtml(items) {
  return '<div class="legend" style="margin-bottom:8px">' + items.map(x =>
    '<span class="li"><span class="dot" style="background:' + x.c + '"></span>' + x.l + '</span>').join('') + '</div>';
}

/* ================= 5. 系统管理（占位） ================= */
function viewSys() {
  return head('系统管理（示例占位）', '本期演示不包含系统管理功能，实际系统中由管理员维护以下配置') +
    '<div class="grid" style="grid-template-columns:repeat(auto-fill,minmax(280px,1fr))">' +
      sysCard('用户管理', '账号、姓名、部门、角色、启用/停用、重置密码', '#1677ff') +
      sysCard('部门管理', '单位内部部门树维护，用于数据范围控制', '#13c2c2') +
      sysCard('流程模板配置', '硬件/软件项目阶段模板：阶段名称、顺序、权重、付款节点比例', '#722ed1') +
      sysCard('基础字典', '甲方单位、资金来源、招标方式、文档类别、付款节点等', '#fa8c16') +
      sysCard('操作日志查询', '全量审计日志：新增/编辑/推进/上传/删除留痕', '#52c41a') +
    '</div>';
}
function sysCard(t, d, c) {
  return '<div class="card card-pad"><div class="bar" style="width:4px;height:40px;background:' + c + ';border-radius:2px;margin-bottom:10px"></div>' +
    '<div style="font-weight:700;font-size:15px">' + t + '</div>' +
    '<div class="muted mt8" style="font-size:13px;line-height:1.7">' + d + '</div>' +
    '<button class="btn btn-ghost btn-sm mt8" data-act="sys-demo">查看示例说明</button></div>';
}
function head(title, sub) {
  return '<div class="page-head"><div><h2>' + title + '</h2><div class="sub">' + sub + '</div></div></div>';
}

/* ================= 新建项目（演示弹窗） ================= */
function openNewProject() {
  const unitsOpt = UNIT_OPTIONS.map(u => '<option>' + esc(u) + '</option>').join('');
  const usersOpt = USERS.filter(u => u.role !== '管理员').map(u => '<option>' + u.name + '</option>').join('');
  const modal = document.createElement('div');
  modal.className = 'mask';
  modal.setAttribute('data-act', 'modal-close');
  modal.innerHTML =
    '<div class="modal">' +
      '<h3>新建项目（演示）</h3>' +
      '<div class="form-row"><div class="g"><label>项目名称 *</label><input id="np-name" class="inp" placeholder="如：市XX平台建设项目"></div>' +
      '<div class="g"><label>项目类型 *</label><select id="np-type" class="sel"><option value="HW">硬件项目</option><option value="SW">软件项目</option></select></div></div>' +
      '<div class="form-row"><div class="g"><label>甲方单位 *</label><select id="np-unit" class="sel">' + unitsOpt + '</select></div>' +
      '<div class="g"><label>承接部门 *</label><select id="np-dept" class="sel">' + DEPTS.map(d => '<option>' + d + '</option>').join('') + '</select></div></div>' +
      '<div class="form-row"><div class="g"><label>项目经理 *</label><select id="np-mgr" class="sel">' + usersOpt + '</select></div>' +
      '<div class="g"><label>供应商</label><input id="np-vendor" class="inp" placeholder="中标/合作厂商"></div></div>' +
      '<div class="form-row"><div class="g"><label>概算 / 预算金额（元）*</label><input id="np-budget" class="inp" type="number" placeholder="如 2000000"></div>' +
      '<div class="g"><label>合同金额（元）</label><input id="np-contract" class="inp" type="number" placeholder="可先留空，签订后补录"></div></div>' +
      '<div class="form-row"><div class="g"><label>立项日期 *</label><input id="np-date" class="inp" type="date" value="' + TODAY + '"></div>' +
      '<div class="g"><label>计划完成日期</label><input id="np-finish" class="inp" type="date"></div></div>' +
      '<div class="mf"><button class="btn btn-ghost" data-act="modal-close">取 消</button>' +
      '<button class="btn btn-p" data-act="modal-save">保存并生成流程阶段</button></div>' +
    '</div>';
  document.body.appendChild(modal);
}

function doNewProject() {
  const val = id => { const el = $id(id); return el ? el.value.trim() : ''; };
  const name = val('np-name'), unit = val('np-unit'), dept = val('np-dept'), mgr = val('np-mgr');
  const date = val('np-date'), budget = val('np-budget'), contract = val('np-contract');
  if (!name || !unit || !dept || !mgr || !date || !budget) { toast('请填写完整带 * 的必填项'); return; }
  const maxNo = PROJECTS.reduce((m, p) => Math.max(m, p.no), 0) + 1;
  const year = date.slice(0, 4);
  const spec = {
    no: maxNo, name, type: val('np-type') === 'SW' ? 'SW' : 'HW',
    status: 'RUN', cur: 0, curPct: 10,
    unit, dept, manager: mgr, vendor: val('np-vendor'),
    approveNo: '', approveDate: date, planFinish: val('np-finish') || '',
    budget: +budget, bidAmount: 0, contractAmount: contract ? +contract : 0, changeAmount: 0,
    source: '单位自建', fundSource: '单位自筹', bidType: '—', updated: TODAY,
  };
  const proj = buildProject(spec);
  PROJECTS.unshift(proj);
  document.querySelector('.mask').remove();
  toast('项目已创建，已按「' + TYPES[proj.type] + '」模板生成 ' + proj.phases.length + ' 个流程阶段');
  STATE.view = 'detail'; STATE.pid = proj.code; STATE.tab = 'flow';
  renderApp();
}

/* ================= 事件绑定（委托） ================= */
document.addEventListener('click', function (e) {
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const insideModal = !!e.target.closest('.modal');
  if (insideModal && el.classList.contains('mask')) return;   // 点击弹窗内容冒泡到遮罩，忽略
  const act = el.dataset.act;
  const val = el.dataset;

  if (act === 'login') { doLogin(); return; }

  if (STATE.view === 'list') readListKw();

  switch (act) {
    case 'go': STATE.view = val.view; if (val.view === 'list') STATE.list.page = 1; renderApp(); break;
    case 'logout': localStorage.removeItem('pms_auth'); boot(); break;
    case 'open': STATE.view = 'detail'; STATE.pid = val.id; STATE.tab = 'flow'; renderApp(); break;
    case 'tab': STATE.tab = val.tab; renderView(); break;
    case 'mode': STATE.list.mode = val.mode; STATE.list.page = 1; renderView(); break;
    case 'search': readListKw(); STATE.list.page = 1; renderView(); break;
    case 'reset':
      STATE.list = { mode: STATE.list.mode, page: 1, kw: '', type: '', status: '', unit: '', year: '' };
      renderView(); break;
    case 'page': {
      readListKw();
      const arr = filteredProjects();
      const pages = Math.max(1, Math.ceil(arr.length / PAGE_SIZE));
      let np = +val.p;
      if (np < 1) np = 1; if (np > pages) np = pages;
      STATE.list.page = np; renderView(); break;
    }
    case 'newproj': openNewProject(); break;
    case 'modal-close': { const m = document.querySelector('.mask'); if (m) m.remove(); break; }
    case 'modal-save': doNewProject(); break;
    case 'stat-reset': STATE.stats = { year: '', type: '', status: '' }; renderApp(); break;
    case 'file': toast('在线预览（演示）：' + val.f); break;
    case 'phase-demo': toast('【演示入口】' + val.t + ' —— 正式版本将打开阶段详情编辑'); break;
    case 'editinfo': toast('【演示入口】编辑基本信息 —— 正式版本将打开信息编辑表单'); break;
    case 'export': toast('【演示入口】导出项目档案 —— 正式版本将打包生成 PDF / Excel'); break;
    case 'addpay': toast('【演示入口】新增付款记录 —— 正式版本按合同节点录入'); break;
    case 'batch-download': toast('【演示入口】批量下载附件 —— 正式版本将压缩打包下载'); break;
    case 'sys-demo': toast('【说明】系统管理功能未包含在本演示版本中，将在正式版本实现'); break;
  }
});

document.addEventListener('change', function (e) {
  const t = e.target;
  if (!t.classList || !t.classList.contains('js-lf')) return;
  readListKw();
  STATE.list[t.dataset.k] = t.value;
  STATE.list.page = 1;
  renderView();
});
document.addEventListener('change', function (e) {
  const t = e.target;
  if (!t.classList || !t.classList.contains('js-sf')) return;
  STATE.stats[t.dataset.k] = t.value;
  renderView();
});

document.addEventListener('keydown', function (e) {
  if (e.key === 'Enter') {
    if (STATE.view === 'list' && e.target && e.target.id === 'list-kw') {
      readListKw(); STATE.list.page = 1; renderView();
    } else if (!STATE.authed && (e.target.id === 'login-user' || e.target.id === 'login-pass')) {
      doLogin();
    }
  }
});

function doLogin() {
  const u = $id('login-user'), p = $id('login-pass');
  if (u && p && u.value.trim() === 'admin' && p.value === '123456') {
    localStorage.setItem('pms_auth', '1');
    STATE.authed = true;
    renderApp();
    toast('欢迎回来，系统管理员');
  } else {
    toast('账号或密码错误（演示账号：admin / 123456）');
  }
}

boot();
