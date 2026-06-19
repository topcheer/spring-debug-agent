package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Redis diagnostic tools.
 * Inspects Redis server info, slowlog, and key details.
 * Conditional on Spring Data Redis being on classpath.
 */
public class RedisInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @SuppressWarnings("unchecked")
    private Object getConnection() throws Exception {
        Class<?> factoryClass = Class.forName("org.springframework.data.redis.connection.RedisConnectionFactory");
        Object factory = ctx.getBean(factoryClass);
        Method connect = factoryClass.getMethod("getConnection");
        return connect.invoke(factory);
    }

    @DebugTool(description = "Get Redis server info: version, memory usage, connected clients, keyspace stats, replication status. Useful for diagnosing Redis performance or connectivity issues.")
    public Map<String, Object> getRedisInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Object conn = getConnection();

            // Get server info
            Method info = conn.getClass().getMethod("info");
            Object infoProps = info.invoke(conn);
            Map<String, String> serverInfo = new LinkedHashMap<>();

            if (infoProps instanceof java.util.Properties props) {
                // Group by section
                Map<String, Object> grouped = new LinkedHashMap<>();
                Map<String, String> server = new LinkedHashMap<>();
                Map<String, String> memory = new LinkedHashMap<>();
                Map<String, String> clients = new LinkedHashMap<>();
                Map<String, String> stats = new LinkedHashMap<>();
                Map<String, String> keyspace = new LinkedHashMap<>();

                for (String key : props.stringPropertyNames()) {
                    String val = props.getProperty(key);
                    if (key.startsWith("redis_") || key.startsWith("os") || key.startsWith("process")
                            || key.startsWith("run") || key.startsWith("config") || key.startsWith("tcp")
                            || key.startsWith("uptime")) {
                        server.put(key, val);
                    } else if (key.startsWith("used_") || key.startsWith("mem_") || key.startsWith("maxmemory")
                            || key.startsWith("fragmentation")) {
                        memory.put(key, val);
                    } else if (key.startsWith("connected_") || key.startsWith("blocked_") || key.startsWith("client")) {
                        clients.put(key, val);
                    } else if (key.startsWith("total_") || key.startsWith("expired_") || key.startsWith("evicted_")
                            || key.startsWith("keyspace_") || key.startsWith("rejected")) {
                        stats.put(key, val);
                    } else if (key.startsWith("db")) {
                        keyspace.put(key, val);
                    } else {
                        server.put(key, val);
                    }
                }

                grouped.put("server", server);
                grouped.put("memory", memory);
                grouped.put("clients", clients);
                grouped.put("stats", stats);
                grouped.put("keyspace", keyspace);
                result.putAll(grouped);

                // Key metrics
                result.put("redisVersion", server.get("redis_version"));
                result.put("uptimeDays", server.get("uptime_in_days"));
                result.put("usedMemoryHuman", memory.get("used_memory_human"));
                result.put("maxMemoryHuman", memory.get("maxmemory_human"));
                result.put("connectedClients", clients.get("connected_clients"));
                result.put("totalCommandsProcessed", stats.get("total_commands_processed"));
            }

            // DB size
            try {
                Method dbSize = conn.getClass().getMethod("dbSize");
                Long size = (Long) dbSize.invoke(conn);
                result.put("dbSize", size);
            } catch (Exception ignored) {}

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("hint", "Ensure Redis is running and spring-data-redis is on classpath.");
        }

        return result;
    }

    @DebugTool(description = "Get Redis slowlog: queries that took longer than the configured threshold. Shows timestamp, duration (microseconds), command, and client. Useful for finding slow Redis operations.")
    public List<Map<String, Object>> getRedisSlowlog(
            @ToolParam(description = "Number of entries to retrieve (default 10)") Integer count
    ) {
        List<Map<String, Object>> slowlog = new ArrayList<>();
        int n = count != null ? Math.min(count, 50) : 10;

        try {
            Object conn = getConnection();
            // slowLogGet
            Method slowLogGet;
            try {
                slowLogGet = conn.getClass().getMethod("slowLogGet", long.class);
            } catch (NoSuchMethodException e) {
                slowLogGet = conn.getClass().getMethod("slowLogGet");
            }

            Object entries;
            try {
                entries = slowLogGet.invoke(conn, (long) n);
            } catch (Exception e) {
                entries = slowLogGet.invoke(conn);
            }

            if (entries instanceof List<?> list) {
                for (Object entry : list) {
                    Map<String, Object> slowEntry = new LinkedHashMap<>();
                    // SlowLog entry structure: id, timeStamp (epoch seconds), executionTime (micros), args, clientHost, clientName
                    if (entry instanceof List<?> parts && parts.size() >= 4) {
                        slowEntry.put("id", parts.get(0));
                        slowEntry.put("timestamp", java.time.Instant.ofEpochSecond(
                                ((Number) parts.get(1)).longValue()).toString());
                        slowEntry.put("durationMicros", parts.get(2));
                        slowEntry.put("durationMs", ((Number) parts.get(2)).longValue() / 1000.0);
                        Object args = parts.get(3);
                        if (args instanceof List<?> argList) {
                            List<String> argStrings = new ArrayList<>();
                            for (Object a : argList) argStrings.add(a.toString());
                            slowEntry.put("command", String.join(" ", argStrings));
                        }
                        if (parts.size() > 4) slowEntry.put("clientHost", parts.get(4));
                    } else {
                        slowEntry.put("raw", entry.toString());
                    }
                    slowlog.add(slowEntry);
                }
            }

        } catch (Exception e) {
            slowlog.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return slowlog;
    }

    @DebugTool(description = "Inspect a specific Redis key: type, TTL, size in bytes, and a preview of its value. Useful for debugging cache entries or verifying key expiration.")
    public Map<String, Object> getRedisKeyInfo(
            @ToolParam(description = "Redis key name", required = true) String key
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Object conn = getConnection();
            result.put("key", key);

            // Type
            Method typeMethod = conn.getClass().getMethod("type", byte[].class);
            Object dataType = typeMethod.invoke(conn, key.getBytes());
            result.put("type", dataType != null ? dataType.toString() : "none");

            // TTL
            try {
                Method ttlMethod = conn.getClass().getMethod("ttl", byte[].class);
                Object ttl = ttlMethod.invoke(conn, key.getBytes());
                if (ttl instanceof Long t) {
                    result.put("ttlSeconds", t);
                    result.put("ttlHuman", t < 0 ? "no expiry" : (t + "s"));
                }
            } catch (Exception ignored) {}

            // Get value preview based on type
            String type = result.get("type").toString();
            try {
                if (type.contains("STRING")) {
                    Method getMethod = conn.getClass().getMethod("get", byte[].class);
                    Object value = getMethod.invoke(conn, key.getBytes());
                    if (value instanceof byte[] bytes) {
                        String str = new String(bytes);
                        result.put("valueLength", str.length());
                        result.put("valuePreview", str.length() > 500
                                ? str.substring(0, 500) + "... (truncated)" : str);
                    }
                } else if (type.contains("LIST")) {
                    Method lLen = conn.getClass().getMethod("lLen", byte[].class);
                    Object size = lLen.invoke(conn, key.getBytes());
                    result.put("listLength", size);

                    Method lRange = conn.getClass().getMethod("lRange", byte[].class, long.class, long.class);
                    Object range = lRange.invoke(conn, key.getBytes(), 0L, 9L);
                    if (range instanceof List<?> list) {
                        List<String> items = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof byte[] b) items.add(new String(b));
                            else items.add(String.valueOf(item));
                        }
                        result.put("first10Items", items);
                    }
                } else if (type.contains("HASH")) {
                    Method hGetAll = conn.getClass().getMethod("hGetAll", byte[].class);
                    Object hash = hGetAll.invoke(conn, key.getBytes());
                    if (hash instanceof Map<?, ?> map) {
                        Map<String, String> simplified = new LinkedHashMap<>();
                        int i = 0;
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (i++ >= 20) break;
                            String k = entry.getKey() instanceof byte[] b ? new String(b) : String.valueOf(entry.getKey());
                            String v = entry.getValue() instanceof byte[] b ? new String(b) : String.valueOf(entry.getValue());
                            simplified.put(k, v);
                        }
                        result.put("hashFieldCount", map.size());
                        result.put("first20Fields", simplified);
                    }
                } else if (type.contains("SET")) {
                    Method sCard = conn.getClass().getMethod("sCard", byte[].class);
                    result.put("setSize", sCard.invoke(conn, key.getBytes()));
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }
}
