-- ============================================================
-- 演示数据**物理清理**（仅用于开发机 / 演示环境，切勿在生产执行）
--
-- 为什么需要它：
--   `scripts/seed-demo.mjs` 是通过**后端接口**删除项目的，而本系统的删除一律是
--   **逻辑删除**（deleted = 1，为审计留痕）。所以反复重跑 seed 会让
--   project / contract / payment 等表里堆积大量 deleted = 1 的历史行——
--   直接看数据库时很容易误以为"当前数据缺字段"，其实那是早期批次留下的旧结构行。
--
-- 本脚本把演示数据**物理删掉**，让库回到"干净基线"，随后再跑 seed 就是全新的一批。
--
-- 用法（开发机）：
--   .\scripts\db-sql.ps1 scripts/demo-reset.sql
--   node scripts/seed-demo.mjs
--   node scripts/seed-attachments.mjs
--
-- 保留不动：sys_user（账号）、dict_item（字典）、phase_tpl / phase_template（流程模板）、
--           schema_version（迁移版本表）。
-- 注意：附件**元数据**会被删除，但已落盘/已上传到 OBS 的**物理文件不会被删**，
--       会变成无引用垃圾（开发环境可接受；生产环境如需清理应走保留策略，不要用本脚本）。
-- ============================================================

-- 子表在前，主表在后
DELETE FROM project_contract;
DELETE FROM project_division;
DELETE FROM project_overview;
DELETE FROM project_phase;
DELETE FROM attachment_upload_task;
DELETE FROM attachment;
DELETE FROM payment;
DELETE FROM contract;
DELETE FROM project;

-- 操作日志是审计留痕，默认**不清理**；确实想让演示环境日志干净时，手动放开下面这行：
-- DELETE FROM operate_log;
