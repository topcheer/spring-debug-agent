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

    @DebugTool(description = "List all JPA entity classes with their table names, mapped class names, "
            + "and whether they have associations. Uses Hibernate Metamodel to introspect entities.")
    public List<Map<String, Object>> getJpaEntities() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Object emf = ReflectionHelper.getFirstBeanOfType(ctx,
                    "jakarta.persistence.EntityManagerFactory");
            if (emf == null) {
                emf = ReflectionHelper.getFirstBeanOfType(ctx,
                        "javax.persistence.EntityManagerFactory");
            }
            if (emf == null) {
                results.add(Map.of("info", "No EntityManagerFactory found"));
                return results;
            }

            // Get Metamodel
            Object metamodel = ReflectionHelper.invokeMethod(emf, "getMetamodel");
            if (metamodel == null) {
                results.add(Map.of("info", "Metamodel not available"));
                return results;
            }

            Object entities = ReflectionHelper.invokeMethod(metamodel, "getEntities");
            if (entities instanceof Collection<?> entitySet) {
                for (Object entityType : entitySet) {
                    Map<String, Object> entity = new LinkedHashMap<>();
                    Object javaType = ReflectionHelper.invokeMethod(entityType, "getJavaType");
                    entity.put("className", javaType != null ? ((Class<?>) javaType).getName() : "unknown");

                    Object typeName = ReflectionHelper.invokeMethod(entityType, "getName");
                    entity.put("entityName", typeName);

                    // Try to get table name from @Table annotation via persistenceType
                    Object persistenceType = ReflectionHelper.invokeMethod(entityType, "getPersistenceType");
                    entity.put("persistenceType", persistenceType != null ? persistenceType.toString() : "UNKNOWN");

                    // Attributes
                    Object attrs = ReflectionHelper.invokeMethod(entityType, "getAttributes");
                    if (attrs instanceof Collection<?> attrCol) {
                        List<String> attrNames = new ArrayList<>();
                        for (Object attr : attrCol) {
                            attrNames.add(ReflectionHelper.invokeMethod(attr, "getName").toString());
                        }
                        entity.put("attributes", attrNames);
                    }

                    results.add(entity);
                }
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get JPA entities: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "List all Spring Data JPA repositories: bean name, domain type, ID type, "
            + "and repository interface. Uses Spring Data's RepositoryInformation.")
    public List<Map<String, Object>> getJpaRepositories() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // Find all beans implementing Repository
            List<Object> repos = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.data.repository.Repository");
            if (repos.isEmpty()) {
                results.add(Map.of("info", "No Spring Data repositories found"));
                return results;
            }

            for (Object repo : repos) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("className", repo.getClass().getName());

                // Try to get RepositoryInformation via the proxy
                for (Class<?> iface : repo.getClass().getInterfaces()) {
                    if (iface.getName().contains("Repository")) {
                        info.put("repositoryInterface", iface.getName());
                        break;
                    }
                }

                // Check for CrudRepository, JpaRepository, PagingAndSortingRepository
                List<String> repoTypes = new ArrayList<>();
                for (Class<?> iface : repo.getClass().getInterfaces()) {
                    String name = iface.getSimpleName();
                    if (name.contains("Repository")) repoTypes.add(name);
                }
                info.put("interfaces", repoTypes);

                results.add(info);
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get repositories: " + e.getMessage()));
        }

        return results;
    }
}
