-- ============================================================
-- V14 (yashan) 问答留痕补「受控查询（P2）」两列
--
-- 背景（见 docs/AI前端与集成方案.md §11.6）：
--   P2 起 AI 服务在做结构化问答时可以**反向回调**主系统的 /api/ai/query/{entity}
--   取业务事实（项目/合同/付款/统计）。于是同一次问答除了「引用了哪些文档」，
--   还必须能回答「这次用了几次系统查询、查了哪几个 entity」——否则
--   「数字是从系统里查来的，还是模型推的」在事后审计时无法区分。
--
-- 为什么记在主系统这一侧、而不是靠 AI 服务的 toolTrace：
--   ① 留痕口子在主系统（§11.6），审计数据不能依赖被审计方自报；
--   ② toolTrace 是给前端看的展示数据，字段随时可能变；
--   ③ 主系统自己签发 scope_token、自己受理回调，计数天然权威（见 AiQueryUsageTracker）。
--
-- 约定（与 V13 一致）：
--   * 字符列 VARCHAR(n CHAR)；时间 TIMESTAMP
--   * ALTER TABLE ... ADD (...) 多列一次加（崖山 Oracle 模式不支持 ADD COLUMN IF NOT EXISTS，
--     幂等由 YashanMigrationRunner 按文件名登记 schema_version 保证）
--   * biz_query_count 有 NOT NULL DEFAULT 0：存量行读出来是 0（P0/P1 的问答确实没用过系统查询），
--     与「本次问答用了 0 次」语义一致，不需要额外回填
--   * biz_entities 可空：0 次查询时没有实体可记，用 NULL 而不是空串，便于 SQL 里 `IS NULL` 判定
-- ============================================================

ALTER TABLE ai_ask_log ADD (
    biz_query_count INT              NOT NULL DEFAULT 0,
    biz_entities    VARCHAR(200 CHAR) NULL
);

COMMENT ON COLUMN ai_ask_log.biz_query_count IS '本次问答调用主系统受控查询（/api/ai/query/*）的次数；0 表示没有用系统数据（P0/P1 的存量行为）';
COMMENT ON COLUMN ai_ask_log.biz_entities    IS '本次问答查过的 entity 去重清单（英文逗号分隔，取值 projects/contracts/payments/stats）；未查询时为空';
