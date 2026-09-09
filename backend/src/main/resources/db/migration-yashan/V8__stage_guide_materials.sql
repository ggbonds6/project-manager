-- ============================================================
-- V8 (yashan)：阶段说明/关键材料（guide/key_materials）+ 画布布局(flow_json)
-- phase_template.guide：该阶段"要做什么"（Markdown/长文本）
-- phase_template.key_materials：关键材料/产出物清单
-- project_phase 快照同样字段（新建项目时从模板拷贝）
-- phase_tpl.flow_json：draw.io 式画布布局（节点坐标+连线），保存后可还原
-- 转换说明：TEXT -> CLOB；一条 ALTER 的多 ADD COLUMN 用括号列表合并。
-- ============================================================
ALTER TABLE phase_template ADD (
    guide          CLOB NULL,
    key_materials  CLOB NULL
);

ALTER TABLE project_phase ADD (
    guide          CLOB NULL,
    key_materials  CLOB NULL
);

ALTER TABLE phase_tpl ADD (
    flow_json      CLOB NULL
);
