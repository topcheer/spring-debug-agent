package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Database connection pool inspection tool.
 * Uses reflection to read pool stats from HikariCP, Tomcat DBCP2, or generic DataSource.
 */
public class DataSourceInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get database connection pool statistics (active, idle, total connections, max pool size). Supports HikariCP, Tomcat DBCP2, and generic pools.")
    public List<Map<String, Object>> getDataSourceInfo() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Map<String, DataSource> dsMap = ctx.getBeansOfType(DataSource.class);
            for (Map.Entry<String, DataSource> entry : dsMap.entrySet()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("beanName", entry.getKey());
                info.put("type", entry.getValue().getClass().getName());

                Map<String, Object> pool = inspectPool(entry.getValue());
                if (pool.isEmpty()) {
                    info.put("status", "No pool statistics available (unsupported DataSource type)");
                } else {
                    info.put("pool", pool);
                }
                results.add(info);
            }

            if (results.isEmpty()) {
                results.add(Map.of("error", "No DataSource beans found in context"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to inspect DataSource: " + e.getMessage()));
        }

        return results;
    }

    private Map<String, Object> inspectPool(DataSource ds) {
        Map<String, Object> pool = new LinkedHashMap<>();
        Object unwrapped = unwrapDataSource(ds);

        // Try HikariCP
        if (tryHikari(unwrapped, pool)) return pool;
        // Try Tomcat JDBC Pool
        if (tryTomcatDbcp(unwrapped, pool)) return pool;

        return pool;
    }

    private Object unwrapDataSource(DataSource ds) {
        // Try to unwrap to get the underlying pool
        try {
            return ds.unwrap(DataSource.class);
        } catch (Exception e) {
            return ds;
        }
    }

    private boolean tryHikari(Object ds, Map<String, Object> pool) {
        try {
            // HikariDataSource.getHikariPoolMXBean()
            Object poolBean = ReflectionHelper.invokeMethod(ds, "getHikariPoolMXBean");
            if (poolBean == null) {
                // Maybe HikariPoolDataSource — try getting through hikariPool field
                Object hikariPool = ReflectionHelper.getFieldValue(ds, "pool");
                if (hikariPool != null) {
                    poolBean = ReflectionHelper.invokeMethod(hikariPool, "getHikariPoolMXBean");
                }
            }
            if (poolBean == null) return false;

            pool.put("poolType", "HikariCP");
            pool.put("activeConnections", ReflectionHelper.invokeMethod(poolBean, "getActiveConnections"));
            pool.put("idleConnections", ReflectionHelper.invokeMethod(poolBean, "getIdleConnections"));
            pool.put("totalConnections", ReflectionHelper.invokeMethod(poolBean, "getTotalConnections"));
            pool.put("threadsAwaitingConnection", ReflectionHelper.invokeMethod(poolBean, "getThreadsAwaitingConnection"));

            // Pool config
            Object config = ReflectionHelper.getFieldValue(ds, "poolConfig");
            if (config == null) config = ReflectionHelper.invokeMethod(ds, "getMaximumPoolSize");
            if (config != null) {
                Object maxSize = ReflectionHelper.invokeMethod(ds, "getMaximumPoolSize");
                if (maxSize instanceof Integer) pool.put("maxPoolSize", maxSize);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryTomcatDbcp(Object ds, Map<String, Object> pool) {
        try {
            Method getActive = ReflectionHelper.findMethod(ds.getClass(), "getNumActive");
            Method getIdle = ReflectionHelper.findMethod(ds.getClass(), "getNumIdle");
            Method getMaxActive = ReflectionHelper.findMethod(ds.getClass(), "getMaxTotal");
            if (getActive == null) return false;

            pool.put("poolType", "Tomcat/DBCP2");
            getActive.setAccessible(true);
            getIdle.setAccessible(true);
            pool.put("activeConnections", getActive.invoke(ds));
            pool.put("idleConnections", getIdle.invoke(ds));
            if (getMaxActive != null) {
                getMaxActive.setAccessible(true);
                pool.put("maxPoolSize", getMaxActive.invoke(ds));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
