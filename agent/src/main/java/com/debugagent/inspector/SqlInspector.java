package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.*;

/**
 * Deep SQL diagnostics tools.
 * Inspects active SQL queries, slow queries, and connection leak detection.
 * Conditional on javax.sql.DataSource being available.
 */
public class SqlInspector implements ApplicationContextAware {

    private ApplicationContext ctx;
    private final List<Map<String, Object>> slowQueryHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HISTORY = 200;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get active SQL queries and database connection details. Shows connection URL, driver, active connections, and metadata. Useful for understanding database connectivity.")
    public Map<String, Object> getActiveSqlQueries() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            DataSource ds = ctx.getBean(DataSource.class);

            try (Connection conn = ds.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                Map<String, Object> dbInfo = new LinkedHashMap<>();
                dbInfo.put("databaseProductName", meta.getDatabaseProductName());
                dbInfo.put("databaseProductVersion", meta.getDatabaseProductVersion());
                dbInfo.put("driverName", meta.getDriverName());
                dbInfo.put("driverVersion", meta.getDriverVersion());
                dbInfo.put("url", meta.getURL());
                dbInfo.put("userName", meta.getUserName());
                dbInfo.put("transactionIsolation", isolationName(conn.getTransactionIsolation()));
                dbInfo.put("autoCommit", conn.getAutoCommit());
                dbInfo.put("readOnly", conn.isReadOnly());
                dbInfo.put("catalog", conn.getCatalog());
                result.put("databaseInfo", dbInfo);

                // Schema/table info
                List<Map<String, String>> tables = new ArrayList<>();
                try (var rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                    while (rs.next() && tables.size() < 50) {
                        Map<String, String> t = new LinkedHashMap<>();
                        t.put("schema", rs.getString("TABLE_SCHEM"));
                        t.put("name", rs.getString("TABLE_NAME"));
                        t.put("type", rs.getString("TABLE_TYPE"));
                        tables.add(t);
                    }
                }
                result.put("tables", tables);
                result.put("tableCount", tables.size());

                // Connection pool stats via Hikari
                try {
                    Object hikariDs = conn.unwrap(Class.forName("com.zaxxer.hikari.HikariDataSource"));
                    if (hikariDs != null) {
                        Object pool = ReflectionHelper.invokeMethod(hikariDs, "getHikariPoolMXBean");
                        if (pool != null) {
                            Map<String, Object> poolStats = new LinkedHashMap<>();
                            poolStats.put("activeConnections", ReflectionHelper.invokeMethod(pool, "getActiveConnections"));
                            poolStats.put("idleConnections", ReflectionHelper.invokeMethod(pool, "getIdleConnections"));
                            poolStats.put("totalConnections", ReflectionHelper.invokeMethod(pool, "getTotalConnections"));
                            poolStats.put("threadsAwaitingConnection", ReflectionHelper.invokeMethod(pool, "getThreadsAwaitingConnection"));
                            result.put("connectionPool", poolStats);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // JDBC URL patterns for type detection
            String url = (String) ((Map<?, ?>) result.get("databaseInfo")).get("url");
            if (url != null) {
                result.put("databaseType", detectDbType(url));
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get slow SQL queries captured by Hibernate statistics. Shows query string, execution count, max/avg/min execution time, and row count. Requires Hibernate generate_statistics=true.")
    public List<Map<String, Object>> getSlowSql(
            @ToolParam(description = "Minimum average execution time in ms (default 10)") Integer minAvgMs
    ) {
        List<Map<String, Object>> slowQueries = new ArrayList<>();
        long threshold = minAvgMs != null ? minAvgMs : 10;

        try {
            // Try to get Hibernate Statistics
            String[] emfNames = ctx.getBeanNamesForType(
                    Class.forName("org.springframework.orm.jpa.EntityManagerFactory"));
            if (emfNames.length == 0) {
                emfNames = ctx.getBeanNamesForType(
                        Class.forName("jakarta.persistence.EntityManagerFactory"));
            }

            for (String name : emfNames) {
                Object emf = ctx.getBean(name);
                Object sessionFactory;
                try {
                    Method unwrap = emf.getClass().getMethod("unwrap", Class.class);
                    sessionFactory = unwrap.invoke(emf, Class.forName("org.hibernate.engine.spi.SessionFactoryImplementor"));
                } catch (Exception ex) { continue; }

                if (sessionFactory == null) continue;

                Object statistics = ReflectionHelper.invokeMethod(sessionFactory, "getStatistics");
                if (statistics == null) continue;

                // Get query statistics
                Method getQueryStatistics = statistics.getClass().getMethod("getQueryStatistics");
                Map<String, ?> queryStats = (Map<String, ?>) getQueryStatistics.invoke(statistics);

                for (Map.Entry<String, ?> entry : queryStats.entrySet()) {
                    Object qs = entry.getValue();
                    long avgTime = (Long) ReflectionHelper.invokeMethod(qs, "getExecutionAvgTime");
                    if (avgTime >= threshold) {
                        Map<String, Object> qInfo = new LinkedHashMap<>();
                        qInfo.put("query", entry.getKey());
                        qInfo.put("executionCount", ReflectionHelper.invokeMethod(qs, "getExecutionCount"));
                        qInfo.put("avgTimeMs", avgTime);
                        qInfo.put("maxTimeMs", ReflectionHelper.invokeMethod(qs, "getExecutionMaxTime"));
                        qInfo.put("minTimeMs", ReflectionHelper.invokeMethod(qs, "getExecutionMinTime"));
                        qInfo.put("totalRows", ReflectionHelper.invokeMethod(qs, "getExecutionRowCount"));
                        qInfo.put("cacheHitCount", ReflectionHelper.invokeMethod(qs, "getCacheHitCount"));
                        qInfo.put("cacheMissCount", ReflectionHelper.invokeMethod(qs, "getCacheMissCount"));
                        slowQueries.add(qInfo);
                    }
                }
            }

            // Sort by avgTime descending
            slowQueries.sort((a, b) -> Long.compare(
                    (Long) b.getOrDefault("avgTimeMs", 0L),
                    (Long) a.getOrDefault("avgTimeMs", 0L)));

        } catch (Exception e) {
            slowQueries.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return slowQueries.size() > 50 ? slowQueries.subList(0, 50) : slowQueries;
    }

    @DebugTool(description = "Detect potential connection leaks: connections checked out but not returned, connections held too long, pool exhaustion indicators. Critical for diagnosing pool exhaustion issues.")
    public Map<String, Object> detectConnectionLeak() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            DataSource ds = ctx.getBean(DataSource.class);

            // Try HikariCP leak detection
            try {
                Object hikariDs = ds.unwrap(Class.forName("com.zaxxer.hikari.HikariDataSource"));
                if (hikariDs != null) {
                    Map<String, Object> hikariInfo = new LinkedHashMap<>();

                    // Pool config
                    Object config = ReflectionHelper.invokeMethod(hikariDs, "getMaximumPoolSize");
                    hikariInfo.put("maxPoolSize", config);

                    Object leakDetectionTime = ReflectionHelper.invokeMethod(hikariDs, "getLeakDetectionThreshold");
                    hikariInfo.put("leakDetectionThresholdMs", leakDetectionTime);

                    // Pool stats
                    Object pool = ReflectionHelper.invokeMethod(hikariDs, "getHikariPoolMXBean");
                    if (pool != null) {
                        int active = (Integer) ReflectionHelper.invokeMethod(pool, "getActiveConnections");
                        int idle = (Integer) ReflectionHelper.invokeMethod(pool, "getIdleConnections");
                        int total = (Integer) ReflectionHelper.invokeMethod(pool, "getTotalConnections");
                        int waiting = (Integer) ReflectionHelper.invokeMethod(pool, "getThreadsAwaitingConnection");

                        hikariInfo.put("activeConnections", active);
                        hikariInfo.put("idleConnections", idle);
                        hikariInfo.put("totalConnections", total);
                        hikariInfo.put("threadsAwaitingConnection", waiting);

                        // Leak indicators
                        List<String> warnings = new ArrayList<>();
                        int maxPool = (Integer) config;
                        if (active == maxPool) {
                            warnings.add("CRITICAL: All " + maxPool + " connections are in use (pool exhausted)");
                        }
                        if (active > maxPool * 0.8) {
                            warnings.add("WARNING: " + active + "/" + maxPool + " connections in use (>80%)");
                        }
                        if (waiting > 0) {
                            warnings.add("WARNING: " + waiting + " threads waiting for connections (potential leak)");
                        }
                        int leakThreshold = (Integer) leakDetectionTime;
                        if (leakThreshold == 0) {
                            warnings.add("INFO: Leak detection is disabled (leakDetectionThreshold=0). " +
                                    "Set to 60000 (60s) to enable.");
                        }
                        hikariInfo.put("warnings", warnings);
                        hikariInfo.put("poolUtilization", String.format("%.1f%%", active * 100.0 / maxPool));
                    }

                    result.put("hikari", hikariInfo);
                }
            } catch (Exception ignored) {}

            // Generic DataSource info
            if (result.isEmpty()) {
                result.put("note", "HikariCP not detected. Generic connection pool monitoring unavailable.");
                try (Connection conn = ds.getConnection()) {
                    result.put("canGetConnection", true);
                } catch (Exception e) {
                    result.put("canGetConnection", false);
                    result.put("error", "Cannot get connection: " + e.getMessage());
                    result.put("leakSuspected", true);
                }
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    private String isolationName(int level) {
        return switch (level) {
            case Connection.TRANSACTION_NONE -> "NONE";
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "UNKNOWN(" + level + ")";
        };
    }

    private String detectDbType(String url) {
        if (url == null) return "unknown";
        if (url.contains(":h2:")) return "H2";
        if (url.contains(":mysql:")) return "MySQL";
        if (url.contains(":postgresql:")) return "PostgreSQL";
        if (url.contains(":oracle:")) return "Oracle";
        if (url.contains(":sqlserver:")) return "SQL Server";
        if (url.contains(":mariadb:")) return "MariaDB";
        return "other";
    }
}
