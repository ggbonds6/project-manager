# 数据库迁移可行性分析与实施方案

## MySQL 8 → 崖山数据库 YashanDB（yashan / Oracle 兼容模式）

> 分析对象：`project-manager` 后端（Spring Boot 3.3.5 + Java 17 + MyBatis-Plus 3.5.7 + Flyway + MySQL 8）  
> 分析方式：逐文件核对代码 + 逐条比对崖山官方文档（23.4.14 与 Oracle 兼容性说明 / SELECT / 字符型 / CREATE TABLE / JDBC）  
> 本文替代 2026-09-08 16:42 的初版草稿，结论已按官方文档逐条复核修正。

---

## 0. 结论速览

> **本文第 1～8 章针对 yashan（Oracle）模式。崖山另有 MySQL 兼容模式，改动量显著更小但有形态与长期性隐患，两种模式的完整对比与选型建议见第 9 章——做选型决策前请先读第 9 章。**



| 判定项       | 结论                                                              |
| --------- | --------------------------------------------------------------- |
| **技术可行性** | ✅ **可行**。崖山 yashan 模式兼容本项目用到的全部 SQL 能力                          |
| **代码改动量** | **小**。Java 侧改动 **8 处**（1 个配置文件 + 7 处代码行），无 DAO 重写、无 XML Mapper  |
| **主要工作量** | 不在代码，在 **DDL 转换（11 张表）+ 数据迁移 + 回归验证**                           |
| **预估工期**  | **8～12 人日**（不含环境申请等待与性能压测）                                      |
| **最高风险项** | ① Flyway 官方不支持崖山（P0）；② `VARCHAR` 默认按字节计数导致中文截断（P0）；③ 主键自增改造（P1） |
| **建议策略**  | 先做 PoC（2～3 人日）验证 5 个关键假设，再决定是否全面改造                              |

> 一句话概括：**这个项目的 ORM 化程度很高（77 个 Java 文件、0 个 XML Mapper、几乎全用 LambdaQueryWrapper），代码侧几乎"免疫"数据库方言差异；工作量集中在建表脚本和迁移工具链，而不是业务代码。**

---

## 1. 当前项目数据库依赖盘点（逐项核对）


### 1.1 技术栈与结构

| 项          | 现状                                                                  | 文件                                                |
| ---------- | ------------------------------------------------------------------- | ------------------------------------------------- |
| 框架         | Spring Boot 3.3.5 / Java 17                                         | `backend/pom.xml`                                 |
| ORM        | MyBatis-Plus 3.5.7（`mybatis-plus-spring-boot3-starter`）             | `backend/pom.xml:37-41`                           |
| 分页插件       | `PaginationInnerInterceptor(DbType.MYSQL)`，`maxLimit=200`           | `MybatisPlusConfig.java:15-16`                    |
| 主键策略       | `IdType.AUTO`（依赖 `AUTO_INCREMENT`）                                  | `BaseEntity.java:19` + `application.yml:32`       |
| 逻辑删除       | `deleted` 字段，1 删 0 留                                                | `BaseEntity.java:28-29` + `application.yml:33-35` |
| 迁移工具       | Flyway（`flyway-core` + `flyway-mysql`），启动自动迁移                       | `pom.xml:43-51`、`application.yml:16-19`           |
| XML Mapper | **0 个**                                                             | `backend/src` 全域检索为空                              |
| 表数量        | **11 张**业务表（`sys_dept` 已在 V3 删除）                                    | `db/migration/V1~V8`                              |
| 迁移脚本       | V1 初始建表（9 表）、V2 种子数据、V3 删部门、V4 合同模型、V5 文件字典、V6 项目概览、V7 流程模板、V8 阶段说明 | 同上                                                |


### 1.2 代码中的"手写 SQL"全量清单（这是迁移风险的全部来源）

全库检索仅发现 **4 类、7 处**非 ORM 生成的 SQL 片段：

