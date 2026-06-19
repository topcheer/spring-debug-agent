package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.bson.Document;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.*;

/**
 * MongoDB diagnostic tools.
 * Inspects server status, collections, slow queries, and indexes.
 * Conditional on Spring Data MongoDB being on classpath.
 *
 * Implementation note: MongoTemplate's {@code getDb()} and {@code getCollection(name)}
 * return types from mongodb-driver-sync (MongoDatabase / MongoCollection), which is not
 * transitively available. To stay compilable we restrict ourselves to MongoTemplate
 * methods whose return types are available: {@code executeCommand(Document)} returns
 * {@code Document} (bson) and {@code getCollectionNames()} returns {@code Set<String>}.
 */
public class MongoDbInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    /** In-memory slow-query buffer, capped at 200 entries. */
    private final List<Map<String, Object>> slowQueries =
            Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_SLOW_QUERIES = 200;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private MongoTemplate getMongoTemplate() {
        try {
            return ctx.getBean(MongoTemplate.class);
        } catch (Exception e) {
            return null;
        }
    }

    @DebugTool(description = "Get MongoDB server status: version, connections, storage engine, uptime, and host info. Useful for diagnosing connectivity or capacity issues.")
    public Map<String, Object> getMongoInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        MongoTemplate template = getMongoTemplate();
        if (template == null) {
            result.put("error", "MongoTemplate bean not found");
            result.put("hint", "Ensure spring-boot-starter-data-mongodb is on classpath and configured.");
            return result;
        }

        result.put("mongoTemplate", template.getClass().getSimpleName());

        // Database name from environment (avoids calling getDb() which returns MongoDatabase)
        try {
            String dbName = ctx.getEnvironment().getProperty("spring.data.mongodb.database");
            if (dbName == null || dbName.isBlank()) {
                String uri = ctx.getEnvironment().getProperty("spring.data.mongodb.uri");
                dbName = extractDatabaseFromUri(uri);
            }
            result.put("databaseName", dbName != null ? dbName : "(default)");
        } catch (Exception ignored) {}

        // Server status via executeCommand (requires admin)
        try {
            Document serverStatus = template.executeCommand(new Document("serverStatus", 1));
            if (serverStatus != null) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("version", serverStatus.get("version"));
                info.put("host", serverStatus.get("host"));
                info.put("uptimeSeconds", serverStatus.get("uptime"));
                Object storageEngine = serverStatus.get("storageEngine");
                if (storageEngine instanceof Document d) {
                    info.put("storageEngine", d.get("name"));
                } else {
                    info.put("storageEngine", storageEngine);
                }
                info.put("connections", serverStatus.get("connections"));
                info.put("network", serverStatus.get("network"));
                info.put("opcounters", serverStatus.get("opcounters"));
                result.put("serverStatus", info);
            }
        } catch (Exception e) {
            result.put("serverStatusError", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("hint", "serverStatus may require admin privileges; try getMongoCollections instead.");
        }

        // Build info (usually less privileged)
        try {
            Document buildInfo = template.executeCommand(new Document("buildinfo", 1));
            if (buildInfo != null) {
                result.put("buildInfoVersion", buildInfo.get("version"));
                result.put("gitVersion", buildInfo.get("gitVersion"));
                Object bits = buildInfo.get("bits");
                if (bits != null) result.put("bits", bits);
            }
        } catch (Exception ignored) {}

        // Host info (privileged)
        try {
            Document hostInfo = template.executeCommand(new Document("hostInfo", 1));
            if (hostInfo != null) {
                Object os = hostInfo.get("os");
                Map<String, Object> osInfo = new LinkedHashMap<>();
                if (os instanceof Document d) {
                    osInfo.put("name", d.get("name"));
                    osInfo.put("version", d.get("version"));
                }
                Object system = hostInfo.get("system");
                if (system instanceof Document d) {
                    osInfo.put("currentTime", d.get("currentTime"));
                    osInfo.put("hostname", d.get("hostname"));
                    osInfo.put("cpuAddrSize", d.get("cpuAddrSize"));
                    osInfo.put("memSizeMB", d.get("memSizeMB"));
                }
                if (!osInfo.isEmpty()) result.put("hostInfo", osInfo);
            }
        } catch (Exception ignored) {}

        return result;
    }

    @DebugTool(description = "List all MongoDB collections in the default database with document count and average document size. Useful for understanding data distribution.")
    public List<Map<String, Object>> getMongoCollections() {
        List<Map<String, Object>> collections = new ArrayList<>();

        MongoTemplate template = getMongoTemplate();
        if (template == null) {
            collections.add(Map.of("error", "MongoTemplate bean not found"));
            return collections;
        }

        try {
            Set<String> names = template.getCollectionNames();
            for (String name : names) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", name);
                try {
                    Document countResult = template.executeCommand(new Document("count", name));
                    if (countResult != null) {
                        Object n = countResult.get("n");
                        long count = n instanceof Number nn ? nn.longValue() : 0L;
                        info.put("documentCount", count);
                    }

                    Document collStats = template.executeCommand(new Document("collStats", name));
                    if (collStats != null) {
                        Object size = collStats.get("size");
                        Object storageSize = collStats.get("storageSize");
                        info.put("totalSizeBytes", size);
                        info.put("storageSizeBytes", storageSize);
                        info.put("indexCount", collStats.get("nindexes"));
                        info.put("indexSizeBytes", collStats.get("totalIndexSize"));
                        info.put("capped", collStats.get("capped"));
                        Object count = collStats.get("count");
                        if (size instanceof Number sizeN && count instanceof Number countN
                                && countN.longValue() > 0) {
                            info.put("avgDocSizeBytes", sizeN.longValue() / countN.longValue());
                        }
                    }
                } catch (Exception e) {
                    info.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                collections.add(info);
            }

            if (collections.isEmpty()) {
                collections.add(Map.of("note", "No collections found in the configured database."));
            }
        } catch (Exception e) {
            collections.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return collections;
    }

    @DebugTool(description = "Return recent slow MongoDB operations from the in-memory tracker (max 200) or via the $currentOp command. Each entry includes namespace, operation, duration, and client.")
    public List<Map<String, Object>> getMongoSlowQueries() {
        MongoTemplate template = getMongoTemplate();

        if (template != null) {
            try {
                Document currentOp = template.executeCommand(
                        new Document("currentOp", 1)
                                .append("active", true)
                                .append("microsecs_running", new Document("$gt", 100_000L)));
                Object inprog = currentOp.get("inprog");
                if (inprog instanceof List<?> list) {
                    synchronized (slowQueries) {
                        for (Object op : list) {
                            if (op instanceof Document doc) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("opId", doc.get("opid"));
                                entry.put("opType", doc.get("op"));
                                entry.put("ns", doc.get("ns"));
                                entry.put("client", doc.get("client"));
                                Object micros = doc.get("microsecs_running");
                                if (micros instanceof Number n) {
                                    entry.put("durationMicros", n.longValue());
                                    entry.put("durationMs", n.longValue() / 1000.0);
                                }
                                entry.put("planSummary", doc.get("planSummary"));
                                entry.put("capturedAt", new Date());
                                pushSlow(entry);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        synchronized (slowQueries) {
            if (slowQueries.isEmpty()) {
                return List.of(Map.of("note", "No slow queries captured. " +
                        "Slow-query tracking uses $currentOp; enable the database profiler for full history."));
            }
            return new ArrayList<>(slowQueries);
        }
    }

    @DebugTool(description = "List indexes for a MongoDB collection, showing index keys, size, and uniqueness. Useful for diagnosing missing indexes or identifying redundant indexes.")
    public List<Map<String, Object>> getMongoIndexes(
            @ToolParam(description = "Collection name (leave empty to list indexes across all collections)", required = false) String collectionName
    ) {
        List<Map<String, Object>> result = new ArrayList<>();

        MongoTemplate template = getMongoTemplate();
        if (template == null) {
            result.add(Map.of("error", "MongoTemplate bean not found"));
            return result;
        }

        Set<String> targets = new LinkedHashSet<>();
        if (collectionName != null && !collectionName.isBlank()) {
            targets.add(collectionName);
        } else {
            try {
                targets.addAll(template.getCollectionNames());
            } catch (Exception e) {
                result.add(Map.of("error", "Failed to list collections: " + e.getMessage()));
                return result;
            }
        }

        for (String name : targets) {
            try {
                Document cursorDoc = template.executeCommand(new Document("listIndexes", name));
                Object cursor = cursorDoc.get("cursor");
                if (cursor instanceof Document c) {
                    Object firstBatch = c.get("firstBatch");
                    if (firstBatch instanceof List<?> batch) {
                        for (Object idx : batch) {
                            if (!(idx instanceof Document d)) continue;
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("collection", name);
                            info.put("name", d.get("name"));
                            info.put("keys", d.get("key"));
                            info.put("unique", d.get("unique"));
                            info.put("sparse", d.get("sparse"));
                            info.put("version", d.get("v"));
                            result.add(info);
                        }
                    }
                }
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("collection", name);
                err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                result.add(err);
            }
        }

        if (result.isEmpty()) {
            result.add(Map.of("note", "No indexes found for the specified collection(s)."));
        }
        return result;
    }

    private void pushSlow(Map<String, Object> entry) {
        slowQueries.add(entry);
        while (slowQueries.size() > MAX_SLOW_QUERIES) {
            slowQueries.remove(0);
        }
    }

    private static String extractDatabaseFromUri(String uri) {
        if (uri == null || uri.isBlank()) return null;
        int schemeEnd = uri.indexOf("://");
        int queryStart = uri.indexOf('?');
        String working = queryStart > 0 ? uri.substring(0, queryStart) : uri;
        int slash = working.lastIndexOf('/');
        if (slash < 0) return null;
        String db = working.substring(slash + 1).trim();
        return db.isEmpty() ? null : db;
    }
}
