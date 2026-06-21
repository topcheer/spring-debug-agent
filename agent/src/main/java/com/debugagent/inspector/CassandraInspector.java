package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Apache Cassandra diagnostic tools.
 * Inspects cluster topology, keyspaces, CQL session, prepared statements, and query stats.
 * Conditional on spring-boot-starter-data-cassandra or java-driver-core being on classpath.
 */
public class CassandraInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Object getCqlSession() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "com.datastax.oss.driver.api.core.CqlSession");
    }

    @DebugTool(description = "Inspect Cassandra cluster topology: connected nodes, datacenters, "
            + "racks, contact points, local datacenter, and protocol version. "
            + "Useful for diagnosing connection issues or topology awareness problems.")
    public Map<String, Object> getCassandraClusterInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object session = getCqlSession();
        if (session == null) {
            result.put("status", "not_configured");
            result.put("hint", "No CqlSession found. Add spring-boot-starter-data-cassandra.");
            return result;
        }

        result.put("sessionClass", session.getClass().getSimpleName());

        try {
            Object context = ReflectionHelper.invokeMethod(session, "getContext");
            if (context != null) {
                // Get configured nodes
                Object config = ReflectionHelper.invokeMethod(context, "getConfig");
                if (config != null) {
                    Object driverConfig = ReflectionHelper.invokeMethod(config, "getDefaultProfile");
                    if (driverConfig != null) {
                        result.put("defaultProfile", driverConfig.toString());
                    }
                }
            }

            // Get metadata
            Object metadata = ReflectionHelper.invokeMethod(session, "getMetadata");
            if (metadata != null) {
                Object nodes = ReflectionHelper.invokeMethod(metadata, "getNodes");
                if (nodes instanceof Map) {
                    List<Map<String, Object>> nodeList = new ArrayList<>();
                    for (Object n : ((Map<?, ?>) nodes).values()) {
                        Map<String, Object> node = new LinkedHashMap<>();
                        node.put("endpoint", ReflectionHelper.invokeMethod(n, "getEndpoint"));
                        node.put("state", ReflectionHelper.invokeMethod(n, "getState"));
                        node.put("openConnections", ReflectionHelper.invokeMethod(n, "getOpenConnections"));
                        Object dc = ReflectionHelper.invokeMethod(n, "getDatacenter");
                        if (dc != null) node.put("datacenter", dc);
                        Object rack = ReflectionHelper.invokeMethod(n, "getRack");
                        if (rack != null) node.put("rack", rack);
                        Object upSince = ReflectionHelper.invokeMethod(n, "getUpSinceMs");
                        if (upSince != null) node.put("upSince", upSince);
                        nodeList.add(node);
                    }
                    result.put("nodes", nodeList);
                }
            }

            // Session name
            Object name = ReflectionHelper.invokeMethod(session, "getName");
            if (name != null) result.put("sessionName", name);

            // Keyspace
            Object keyspace = ReflectionHelper.invokeMethod(session, "getKeyspace");
            result.put("keyspace", keyspace != null ? ReflectionHelper.safeToString(keyspace) : null);

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.cassandra.contact-points",
                "spring.cassandra.port", "spring.cassandra.local-datacenter",
                "spring.cassandra.keyspace-name", "spring.cassandra.username",
                "spring.cassandra.schema-action", "spring.cassandra.connection.connect-timeout",
                "spring.cassandra.connection.init-query-timeout",
                "spring.cassandra.pool.max-requests-per-connection",
                "spring.cassandra.read-timeout", "spring.cassandra.request.consistency"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null && !p.contains("password")) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        return result;
    }

    @DebugTool(description = "List all Cassandra keyspaces and their replication strategies: "
            + "keyspace name, replication class (SimpleStrategy/NetworkTopologyStrategy), "
            + "replication factor, and table count. "
            + "Useful for verifying schema setup and replication configuration.")
    public List<Map<String, Object>> getCassandraKeyspaces() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object session = getCqlSession();
        if (session == null) {
            result.add(Map.of("error", "No CqlSession found"));
            return result;
        }

        try {
            Object metadata = ReflectionHelper.invokeMethod(session, "getMetadata");
            if (metadata != null) {
                Object keyspaces = ReflectionHelper.invokeMethod(metadata, "getKeyspaces");
                if (keyspaces instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) keyspaces).entrySet()) {
                        Map<String, Object> ks = new LinkedHashMap<>();
                        ks.put("name", entry.getKey().toString());

                        Object keyspaceMeta = entry.getValue();
                        Object durableWrites = ReflectionHelper.invokeMethod(keyspaceMeta, "isDurableWrites");
                        if (durableWrites != null) ks.put("durableWrites", durableWrites);

                        Object replication = ReflectionHelper.invokeMethod(keyspaceMeta, "getReplication");
                        if (replication instanceof Map) {
                            ks.put("replication", replication);
                        }

                        Object tables = ReflectionHelper.invokeMethod(keyspaceMeta, "getTables");
                        if (tables instanceof Map) {
                            ks.put("tableCount", ((Map<?, ?>) tables).size());
                        }

                        Object userTypes = ReflectionHelper.invokeMethod(keyspaceMeta, "getUserDefinedTypes");
                        if (userTypes instanceof Map) {
                            ks.put("userTypeCount", ((Map<?, ?>) userTypes).size());
                        }

                        result.add(ks);
                    }
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Failed to get keyspaces: " + e.getClass().getSimpleName()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_keyspaces"));
        }

        return result;
    }

    @DebugTool(description = "Inspect Cassandra CQL session execution info: prepared statements cache, "
            + "request consistency level, default timeout, page size, and session metrics "
            + "(connected nodes, inflight requests, throughput). "
            + "Useful for diagnosing query performance and connection pool issues.")
    public Map<String, Object> getCassandraSessionStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object session = getCqlSession();
        if (session == null) {
            result.put("status", "not_configured");
            return result;
        }

        try {
            // Get metrics if available
            Object metrics = ReflectionHelper.invokeMethod(session, "getMetrics");
            if (metrics != null) {
                result.put("metricsAvailable", true);

                // Try to get specific metrics
                Object registry = ReflectionHelper.invokeMethod(metrics, "getRegistry");
                if (registry != null) {
                    result.put("registryClass", registry.getClass().getSimpleName());
                }
            } else {
                result.put("metricsAvailable", false);
                result.put("hint", "Metrics require 'micrometer-core' or 'dropwizard-metrics' on classpath.");
            }

            // Session state
            Object state = ReflectionHelper.invokeMethod(session, "getState");
            if (state != null) {
                Object nodes = ReflectionHelper.invokeMethod(state, "getNodes");
                if (nodes instanceof Map) {
                    result.put("sessionNodes", ((Map<?, ?>) nodes).size());
                }
            }

            // Thread name prefix for async operations
            Object context = ReflectionHelper.invokeMethod(session, "getContext");
            if (context != null) {
                Object driverConfig = ReflectionHelper.invokeMethod(context, "getConfig");
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Request configuration from Spring properties
        Map<String, String> reqConfig = new LinkedHashMap<>();
        String[] reqProps = {
                "spring.cassandra.request.consistency",
                "spring.cassandra.request.serial-consistency",
                "spring.cassandra.request.page-size",
                "spring.cassandra.request.timeout",
                "spring.cassandra.request.throttaler.type",
                "spring.cassandra.request.throttaler.max-queue-size",
                "spring.cassandra.request.throttaler.max-requests-per-second",
                "spring.cassandra.request.throttaler.drain-interval"
        };
        for (String p : reqProps) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) reqConfig.put(p.replace("spring.cassandra.request.", ""), val);
        }
        if (!reqConfig.isEmpty()) result.put("requestConfig", reqConfig);

        // Pool config
        Map<String, String> poolConfig = new LinkedHashMap<>();
        String[] poolProps = {
                "spring.cassandra.pool.max-requests-per-connection",
                "spring.cassandra.pool.heartbeat-interval",
                "spring.cassandra.pool.idle-timeout",
                "spring.cassandra.pool.local.core",
                "spring.cassandra.pool.local.max",
                "spring.cassandra.pool.remote.core",
                "spring.cassandra.pool.remote.max"
        };
        for (String p : poolProps) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) poolConfig.put(p.replace("spring.cassandra.pool.", ""), val);
        }
        if (!poolConfig.isEmpty()) result.put("poolConfig", poolConfig);

        return result;
    }

    @DebugTool(description = "Execute a CQL query and return results: table schema, row count, "
            + "or diagnostic info. Supports SELECT, DESCRIBE, and COUNT queries. "
            + "WARNING: executes against the live Cassandra cluster — use read-only queries only.")
    public Map<String, Object> testCassandraQuery(
            @ToolParam(description = "CQL query to execute (e.g., 'SELECT * FROM my_keyspace.my_table LIMIT 10')") String query
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        Object session = getCqlSession();
        if (session == null) {
            result.put("error", "No CqlSession found");
            return result;
        }

        try {
            Object rs = ReflectionHelper.invokeMethod(session, "execute", query);
            if (rs != null) {
                Object columns = ReflectionHelper.invokeMethod(rs, "getColumnDefinitions");
                if (columns != null) {
                    List<String> colNames = new ArrayList<>();
                    Object iterator = ReflectionHelper.invokeMethod(columns, "iterator");
                    java.lang.reflect.Method hasNext = iterator.getClass().getMethod("hasNext");
                    java.lang.reflect.Method next = iterator.getClass().getMethod("next");
                    while ((Boolean) hasNext.invoke(iterator)) {
                        Object col = next.invoke(iterator);
                        Object name = ReflectionHelper.invokeMethod(col, "getName");
                        colNames.add(name != null ? name.toString() : "?");
                    }
                    result.put("columns", colNames);
                }

                // Collect rows
                List<Map<String, Object>> rows = new ArrayList<>();
                Object rowIterator = ReflectionHelper.invokeMethod(rs, "iterator");
                java.lang.reflect.Method hasNext = rowIterator.getClass().getMethod("hasNext");
                java.lang.reflect.Method next = rowIterator.getClass().getMethod("next");
                int count = 0;
                while ((Boolean) hasNext.invoke(rowIterator) && count < 20) {
                    Object row = next.invoke(rowIterator);
                    Map<String, Object> rowData = new LinkedHashMap<>();
                    Object rowColumns = ReflectionHelper.invokeMethod(row, "getColumnDefinitions");
                    if (rowColumns != null) {
                        Object colIt = ReflectionHelper.invokeMethod(rowColumns, "iterator");
                        java.lang.reflect.Method colHasNext = colIt.getClass().getMethod("hasNext");
                        java.lang.reflect.Method colNext = colIt.getClass().getMethod("next");
                        while ((Boolean) colHasNext.invoke(colIt)) {
                            Object col = colNext.invoke(colIt);
                            Object name = ReflectionHelper.invokeMethod(col, "getName");
                            Object type = ReflectionHelper.invokeMethod(col, "getType");
                            if (name != null) {
                                try {
                                    Object val = ReflectionHelper.invokeMethod(row,
                                            "getObject", name.toString());
                                    rowData.put(name.toString(),
                                            val != null ? val.toString() : null);
                                } catch (Exception ignored) {
                                    rowData.put(name.toString(), "[unreadable]");
                                }
                            }
                        }
                    }
                    rows.add(rowData);
                    count++;
                }
                result.put("rows", rows);
                result.put("returnedRows", count);
            }
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }
}