| # | 位置                        | 内容                                                    | 崖山 Oracle 模式是否可用           |
| - | ------------------------- | ----------------------------------------------------- | -------------------------- |
| 1 | `ProjectService.java:123` | `qw.apply("YEAR(approve_date) = {0}", y)`             | ❌ 需改写                      |
| 2 | `ProjectService.java:129` | 同上（多年度 OR 分支首项）                                       | ❌ 需改写                      |
| 3 | `ProjectService.java:132` | `w.or().apply("YEAR(approve_date) = {0}", y)`         | ❌ 需改写                      |
| 4 | `StatsService.java:262`   | `qw.apply("YEAR(approve_date) = {0}", q.getYear())`   | ❌ 需改写                      |
| 5 | `ProjectService.java:350` | `.last("limit 1")` 取默认模板                              | ⚠️ 崖山支持 `LIMIT`，可用，但建议统一改写 |
| 6 | `ProjectService.java:356` | `.last("limit 1")` 取首个启用模板                            | ⚠️ 同上                      |
| 7 | `ProjectMapper.java:13`   | `@Select("... WHERE code LIKE CONCAT(#{like}, '%')")` | ✅ 崖山内置 `CONCAT`，需 PoC 复核   |

**除此之外，全部是 `LambdaQueryWrapper` / `BaseMapper` 方法，方言由分页插件与 MP 内核统一生成。**  
已确认不存在：反引号标识符、`ON DUPLICATE KEY`、`REPLACE INTO`、`INSERT IGNORE`、`DATE_FORMAT`、`GROUP_CONCAT`、`NOW()`、`IFNULL`、`AUTO_INCREMENT`、`SELECT LAST_INSERT_ID()` 等 MySQL 专有写法。

### 1.3 数据类型使用情况

`BIGINT` / `VARCHAR` / `TEXT` / `TINYINT` / `INT` / `DATE` / `DATETIME` / `DECIMAL(15,2)`。  
**无 BLOB/二进制列**；附件只存磁盘路径（`file_path`），元数据在库里——这点对迁移非常友好。  
`result_fields`、`modules_json`、`intro_md`、`flow_json`、`guide`、`key_materials`、`note`、`content_summary` 虽然存 JSON/Markdown，但**都是 TEXT 纯文本，未使用任何 MySQL JSON 函数**，无兼容问题。

---


## 2. 崖山 yashan（Oracle）模式能力边界核对

以下均来自官方文档，直接决定本项目的改造范围：

| 能力                   | 官方说明                                                                                                                         | 对本项目的意义                                         |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| **分页语法**             | SELECT 支持 `row_limiting_clause`（`LIMIT n OFFSET m`）与 `offset_fetch_clause`（`FETCH FIRST n ROW ONLY`），并支持 `ROWNUM` 伪列         | ✅ 两种分页都能用，`.last("limit 1")` 理论上可原样保留           |
| **字符长度单位**           | `VARCHAR(Size[byte \| char])`，**"若不指定单位，默认为 byte"**；HEAP 表超过 8000 字节的列自动转 LOB                                                | ⚠️ **P0 风险**：`VARCHAR(128)` 在 UTF8 下只能存约 42 个汉字 |
| **DATE 类型**          | 范围 `1-1-1 00:00:00 ~ 9999-12-31 23:59:59`，含时分秒                                                                               | ✅ `DATETIME` 可映射为 `DATE`，也建议用 `TIMESTAMP` 更保险   |
| **空串与 NULL**         | "YashanDB yashan 语法模式将空字符串同样视作 NULL 处理"                                                                                      | ⚠️ 与 MySQL 不同的语义，需回归                            |
| **TINYINT / BIGINT** | 均支持（Oracle 反而不支持，崖山额外提供）                                                                                                     | ✅ `TINYINT`、`BIGINT` 可原样保留                      |
| **自增列**              | CREATE TABLE 支持 `identity_clause`：`GENERATED ALWAYS/BY DEFAULT [ON NULL] AS IDENTITY`，也支持 `SEQUENCE` + `DEFAULT seq.NEXTVAL` | ✅ 自增有成熟方案，二选一                                   |
| **CONCAT 函数**        | 内置函数清单含 `CONCAT`                                                                                                             | ✅ 大概率可用，PoC 复核                                  |
| **EXTRACT 函数**       | 时间处理函数含 `EXTRACT`                                                                                                            | ✅ `YEAR()` 的标准替代                                |
| **约束/索引**            | 支持 in-line / out-of-line 约束，UNIQUE、PRIMARY KEY、FOREIGN KEY、CHECK、(NOT)NULL；BTree 唯一/非唯一索引                                    | ✅ 转换无碍，但语法要改成 Oracle 形态                         |
| **字符集**              | 支持 UTF8、GBK、GB18030 等；排序支持 `UTF8_GENERAL_CI` 等                                                                               | ✅ 可对齐 utf8mb4_unicode_ci                        |
| **JDBC 驱动**          | Maven Central 有 `com.yashandb:yashandb-jdbc`；Driver=`com.yashandb.jdbc.Driver`；URL=`jdbc:yasdb://host:1688/dbname`；支持 JDK8+  | ✅ 驱动获取无阻碍                                       |
| **Flyway**           | Redgate 官方支持列表（50+ DBMS）**不含 YashanDB**                                                                                      | ❌ **P0 风险**，必须另行处置                              |

