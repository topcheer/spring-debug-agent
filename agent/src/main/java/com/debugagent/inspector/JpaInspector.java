package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Hibernate/JPA query statistics inspection tool.
 * Uses reflection on SessionFactory — no hard Hibernate dependency.
 */
public class JpaInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get JPA/Hibernate query statistics: executed queries, entity loads, collection fetches, slow queries, and potential N+1 issues. Requires Hibernate statistics enabled.")
    public Map<String, Object> getJpaQueryStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Try EntityManagerFactory (JPA standard)
            Object emf = ReflectionHelper.getFirstBeanOfType(ctx,
                    "jakarta.persistence.EntityManagerFactory");
            if (emf == null) {
                emf = ReflectionHelper.getFirstBeanOfType(ctx,
                        "javax.persistence.EntityManagerFactory");
            }

            if (emf == null) {
                result.put("info", "No EntityManagerFactory found. JPA/Hibernate not configured.");
                return result;
            }

            // Unwrap to get SessionFactory (Hibernate-specific)
            Object sessionFactory = null;
            try {
                Method unwrap = emf.getClass().getMethod("unwrap", Class.class);
                sessionFactory = unwrap.invoke(emf, Class.forName("org.hibernate.SessionFactory", false, ctx.getClassLoader()));
            } catch (Exception ignored) {}

            if (sessionFactory == null) {
                result.put("info", "EntityManagerFactory found but not Hibernate-backed. Cannot get query statistics.");
                return result;
            }

            // Get Statistics object
            Object statistics = ReflectionHelper.invokeMethod(sessionFactory, "getStatistics");
            if (statistics == null) {
                result.put("info", "Statistics not available. Set 'spring.jpa.properties.hibernate.generate_statistics=true'");
                return result;
            }

            result.put("statisticsEnabled", ReflectionHelper.invokeMethod(statistics, "isStatisticsEnabled"));

            // General stats
            Object openSessionCount = ReflectionHelper.invokeMethod(statistics, "getSessionOpenCount");
            Object closeSessionCount = ReflectionHelper.invokeMethod(statistics, "getSessionCloseCount");
            Object flushCount = ReflectionHelper.invokeMethod(statistics, "getFlushCount");
            Object connectCount = ReflectionHelper.invokeMethod(statistics, "getConnectCount");
            result.put("sessionsOpened", openSessionCount);
            result.put("sessionsClosed", closeSessionCount);
            result.put("flushes", flushCount);
            result.put("dbConnections", connectCount);

            // Entity stats
            Object entityLoadCount = ReflectionHelper.invokeMethod(statistics, "getEntityLoadCount");
            Object entityFetchCount = ReflectionHelper.invokeMethod(statistics, "getEntityFetchCount");
            Object entityUpdateCount = ReflectionHelper.invokeMethod(statistics, "getEntityUpdateCount");
            Object entityInsertCount = ReflectionHelper.invokeMethod(statistics, "getEntityInsertCount");
            Object entityDeleteCount = ReflectionHelper.invokeMethod(statistics, "getEntityDeleteCount");
            result.put("entityLoads", entityLoadCount);
            result.put("entityFetches", entityFetchCount);
            result.put("entityInserts", entityInsertCount);
            result.put("entityUpdates", entityUpdateCount);
            result.put("entityDeletes", entityDeleteCount);

            // Collection stats (N+1 detection)
            Object collectionLoadCount = ReflectionHelper.invokeMethod(statistics, "getCollectionLoadCount");
            Object collectionFetchCount = ReflectionHelper.invokeMethod(statistics, "getCollectionFetchCount");
            result.put("collectionLoads", collectionLoadCount);
            result.put("collectionFetches", collectionFetchCount);

            // Query stats
            Object queryExecutionCount = ReflectionHelper.invokeMethod(statistics, "getQueryExecutionCount");
            Object queryExecutionMaxTime = ReflectionHelper.invokeMethod(statistics, "getQueryExecutionMaxTime");
            Object queryExecutionMaxTimeQueryString = ReflectionHelper.invokeMethod(statistics, "getQueryExecutionMaxTimeQueryString");
            result.put("queryExecutions", queryExecutionCount);
            result.put("slowestQueryTimeMs", queryExecutionMaxTime);
            if (queryExecutionMaxTimeQueryString != null) {
                result.put("slowestQuery", ReflectionHelper.safeToString(queryExecutionMaxTimeQueryString));
            }

            // Second-level cache stats
            Object secondLevelCacheHitCount = ReflectionHelper.invokeMethod(statistics, "getSecondLevelCacheHitCount");
            Object secondLevelCacheMissCount = ReflectionHelper.invokeMethod(statistics, "getSecondLevelCacheMissCount");
            Object secondLevelCachePutCount = ReflectionHelper.invokeMethod(statistics, "getSecondLevelCachePutCount");
            result.put("l2CacheHits", secondLevelCacheHitCount);
            result.put("l2CacheMisses", secondLevelCacheMissCount);
            result.put("l2CachePuts", secondLevelCachePutCount);

            // Per-query stats
            Object[] queries = (Object[]) ReflectionHelper.invokeMethod(statistics, "getQueries");
            if (queries != null && queries.length > 0) {
                List<Map<String, Object>> queryList = new ArrayList<>();
                int shown = Math.min(queries.length, 20);
                for (int i = 0; i < shown; i++) {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("query", ReflectionHelper.safeToString(queries[i]));
                    // Try getQueryStatistics(queryString)
                    Object qStats = null;
                    try {
                        Method getQS = statistics.getClass().getMethod("getQueryStatistics", String.class);
                        qStats = getQS.invoke(statistics, queries[i]);
                    } catch (Exception ignored) {}
                    if (qStats != null) {
                        q.put("executionCount", ReflectionHelper.invokeMethod(qStats, "getExecutionCount"));
                        q.put("avgTimeMs", ReflectionHelper.invokeMethod(qStats, "getExecutionAvgTime"));
                        q.put("rowCount", ReflectionHelper.invokeMethod(qStats, "getExecutionRowCount"));
                    }
                    queryList.add(q);
                }
                result.put("topQueries", queryList);
            }

        } catch (Exception e) {
            result.put("error", "Failed to get JPA stats: " + e.getMessage());
        }

        return result;
    }
}
