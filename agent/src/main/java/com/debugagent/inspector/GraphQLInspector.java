package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * GraphQL diagnostic tools.
 * Reports schema overview, recent queries captured from an in-memory ring
 * buffer, and errors from those queries. Schema is read via reflection on
 * graphql.schema.GraphQLSchema; queries are tracked in memory by this
 * inspector (capped at 200 entries).
 */
public class GraphQLInspector implements ApplicationContextAware {

    /** Ring buffer of recent queries; newest at the tail. */
    private static final int MAX_QUERIES = 200;
    private static final LinkedList<Map<String, Object>> recentQueries = new LinkedList<>();
    private static final Object lock = new Object();

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Public hook for instrumentation (e.g. an interceptor around GraphQL invocations)
     * to record a query without coupling to internal storage.
     */
    public static void recordQuery(String operationName, String query, Map<String, Object> variables,
                                   long durationMs, List<Map<String, Object>> errors) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("operationName", operationName);
        entry.put("query", query);
        entry.put("variables", variables);
        entry.put("durationMs", durationMs);
        entry.put("errorCount", errors != null ? errors.size() : 0);
        if (errors != null && !errors.isEmpty()) entry.put("errors", errors);
        synchronized (lock) {
            recentQueries.addLast(entry);
            while (recentQueries.size() > MAX_QUERIES) {
                recentQueries.removeFirst();
            }
        }
    }

    @DebugTool(description = "Show GraphQL schema overview: query, mutation, and subscription root types plus the entity/object types and their field names. Useful for auditing what the API exposes.")
    public Map<String, Object> getGraphqlSchema() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object schema = findGraphQLSchema();
        if (schema == null) {
            result.put("error", "No GraphQLSchema found. Add graphql-java or spring-graphql to the classpath.");
            return result;
        }

        try {
            result.put("queryType", describeType(ReflectionHelper.invokeMethod(schema, "getQueryType")));
            Object mutation = ReflectionHelper.invokeMethod(schema, "getMutationType");
            if (mutation != null) result.put("mutationType", describeType(mutation));
            Object subscription = ReflectionHelper.invokeMethod(schema, "getSubscriptionType");
            if (subscription != null) result.put("subscriptionType", describeType(subscription));

            // Additional types
            Object typeMap = ReflectionHelper.invokeMethod(schema, "getTypeMap");
            if (typeMap instanceof Map<?, ?> map) {
                List<Map<String, Object>> types = new ArrayList<>();
                int shown = 0;
                List<?> keys = new ArrayList<>(map.keySet());
                keys.sort(Comparator.comparing(Object::toString));
                for (Object key : keys) {
                    if (shown++ >= 100) break;
                    Object type = map.get(key);
                    if (type == null) continue;
                    // Skip introspection types (start with __) to reduce noise
                    String name = String.valueOf(key);
                    if (name.startsWith("__")) continue;
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", name);
                    t.put("kind", String.valueOf(ReflectionHelper.invokeMethod(type, "getName")));
                    types.add(t);
                }
                result.put("additionalTypeCount", map.size());
                result.put("additionalTypes", types);
            }
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Recent GraphQL queries captured in memory (ring buffer, max 200 entries): operation name, query string, variables, duration, and error count. Useful for understanding what the agent has been executing.")
    public List<Map<String, Object>> getGraphqlQueries(
            @ToolParam(description = "Max number of queries to return (default 50)") Integer limit
    ) {
        int n = limit != null ? Math.min(limit, MAX_QUERIES) : 50;
        List<Map<String, Object>> snapshot;
        synchronized (lock) {
            int start = Math.max(0, recentQueries.size() - n);
            snapshot = new ArrayList<>(recentQueries.subList(start, recentQueries.size()));
        }
        Collections.reverse(snapshot);
        if (snapshot.isEmpty()) {
            snapshot.add(Map.of("note",
                    "No queries recorded. Instrument the GraphQL execution path (e.g. an instrumentation bean) to call GraphQLInspector.recordQuery(...)."));
        }
        return snapshot;
    }

    @DebugTool(description = "All GraphQL errors from recent queries: message, path, locations, and extensions. Filters out error-free queries. Useful for diagnosing resolver or validation failures.")
    public List<Map<String, Object>> getGraphqlErrors() {
        List<Map<String, Object>> allErrors = new ArrayList<>();
        synchronized (lock) {
            for (Map<String, Object> q : recentQueries) {
                Object errors = q.get("errors");
                if (errors instanceof Collection<?> list) {
                    for (Object err : list) {
                        Map<String, Object> enriched = new LinkedHashMap<>();
                        if (err instanceof Map<?, ?> m) {
                            enriched.putAll((Map) m);
                        } else {
                            enriched.put("error", String.valueOf(err));
                        }
                        enriched.put("operationName", q.get("operationName"));
                        enriched.put("query", q.get("query"));
                        allErrors.add(enriched);
                    }
                }
            }
        }
        if (allErrors.isEmpty()) {
            allErrors.add(Map.of("note", "No errors recorded in recent queries."));
        }
        return allErrors;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private Object findGraphQLSchema() {
        // 1. Try a direct GraphQLSchema bean
        try {
            Class<?> schemaClass = Class.forName("graphql.schema.GraphQLSchema", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(schemaClass);
            if (names.length > 0) return ctx.getBean(names[0]);
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {}

        // 2. Spring GraphQL: GraphQLSource / graphQlSource bean exposes getSchema()
        try {
            Class<?> sourceClass = Class.forName("org.springframework.graphql.execution.GraphQlSource", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(sourceClass);
            if (names.length > 0) {
                Object source = ctx.getBean(names[0]);
                Object schema = ReflectionHelper.invokeMethod(source, "schema");
                if (schema != null) return schema;
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {}

        // 3. graphql.GraphQL bean -> getGraphqlSchema()
        try {
            Class<?> graphQLClass = Class.forName("graphql.GraphQL", false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(graphQLClass);
            if (names.length > 0) {
                Object graphQL = ctx.getBean(names[0]);
                Object schema = ReflectionHelper.invokeMethod(graphQL, "getGraphqlSchema");
                if (schema != null) return schema;
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception ignored) {}

        return null;
    }

    private Map<String, Object> describeType(Object type) {
        if (type == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        Object name = ReflectionHelper.invokeMethod(type, "getName");
        info.put("name", name != null ? name.toString() : type.getClass().getSimpleName());

        Object fieldDefs = ReflectionHelper.invokeMethod(type, "getFieldDefinitions");
        if (fieldDefs instanceof Collection<?> coll) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Object f : coll) {
                Map<String, Object> field = new LinkedHashMap<>();
                Object fname = ReflectionHelper.invokeMethod(f, "getName");
                field.put("name", fname);
                Object ftype = ReflectionHelper.invokeMethod(f, "getType");
                field.put("type", ftype != null ? ftype.toString() : null);
                fields.add(field);
            }
            info.put("fieldCount", fields.size());
            info.put("fields", fields);
        }
        return info;
    }
}