---

## 3. 改造点清单（按优先级）


### P0 — 不做就无法跑起来

#### ① Flyway 自动迁移失效

- 现状：`pom.xml` 引入 `flyway-core + flyway-mysql`，`application.yml` 中 `spring.flyway.enabled=true`，V1~V8 均为 MySQL 方言。
- 问题：Flyway 官方不支持 YashanDB，启动时会因无法识别数据库类型而失败；且 V1~V8 的 DDL 语法崖山也不认。
- **方案（推荐度从高到低）**：
  1. **关闭 Flyway + 自研极简 migration runner**（约 60～80 行）：应用启动时扫描 `db/migration-yashan/*.sql`，用自建 `schema_version` 表记录已执行版本，按序执行未执行的脚本。保留 Flyway 那套"版本号 + 只跑一次"的心智模型，且不引入外部依赖风险。
  2. **用崖山官方 YMP 迁移平台**做一次性元数据+数据迁移，之后 DDL 变更走人工脚本 + 台账（适合政务项目"变更要评审留痕"的管理要求）。
  3. **Liquibase**：生态同样未官方覆盖崖山，风险不低于方案 1，不推荐。
- 改动：`application.yml` 中 `spring.flyway.enabled=false`；`pom.xml` 移除 `flyway-mysql`（可选保留 `flyway-core` 供 MySQL 环境继续使用）；新增 `db/migration-yashan/` 目录。

#### ② VARCHAR 字节/字符语义 —— 最容易踩、也最容易在上线后才炸

- 现状：`project.name VARCHAR(128)`、`project.remark VARCHAR(500)`、`phase_template.description VARCHAR(255)` 等，MySQL 下按**字符**计。
- 崖山：不指定单位时**按字节**。UTF8 下一个汉字 3 字节 → `VARCHAR(128)` 只能存 **42 个汉字**；政务项目名称轻松超过。
- **方案**：所有 `VARCHAR(n)` 一律显式改为 `VARCHAR(n CHAR)`。  
  注意官方提示：HEAP 表中超过 8000 字节的列会**自动转 LOB** 存储，因此 `VARCHAR(4000 CHAR)`（最大 16000 字节）会被转 LOB——本项目最大才 500，**不受影响**。
- 附带：确认崖山库的 `UTF8` 是否覆盖 4 字节字符（emoji）。若附件文件名可能含 emoji，需改用 `UTF8MB4`（规格表中与 UTF8 并列存在，需向厂商确认）。

#### ③ 主键自增（AUTO_INCREMENT → identity / 序列）

