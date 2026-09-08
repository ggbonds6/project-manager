# 数据库迁移可行性分析：MySQL 8 → 崖山数据库（YashanDB）Oracle 模式

> 分析对象：project-manager 当前代码与库结构（V1–V8，Flyway 管理，MyBatis-Plus ORM）。
> 结论先行：**可行，整体难度中等偏低**。改造点少且集中，主要风险在“方言/分页/主键生成/Flyway 策略”，预计 1~3 人日 + 数据迁移与回归验证（以 PoC 实测为准）。

---

## 1. 当前数据库依赖盘点（已逐项核对）

| 方面 | 现状 | 对崖山的影响 |
| --- | --- | --- |
| 访问层 | 全部 MyBatis-Plus `LambdaQueryWrapper`，**无 XML Mapper** | ✅ 方言由分页插件统一生成，集中改一处 |
| 分页 | `MybatisPlusConfig` 显式 `PaginationInnerInterceptor(DbType.MYSQL)` → 生成 `LIMIT ? OFFSET ?` | ⚠️ 需改方言为 Oracle/Yashan（FETCH FIRST 或 ROWNUM），本项目已限 maxLimit |
| 自定义 SQL | 仅 3 类：`CONCAT(code,'%')`、`YEAR(approve_date)`、`.last("limit 1")` ×2 | ⚠️ `YEAR()` 与 `LIMIT 1` 需改写（Oracle 用 `EXTRACT(YEAR FROM …)`、`FETCH FIRST 1 ROWS ONLY`），约 6~8 处 |
| 主键 | `id-type: auto` + `AUTO_INCREMENT` | ⚠️ Oracle 模式需 identity/序列方案，改实体主键策略或建表语法 |
| 数据类型 | BIGINT/VARCHAR/TEXT/TINYINT/DATE/DATETIME/DECIMAL(15,2)，无 BLOB | ✅ 常见映射；TEXT→CLOB/VARCHAR2 大字段需注意 |
| 字符集 | utf8mb4 / utf8mb4_unicode_ci | ✅ 对应库级 utf8 |
| JSON/复杂类型 | `result_fields`、`modules_json` 等以 **TEXT 字符串**存储，未用 MySQL JSON 函数 | ✅ 纯文本，无兼容问题 |
| 逻辑删除 | 通用 `deleted` 字段 + MP 逻辑删除配置 | ✅ 与方言无关 |
| 时间处理 | 代码内全部 `LocalDate/LocalDateTime`，库函数仅 `YEAR()` 一处 | ✅ 除 `YEAR()` 外无 NOW()/DATE_FORMAT/GROUP_CONCAT 等 |
| 迁移管理 | Flyway `flyway-core + flyway-mysql`，启动自动迁移 | ⚠️ **主要风险点**：官方 Flyway 不支持 YashanDB → 需关自动迁移，改为离线迁移脚本或崖山方支持的迁移/兼容方案 |
| 附件文件 | 存磁盘/卷（数据库仅元数据） | ✅ 完全不受影响 |
| 唯一键/索引/外键 | 普通唯一键与索引，无复杂约束 | ✅ 转换简单 |

## 2. 需要改动/调整的点（代码侧，量很小）

1. `MybatisPlusConfig`：分页方言 `MYSQL` → `ORACLE`（YashanDB Oracle 模式）；
2. 主键生成：`AUTO_INCREMENT` → Yashan 自增/序列（建表 DDL + MP 主键类型策略，如 `IdType.INPUT`+序列或 Yashan identity）；
3. 重写日期函数：`YEAR(approve_date)`（ProjectService×2 处、StatsService×1）→ `EXTRACT(YEAR FROM approve_date)`；
4. 重写两条 `.last("limit 1")`（取默认/启用模板）→ `FETCH FIRST 1 ROWS ONLY` 或换实现；
5. 若有隐式 MySQL 习惯（如 `0=1`、反引号、大小写敏感比较）逐一清理——本仓未发现；
6. Flyway：`spring.flyway.enabled=false`，把 V1–V8 转为崖山可执行脚本（含回填），并记录到 `flyway_schema_history` 等效表或文档台账。

## 3. 数据迁移方案（建议路径）

1. **导出**：`mysqldump`（表结构 + 数据，utf8mb4）。
2. **DDL 转换**：手写或借助崖山迁移工具把 10 张表（sys_user/dict_item/phase_tpl/phase_template/project/project_phase/payment/contract/attachment/operate_log/project_overview + flyway_schema_history）转成 Oracle 模式 DDL；类型映射、字符集、主键自增、索引一并落地。
3. **数据装载**：INSERT 文本普遍兼容（数字/字符串/日期），需处理 TEXT→CLOB 的插入方式（建议分批/用工具），核对行数与抽样比对。
4. **回填与存量**：V8 之前的 backfill（tpl_id、内置模板等）直接在目标库脚本内完成。
5. **应用切换验证**：改 `application.yml` 数据源（driver/url/账号），启动前关 Flyway；跑健康/登录/列表/分页/筛选(含多值与年度)/详情/新建项目(生成阶段+快照说明)/附件上传/统计接口回归。
6. **双跑兜底**：建议先并行跑新库试运行 1~2 周，再切换生产。

## 4. 风险评估

| 风险 | 等级 | 应对 |
| --- | --- | --- |
| Flyway 不支持 Yashan | 高 | 关闭自动迁移 + 脚本台账；向崖山厂商确认其提供的迁移/兼容工具与 Flyway 替代 |
| 分页/方言 SQL 差异 | 低-中 | 统一由分页插件切方言 + 回归分页/大页查询 |
| 主键自增语义 | 低 | 提前确定 identity/序列方案并在压测环境验证并发插入 |
| 编码/大小写 | 低 | 库级 utf8；标识符统一小写不引号，规避大小写折叠差异 |
| 日期函数/特殊 SQL | 低 | 仅 `YEAR`/`LIMIT` 几处，改后做年度筛选回归 |
| TEXT 大字段 | 低 | 确认字段长度上限与 CLOB 映射；附件路径不受影响 |

## 5. 工作量与建议

- **改动量估算**：代码改动约 1 处配置 + ≤10 处 SQL/主键调整；**不建议重写 DAO**（当前结构已高度 ORM 化，利于迁移）。
- **前提**：需要一份崖山 Oracle 模式实例做 PoC（含官方驱动与 jdbc url、迁移工具、Flyway/ORM 兼容说明）。
- **建议顺序**：① 申请崖山测试实例 → ② 建 PoC 库按第 3 节迁移 → ③ 改上述点跑全量回归 → ④ 出迁移核对报告后切换。

> 说明：以上为基于仓库现状（无 XML、无复杂 SQL）的静态分析；最终可行性以崖山环境 PoC 实测结果为准。
