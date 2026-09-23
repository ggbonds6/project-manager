-- ============================================================
-- eval/smoke-30.json 的「真值来源」只读探针（可重跑复核）
--
-- 用法（仓库根）：
--   powershell -ExecutionPolicy Bypass -File scripts\db-sql.ps1 eval\probe.sql
--
-- 用途：本评测集里每一个 must_contain 数字都能被这几条 SELECT 复现。
-- 全部只读，不含任何 DDL/DML；不读取任何口令（连接信息由 db-sql.ps1 提供）。
--
-- 目标数据（fixed）：project.id = 55「中小学教育信息化改造（单项目）」（code RJ-2026-005）
-- ============================================================

-- 0) 主项目与子项目候选（挑选真实项目的入口）
SELECT id, code, name, type, status, parent_id, budget_amount, contract_amount, contract_no
FROM project WHERE deleted = 0 AND parent_id IS NULL ORDER BY id;

-- 1) 各阶段附件数（= stats?kind=phase_attachment_count 的正解口径）
SELECT ph.id AS phase_id, ph.phase_name, ph.sort_no,
       (SELECT COUNT(*) FROM attachment a
         WHERE a.biz_type = 'PROJECT_PHASE' AND a.biz_id = ph.id AND a.deleted = 0) AS attachment_count
FROM project_phase ph
WHERE ph.project_id = 55 AND ph.deleted = 0
ORDER BY ph.sort_no;

-- 2) 阶段附件数合计（2026-09-23 复测 = 34；首次造题时 = 31，库被重灌过）
SELECT COUNT(*) AS phase_attachment_total
FROM attachment a JOIN project_phase ph ON ph.id = a.biz_id
WHERE a.biz_type = 'PROJECT_PHASE' AND a.deleted = 0 AND ph.deleted = 0 AND ph.project_id = 55;

-- 3) 预算 / 主合同金额 / 是否登记中标金额
SELECT id, name, type, status, budget_amount, contract_amount, bid_amount, contract_no
FROM project WHERE id = 55;

-- 4) 子项目数（应为 0）
SELECT COUNT(*) AS child_count FROM project WHERE parent_id = 55 AND deleted = 0;

-- 5) 合同数（应为 4）与合同总额（应为 1990800）
SELECT c.id, c.name, c.contract_no, c.vendor_name, c.contract_amount
FROM contract c JOIN project_contract pc ON pc.contract_id = c.id
WHERE pc.project_id = 55 AND c.deleted = 0 ORDER BY c.id;

SELECT COUNT(*) AS contract_count, SUM(c.contract_amount) AS contract_amount_total
FROM contract c JOIN project_contract pc ON pc.contract_id = c.id
WHERE pc.project_id = 55 AND c.deleted = 0;

-- 6) 付款记录数与已付合计（2026-09-23 复测 = 9 条 / 1855560；首次造题时 = 0 条）
SELECT COUNT(*) AS payment_count, SUM(paid_amount) AS paid_total
FROM payment WHERE project_id = 55 AND deleted = 0;

-- 7) 已入库（可问答）附件：复测 = 6 份（首次造题时 = 3 份），全部挂在阶段 526「立项申报」
SELECT a.id, a.biz_id, a.attach_type, a.file_name, a.ai_index_status, a.ai_doc_id
FROM attachment a
WHERE a.deleted = 0 AND a.ai_index_status = 'READY' AND a.ai_doc_id IS NOT NULL
  AND a.biz_id IN (SELECT id FROM project_phase WHERE project_id = 55 AND deleted = 0)
ORDER BY a.id;

-- 8) 招标采购阶段（id=528）的附件明细
SELECT id, attach_type, file_name FROM attachment
WHERE biz_type = 'PROJECT_PHASE' AND biz_id = 528 AND deleted = 0 ORDER BY id;

-- 9) 越权题用的 id 边界（实际范围仅 45~55，故 999999 必然不存在）
SELECT MIN(id) AS min_id, MAX(id) AS max_id FROM project;