- 现状：`BaseEntity` 用 `IdType.AUTO`，全部 11 张表 `BIGINT NOT NULL AUTO_INCREMENT`。
- 崖山：无 `AUTO_INCREMENT`，但有 identity 列与序列两种等价方案。
- **推荐方案 B（序列 + MP `@KeySequence`）**，理由：不改业务语义、id 仍连续递增、`orderByAsc(Project::getId())` 行为不变。
  ```sql
  CREATE SEQUENCE seq_project START WITH 1 INCREMENT BY 1 CACHE 20;
  CREATE TABLE project (
      id BIGINT DEFAULT seq_project.NEXTVAL NOT NULL,
      ...
      PRIMARY KEY (id)
  );
  ```
  ```java
  @Data
  public abstract class BaseEntity implements Serializable {
      @TableId(type = IdType.INPUT)   // 原为 IdType.AUTO
      private Long id;
      ...
  }
  ```
  并在各实体类加 `@KeySequence("SEQ_XXX")`（MP 在 `DbType.ORACLE` 下会走 `OracleKeyGenerator`，用 `SELECT SEQ_XXX.NEXTVAL FROM DUAL` 预取主键，insert 后 id 已回填，`ProjectService.insert()` 后立刻 `pj.getId()` 的逻辑不受影响）。
- **备选方案 A（identity 列 + JDBC `getGeneratedKeys`）**：DDL 最简洁（`id BIGINT GENERATED BY DEFAULT AS IDENTITY`），但依赖崖山 JDBC 对 `getGeneratedKeys` 的支持，需 PoC 实测。
- **备选方案 C（`IdType.ASSIGN_ID` 雪花算法）**：改动最小（只改 `BaseEntity` 一行，无需建序列、无需关心驱动），代价是 id 变成 19 位雪花值、不再连续。若接受 id 不连续，这是**性价比最高**的方案，可作为 PoC 受阻时的兜底。
- 迁移后注意：所有序列的 `START WITH` 必须设为 `当前 MAX(id)+1`，否则新插入会撞主键。


















### 12.3 修正后的行动优先级

综合 10～12 章，优先级应为：

| 序 | 事项 | 说明 |
| --- | --- | --- |
| **0** | **拿到崖山开发版实例** | **真正的前置条件**。拿不到，后面全是纸上谈兵；拿到了，Flyway 立刻变成阻塞项 |
| **1** | **PoC 验证**（2～3 人日） | 首验 6 项：JDBC 连通、**`VARCHAR2` 是否被接受**、`DbType.ORACLE` 分页、主键回填、`LocalDateTime` 映射、`VARCHAR(n CHAR)` 存满汉字 |
| **2** | **写数据库开发规范 + 建表/实体模板** | 用**白名单**形式（类型白名单 + 函数白名单 + DDL 模板）。这是 AI 开发的杠杆点，越早越好 |
| **3** | **真实实例的集成测试进 CI** | 11.3 新风险的唯一兜底 |
| **4** | **Flyway 替代 runner** | 仅当决定直连崖山开发时才需要。**成本极低（AI 几分钟）**，不值得为它纠结 |

**被下调的项**：Flyway 从"P0 最先做"下调到"第 4 位、按需触发"。它不是这个决策的关键变量。

*修订记录：*
- *2026-09-08 初版 —— 存量系统迁移视角的可行性分析与实施方案（第 1～8 章）*
- *2026-09-08 增补第 9 章 —— MySQL 兼容模式对比*
- *2026-09-08 增补第 10 章 —— 基于"项目处于新建阶段"这一事实，修正 9.5 的选型倾向为"直接上 yashan（Oracle）模式"，并给出新建阶段专属的落地路径*
- *2026-09-08 增补第 11 章 —— 基于"后续开发由 AI 完成"这一前提，修正 10.1 的"开发更重"论基本失效；代价从持续重复劳动转移为一次性规范建设 + CI 验证*
- *2026-09-08 增补第 12 章 —— 澄清两个务实问题：① Flyway 在开发阶段主要就是变更记录 + 环境可重建，且**不是**开发阶段阻塞项，优先级从 P0 下调至"切库时按需触发"；② AI 按 Oracle 模式开发方向正确，但第一号漂移点是 `VARCHAR2` vs `VARCHAR`，须用**类型/函数白名单**约束 AI 而非黑名单*
