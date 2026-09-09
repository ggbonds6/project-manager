-- ============================================================
-- V3 (yashan) 清理：移除部门概念（业务不再使用）
-- 删除 sys_dept 表；删除 project.owner_dept_id、sys_user.dept_id 列。
-- 转换说明：DROP TABLE IF EXISTS -> DROP TABLE（runner 以版本表保证不重跑，
-- 无需 IF EXISTS）；多条变更按 Oracle 一条语句一个操作拆分。
-- ============================================================

ALTER TABLE project DROP COLUMN owner_dept_id;
ALTER TABLE sys_user DROP COLUMN dept_id;
DROP TABLE sys_dept;
