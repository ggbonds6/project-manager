-- ============================================================
-- V4 项目-子项目体系 与 合同(可覆盖多个子项目) 模型
-- 变更：
--  1. project 增加 parent_id（顶层=null；历史单项目自动为顶层，无需改数据）
--  2. 新增 contract 表（一个合同可被任意多个子项目引用；金额/供应商/招标信息归属合同）
--  3. project 增加 contract_id（子项目指向所属合同；顶层也可挂共享合同）
--  4. payment 增加 contract_id（里程碑付款归属合同；保留 project_id 便于展示/历史）
-- 回填：为已有财务/合同信息或付款记录的项目生成合同并关联，付款一并挂接。
-- ============================================================

-- 1) 父子层级
ALTER TABLE project
    ADD COLUMN parent_id BIGINT NULL COMMENT '父(总)项目id，null=顶层项目',
    ADD KEY idx_project_parent (parent_id);

-- 2) 合同表
CREATE TABLE contract (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    name            VARCHAR(128)  NOT NULL COMMENT '合同名称（建议含总项目/子项目说明）',
    contract_no     VARCHAR(64)   NULL COMMENT '合同编号',
    vendor_name     VARCHAR(128)  NULL COMMENT '供应商',
    vendor_contact  VARCHAR(128)  NULL,
    bid_type        VARCHAR(40)   NULL,
    bid_amount      DECIMAL(15,2) NULL,
    contract_amount DECIMAL(15,2) NULL COMMENT '合同金额(元)',
    change_amount   DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '变更金额(+/-)',
    plan_amount     DECIMAL(15,2) NULL COMMENT '计划(合同总额口径预留)',
    scope_remark    VARCHAR(500)  NULL COMMENT '覆盖范围说明，如：覆盖 3 个子项目',
    remark          VARCHAR(500)  NULL,
    create_time     DATETIME      NULL,
    update_time     DATETIME      NULL,
    deleted         TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_contract_no (contract_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='合同（可覆盖一个或多个项目/子项目）';

-- 3) 项目挂合同
ALTER TABLE project
    ADD COLUMN contract_id BIGINT NULL COMMENT '所属合同id(可为空；子项目共享合同则指向同一 contract)',
    ADD KEY idx_project_contract (contract_id);

-- 4) 付款挂合同
ALTER TABLE payment
    ADD COLUMN contract_id BIGINT NULL COMMENT '归属合同id',
    ADD KEY idx_payment_contract (contract_id);

-- ============ 回填（对历史数据幂等、仅本地演示数据有意义） ============

-- 4a) 为“有合同编号或合同金额”的历史项目生成合同
INSERT INTO contract (name, contract_no, vendor_name, vendor_contact, bid_type, bid_amount,
                      contract_amount, change_amount, remark)
SELECT p.name, p.contract_no, p.vendor_name, p.vendor_contact, p.bid_type, p.bid_amount,
       p.contract_amount, COALESCE(p.change_amount, 0), '由历史项目数据迁移生成'
FROM project p
WHERE (p.contract_no IS NOT NULL OR p.contract_amount IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM contract c WHERE c.contract_no = p.contract_no AND p.contract_no IS NOT NULL);

-- 4b) 回填 project.contract_id（优先按合同编号匹配）
UPDATE project p
    JOIN contract c ON c.contract_no = p.contract_no
SET p.contract_id = c.id
WHERE p.contract_no IS NOT NULL
  AND p.contract_id IS NULL;

-- 4c) 兜底：无合同编号但有金额的，按项目名匹配（演示数据名唯一）
UPDATE project p
    JOIN contract c ON c.name = p.name AND c.contract_no IS NULL
SET p.contract_id = c.id
WHERE p.contract_no IS NULL
  AND p.contract_amount IS NOT NULL
  AND p.contract_id IS NULL;

-- 4d) 付款记录挂接到其项目所属合同
UPDATE payment pa
    JOIN project p ON p.id = pa.project_id
SET pa.contract_id = p.contract_id
WHERE pa.contract_id IS NULL;
