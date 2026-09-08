-- ============================================================
-- V8：阶段说明/关键材料（guide/key_materials）+ 画布布局(flow_json)
-- phase_template.guide：该阶段"要做什么"（Markdown/长文本）
-- phase_template.key_materials：关键材料/产出物清单
-- project_phase 快照同样字段（新建项目时从模板拷贝）
-- phase_tpl.flow_json：draw.io 式画布布局（节点坐标+连线），保存后可还原
-- ============================================================
ALTER TABLE phase_template
    ADD COLUMN guide TEXT NULL COMMENT '阶段说明：本阶段要做什么（Markdown）',
    ADD COLUMN key_materials TEXT NULL COMMENT '关键材料/产出物清单（每行一项）';

ALTER TABLE project_phase
    ADD COLUMN guide TEXT NULL COMMENT '阶段说明快照（来自模板）',
    ADD COLUMN key_materials TEXT NULL COMMENT '关键材料快照（来自模板）';

ALTER TABLE phase_tpl
    ADD COLUMN flow_json TEXT NULL COMMENT '画布布局 JSON：{nodes:[{id,x,y}],edges:[{source,target}]}';
