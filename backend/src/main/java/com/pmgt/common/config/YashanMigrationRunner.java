package com.pmgt.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 崖山 YashanDB 轻量数据库迁移 Runner（Flyway 替代方案）。
 *
 * <p>心智模型 = "版本号排序 + 只跑一次 + 版本表"：</p>
 * <ol>
 *   <li>确保版本表 schema_version 存在（不存在则创建）；</li>
 *   <li>扫描 classpath:db/migration-yashan/*.sql，按文件名 {@code V{n}__} 前缀数值排序；</li>
 *   <li>跳过 schema_version 已登记的版本（幂等）；</li>
 *   <li>逐文件剥离整行注释、按行尾分号切句，JDBC 顺序执行；</li>
 *   <li>文件全部语句成功后在 schema_version 登记；任一句失败立即抛出异常阻止应用启动（fail-fast）。</li>
 * </ol>
 *
 * <p>注意：Oracle/yashan 的 DDL 为隐式提交、无事务回滚，失败重跑需人工检查部分执行的中间态
 * （与本库纯 DDL、无 PL/SQL 块的低风险相匹配）。</p>
 *
 * <p>{@code @Order(1)}：必须先于 DataInitializer 等业务初始化执行，保证建表/种子先行。</p>
 */
@Component
@Order(1)
public class YashanMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(YashanMigrationRunner.class);

    private static final Pattern VERSION_PATTERN = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String MIGRATION_LOCATION = "classpath*:db/migration-yashan/*.sql";
    private static final String VERSION_TABLE = "schema_version";

    private final JdbcTemplate jdbcTemplate;

    public YashanMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureVersionTable();
        Set<String> applied = loadAppliedVersions();

        List<Resource> scripts = collectScripts();
        scripts.sort(Comparator.comparingLong(this::versionOf));

        for (Resource script : scripts) {
            String name = script.getFilename();
            if (applied.contains(name)) {
                log.info("[yashan-migration] 已执行，跳过: {}", name);
                continue;
            }
            log.info("[yashan-migration] 开始执行: {}", name);
            for (String sql : readStatements(script)) {
                String stmt = sql.trim();
                if (stmt.endsWith(";")) {
                    stmt = stmt.substring(0, stmt.length() - 1).trim(); // 崖山 JDBC execute 不接受语句尾分号
                }
                jdbcTemplate.execute(stmt);
            }
            jdbcTemplate.update(
                    "INSERT INTO " + VERSION_TABLE + " (version, applied_at) VALUES (?, ?)",
                    name, Timestamp.valueOf(LocalDateTime.now()));
            log.info("[yashan-migration] 完成并登记: {}", name);
        }
        log.info("[yashan-migration] 迁移检查完毕，共 {} 个脚本", scripts.size());
    }

    /** 建版本表（Oracle/yashan 无 CREATE TABLE IF NOT EXISTS，先查字典视图再建）。 */
    private void ensureVersionTable() {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tables WHERE table_name = ?",
                Integer.class, VERSION_TABLE.toUpperCase());
        if (cnt != null && cnt > 0) {
            return;
        }
        try {
            jdbcTemplate.execute("CREATE TABLE " + VERSION_TABLE + " ("
                    + "version    VARCHAR(100) PRIMARY KEY, "
                    + "applied_at TIMESTAMP)");
            log.info("[yashan-migration] 版本表 {} 已创建", VERSION_TABLE);
        } catch (DataAccessException e) {
            // 并发/已由他人创建：确认一次，确实存在则放行
            Integer recheck = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_tables WHERE table_name = ?",
                    Integer.class, VERSION_TABLE.toUpperCase());
            if (recheck == null || recheck == 0) {
                throw e;
            }
        }
    }

    private Set<String> loadAppliedVersions() {
        return new TreeSet<>(jdbcTemplate.queryForList(
                "SELECT version FROM " + VERSION_TABLE, String.class));
    }

    private List<Resource> collectScripts() {
        List<Resource> scripts = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(MIGRATION_LOCATION);
            for (Resource r : resources) {
                if (r.getFilename() != null && VERSION_PATTERN.matcher(r.getFilename()).matches()) {
                    scripts.add(r);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描迁移脚本失败: " + MIGRATION_LOCATION, e);
        }
        return scripts;
    }

    private long versionOf(Resource r) {
        Matcher m = VERSION_PATTERN.matcher(r.getFilename() == null ? "" : r.getFilename());
        return m.matches() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
    }

    /**
     * 读脚本并按语句切分：跳过空行与整行 "--" 注释；
     * 以行尾分号作为语句边界（本项目 DDL 值内不含分号，可安全切分）。
     */
    private List<String> readStatements(Resource script) {
        List<String> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        try (InputStream in = script.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                buf.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    statements.add(buf.toString());
                    buf.setLength(0);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取迁移脚本失败: " + script.getFilename(), e);
        }
        if (!buf.isEmpty()) {
            statements.add(buf.toString());
        }
        return statements;
    }
}
