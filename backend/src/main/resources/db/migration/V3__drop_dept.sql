-- ============================================================
-- V3 清理：移除部门概念（业务不再使用）
-- 删除 sys_dept 表；删除 project.owner_dept_id、sys_user.dept_id 列。
-- ============================================================

ALTER TABLE project DROP COLUMN owner_dept_id;
ALTER TABLE sys_user DROP COLUMN dept_id;
DROP TABLE IF EXISTS sys_dept;
