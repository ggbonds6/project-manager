-- ============================================================
-- V1 政府信息化项目管理系统 初始表结构
-- 主键统一自增 bigint；审计字段 create_time/update_time；
-- 主档类表带逻辑删除标记 deleted(0正常/1删除)。
-- ============================================================

-- 1. 用户
CREATE TABLE sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    account         VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password        VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    name            VARCHAR(64)  NOT NULL COMMENT '姓名',
    dept_id         BIGINT       NULL COMMENT '所属部门',
    role            VARCHAR(20)  NOT NULL DEFAULT 'MANAGER' COMMENT '角色 ADMIN/MANAGER/VIEWER',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    last_login_time DATETIME     NULL COMMENT '最后登录时间',
    create_time     DATETIME     NULL,
    update_time     DATETIME     NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='系统用户';

-- 2. 部门树
CREATE TABLE sys_dept (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父部门，0为根',
    name        VARCHAR(64) NOT NULL COMMENT '部门名称',
    order_no    INT         NOT NULL DEFAULT 0,
    create_time DATETIME    NULL,
    update_time DATETIME    NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='部门';

-- 3. 基础字典项（甲方单位/资金来源/招标方式/文档类别/付款节点/项目来源 等统一维护）
CREATE TABLE dict_item (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    dict_type   VARCHAR(40) NOT NULL COMMENT '字典类型编码',
    code        VARCHAR(40) NOT NULL COMMENT '字典项编码',
    name        VARCHAR(64) NOT NULL COMMENT '字典项名称',
    sort_no     INT         NOT NULL DEFAULT 0,
    create_time DATETIME    NULL,
    update_time DATETIME    NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_type (dict_type, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='基础字典';

-- 4. 阶段模板（项目类型 -> 有序阶段）
CREATE TABLE phase_template (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    project_type    VARCHAR(10)  NOT NULL COMMENT '项目类型 HW硬件/SW软件',
    phase_name      VARCHAR(64)  NOT NULL COMMENT '阶段名称',
    sort_no         INT          NOT NULL DEFAULT 0 COMMENT '顺序',
    weight          INT          NOT NULL DEFAULT 0 COMMENT '默认权重(用于整体进度)',
    pay_node        VARCHAR(20)  NULL COMMENT '里程碑付款节点编码(可空)',
    description     VARCHAR(255) NULL COMMENT '主要工作/判定标准',
    attach_type_hints VARCHAR(255) NULL COMMENT '常用附件类别提示(逗号分隔)',
    skipable        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许跳过',
    create_time     DATETIME     NULL,
    update_time     DATETIME     NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_type_sort (project_type, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='阶段模板';

-- 5. 项目主档
CREATE TABLE project (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    code               VARCHAR(64)   NOT NULL COMMENT '项目编号',
    name               VARCHAR(128)  NOT NULL COMMENT '项目名称',
    type               VARCHAR(10)   NOT NULL COMMENT 'HW/SW',
    status             VARCHAR(10)   NOT NULL DEFAULT 'RUN' COMMENT 'RUN/DONE/PAUSE/STOP',
    owner_unit         VARCHAR(64)   NULL COMMENT '甲方单位(字典名称快照)',
    owner_dept_id      BIGINT        NULL COMMENT '甲方内部承接部门',
    manager_user_id    BIGINT        NULL COMMENT '项目经理(内部负责人)',
    member_ids         VARCHAR(255)  NULL COMMENT '参与人员id(逗号分隔)',
    vendor_name        VARCHAR(128)  NULL COMMENT '供应商全称',
    vendor_contact     VARCHAR(128)  NULL COMMENT '供应商联系人姓名/电话',
    approve_no         VARCHAR(64)   NULL COMMENT '批复文号',
    budget_amount      DECIMAL(15,2) NULL COMMENT '概算/预算金额(元)',
    fund_source        VARCHAR(40)   NULL COMMENT '资金来源(字典code)',
    bid_type           VARCHAR(40)   NULL COMMENT '招标方式(字典code)',
    bid_amount         DECIMAL(15,2) NULL COMMENT '中标金额',
    contract_no        VARCHAR(64)   NULL COMMENT '合同编号',
    contract_amount    DECIMAL(15,2) NULL COMMENT '合同金额',
    change_amount      DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '变更金额(+/-)',
    approve_date       DATE          NULL COMMENT '立项/批复日期',
    plan_start_date    DATE          NULL,
    plan_finish_date   DATE          NULL,
    actual_finish_date DATE          NULL COMMENT '实际完成(终验)日期',
    content_summary    TEXT          NULL COMMENT '建设内容摘要',
    project_source     VARCHAR(40)   NULL COMMENT '项目来源(字典code)',
    remark             VARCHAR(500)  NULL,
    create_by          BIGINT        NULL,
    create_time        DATETIME      NULL,
    update_time        DATETIME      NULL,
    deleted            TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_type_status (type, status),
    KEY idx_dept (owner_dept_id),
    KEY idx_manager (manager_user_id),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='项目主档';

-- 6. 项目阶段实例（新建项目时按模板快照生成，之后与模板解耦）
CREATE TABLE project_phase (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    project_id        BIGINT       NOT NULL,
    phase_name        VARCHAR(64)  NOT NULL COMMENT '阶段名称(快照)',
    sort_no           INT          NOT NULL DEFAULT 0,
    weight            INT          NOT NULL DEFAULT 0 COMMENT '权重(快照)',
    pay_node          VARCHAR(20)  NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED/IN_PROGRESS/DONE/SKIPPED',
    percent           INT          NOT NULL DEFAULT 0 COMMENT '完成比例0-100',
    plan_start_date   DATE         NULL,
    plan_finish_date  DATE         NULL,
    actual_start_date DATE         NULL,
    actual_finish_date DATE        NULL,
    manager_user_id   BIGINT       NULL COMMENT '阶段负责人',
    note              TEXT         NULL COMMENT '经办记录/说明',
    result_fields     TEXT         NULL COMMENT '阶段关键结果字段(JSON)',
    create_time       DATETIME     NULL,
    update_time       DATETIME     NULL,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project (project_id, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='项目阶段实例';

-- 7. 附件
CREATE TABLE attachment (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    biz_type       VARCHAR(20)  NOT NULL COMMENT '归属类型 PROJECT_PHASE/PAYMENT/PROJECT',
    biz_id         BIGINT       NOT NULL COMMENT '归属业务id',
    attach_type    VARCHAR(40)  NULL COMMENT '文档类别(字典code)',
    file_name      VARCHAR(255) NOT NULL COMMENT '原始文件名',
    stored_name    VARCHAR(64)  NOT NULL COMMENT '存储文件名(uuid.扩展名)',
    file_path      VARCHAR(255) NOT NULL COMMENT '相对上传目录路径',
    file_size      BIGINT       NOT NULL DEFAULT 0 COMMENT '字节',
    file_ext       VARCHAR(16)  NULL,
    upload_user_id BIGINT       NULL,
    upload_time    DATETIME     NULL,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_biz (biz_type, biz_id),
    KEY idx_phase_attach (attach_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='附件';

-- 8. 付款记录
CREATE TABLE payment (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    project_id   BIGINT        NOT NULL,
    node_code    VARCHAR(20)   NOT NULL COMMENT '付款节点编码 PREPAY/ARRIVAL/FIRST_ACCEPT/FINAL_ACCEPT/WARRANTY',
    node_name    VARCHAR(64)   NOT NULL,
    condition_desc VARCHAR(255) NULL COMMENT '触发条件',
    plan_amount  DECIMAL(15,2) NULL COMMENT '计划金额',
    plan_date    DATE          NULL COMMENT '计划付款日期',
    paid_amount  DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '实付金额',
    paid_date    DATE          NULL,
    status       VARCHAR(10)   NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID/PART/PAID',
    remark       VARCHAR(500)  NULL,
    create_time  DATETIME      NULL,
    update_time  DATETIME      NULL,
    deleted      TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='付款记录';

-- 9. 操作日志（只追加，不删除）
CREATE TABLE operate_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    user_name   VARCHAR(64)  NULL,
    biz_type    VARCHAR(30)  NOT NULL COMMENT 'PROJECT/PHASE/ATTACHMENT/PAYMENT/USER/LOGIN',
    biz_id      BIGINT       NULL,
    action      VARCHAR(40)  NOT NULL COMMENT 'CREATE/UPDATE/DELETE/UPLOAD/DOWNLOAD/LOGIN...',
    detail      VARCHAR(500) NULL COMMENT '操作描述',
    create_time DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_biz (biz_type, biz_id),
    KEY idx_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='操作日志';
