-- ============================================================
-- V6：项目概览（类 README 介绍 + 二级功能模块清单）
-- 一个项目一行；intro_md 存 Markdown；modules_json 存二级清单 JSON：
--   [{ "name":"模块", "description":"说明", "children":[{"name":"子模块","description":""}] }]
-- ============================================================
CREATE TABLE project_overview (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    project_id  BIGINT        NOT NULL COMMENT '项目 id',
    intro_md    TEXT          NULL COMMENT '项目介绍（Markdown，项目背景/用途/功能概览/应用场景）',
    modules_json TEXT         NULL COMMENT '功能模块清单（二级 JSON）',
    create_time DATETIME      NULL,
    update_time DATETIME      NULL,
    deleted     TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_overview_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='项目概览：README 式介绍 + 功能模块清单';
