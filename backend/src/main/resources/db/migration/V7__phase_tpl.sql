-- ============================================================
-- V7：流程模板（多套模板：Tab 式管理）
-- phase_tpl = 模板（每套模板一个分组：名称/类型/内置/默认/启用）；
-- phase_template 增加 tpl_id 归属，行数据仍为线性阶段。
-- 回填：HW/SW 各生成一套内置默认模板，并把存量阶段归入对应模板。
-- ============================================================
CREATE TABLE phase_tpl (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    project_type VARCHAR(16)  NOT NULL COMMENT 'HW/SW',
    name         VARCHAR(64)  NOT NULL COMMENT '模板名称',
    builtin      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否内置（内置不可删除）',
    is_default   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否类型默认模板（新建项目使用）',
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '启用',
    sort_no      INT          NOT NULL DEFAULT 0 COMMENT '展示排序',
    remark       VARCHAR(255) NULL,
    create_time  DATETIME     NULL,
    update_time  DATETIME     NULL,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tpl_type (project_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='流程模板（多套，含内置默认模板）';

ALTER TABLE phase_template
    ADD COLUMN tpl_id BIGINT NULL COMMENT '所属模板 id（空=历史数据，回填后不为空）',
    ADD KEY idx_pt_tpl (tpl_id);

-- 内置默认模板
INSERT INTO phase_tpl (project_type, name, builtin, is_default, enabled, sort_no, remark)
VALUES
('HW', '硬件项目默认模板', 1, 1, 1, 1, '系统内置：硬件 9 阶段'),
('SW', '软件项目默认模板', 1, 1, 1, 2, '系统内置：软件 11 阶段');

-- 回填阶段归属
UPDATE phase_template SET tpl_id = (SELECT id FROM phase_tpl WHERE project_type='HW' AND builtin=1)
WHERE project_type='HW' AND (tpl_id IS NULL OR deleted=0);
UPDATE phase_template SET tpl_id = (SELECT id FROM phase_tpl WHERE project_type='SW' AND builtin=1)
WHERE project_type='SW' AND (tpl_id IS NULL OR deleted=0);
