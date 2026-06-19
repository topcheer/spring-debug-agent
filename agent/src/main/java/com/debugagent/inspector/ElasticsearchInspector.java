package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Elasticsearch diagnostic tools.
 * Uses HTTP calls via java.net.http.HttpClient — no ES Java client dependency.
 * Reads URI from spring.elasticsearch.uris (defaults to http://localhost:9200).
 */
public class ElasticsearchInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private String resolveBaseUri() {
        String uri = null;
        try {
            uri = ctx.getEnvironment().getProperty("spring.elasticsearch.uris");
        } catch (Exception ignored) {}
        if (uri == null || uri.isBlank()) {
            uri = "http://localhost:9200";
        }
        // property may be a comma-separated list — take first
        int comma = uri.indexOf(',');
        if (comma > 0) {
            uri = uri.substring(0, comma).trim();
        }
        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private String httpGet(String path) throws Exception {
        String url = resolveBaseUri() + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + url
                    + (response.body() != null && !response.body().isBlank()
                    ? ": " + response.body() : ""));
        }
        return response.body() == null ? "" : response.body();
    }

    @DebugTool(description = "Get Elasticsearch cluster health: cluster name, status (green/yellow/red), node count, shard info, and pending tasks. Useful for diagnosing cluster capacity or availability issues.")
    public Map<String, Object> getEsClusterHealth() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            String body = httpGet("/_cluster/health");
            Map<String, Object> parsed = JsonMini.parseObject(body);
            result.put("clusterName", parsed.get("cluster_name"));
            result.put("status", parsed.get("status"));
            result.put("timedOut", parsed.get("timed_out"));
            result.put("numberOfNodes", parsed.get("number_of_nodes"));
            result.put("numberOfDataNodes", parsed.get("number_of_data_nodes"));
            result.put("activePrimaryShards", parsed.get("active_primary_shards"));
            result.put("activeShards", parsed.get("active_shards"));
            result.put("relocatingShards", parsed.get("relocating_shards"));
            result.put("initializingShards", parsed.get("initializing_shards"));
            result.put("unassignedShards", parsed.get("unassigned_shards"));
            result.put("pendingTasks", parsed.get("number_of_pending_tasks"));
            result.put("delayedUnassignedShards", parsed.get("number_of_in_flight_fetch"));
            result.put("raw", body);

            if ("red".equals(parsed.get("status"))) {
                result.put("hint", "Cluster status is RED — primary shards are unavailable. " +
                        "Check node availability and storage.");
            } else if ("yellow".equals(parsed.get("status"))) {
                result.put("hint", "Cluster status is YELLOW — replica shards are unassigned. " +
                        "Add nodes or reduce replica count.");
            }
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("configuredUri", resolveBaseUri());
            result.put("hint", "Ensure Elasticsearch is reachable at the configured URI " +
                    "(spring.elasticsearch.uris).");
        }
        return result;
    }

    @DebugTool(description = "List all Elasticsearch indices with document count, store size, health, and shard/replica configuration. Useful for understanding data distribution and detecting oversized or missing indices.")
    public List<Map<String, Object>> getEsIndices() {
        List<Map<String, Object>> indices = new ArrayList<>();

        try {
            // _cat/indices?format=json returns a JSON array
            String body = httpGet("/_cat/indices?format=json");
            List<Map<String, Object>> parsed = JsonMini.parseArray(body);
            for (Map<String, Object> idx : parsed) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("health", idx.get("health"));
                info.put("status", idx.get("status"));
                info.put("index", idx.get("index"));
                info.put("uuid", idx.get("uuid"));
                info.put("pri", idx.get("pri"));
                info.put("rep", idx.get("rep"));
                info.put("docsCount", idx.get("docs.count"));
                info.put("docsDeleted", idx.get("docs.deleted"));
                info.put("storeSize", idx.get("store.size"));
                info.put("priStoreSize", idx.get("pri.store.size"));
                indices.add(info);
            }
            if (indices.isEmpty()) {
                indices.add(Map.of("note", "No indices found on cluster at " + resolveBaseUri()));
            }
        } catch (Exception e) {
            indices.add(Map.of(
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "configuredUri", resolveBaseUri()
            ));
        }
        return indices;
    }

    @DebugTool(description = "Check Elasticsearch slow log settings: returns any configured thresholds for index search and indexing slow logs. Useful for diagnosing slow log visibility.")
    public Map<String, Object> getEsSlowQueries() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Cluster-level settings (may include slow log defaults)
        try {
            String body = httpGet("/_cluster/settings?include_defaults=true&flat_settings=true");
            Map<String, Object> parsed = JsonMini.parseObject(body);

            Map<String, Object> slowSettings = new LinkedHashMap<>();
            extractSlowSettings(parsed, "defaults", slowSettings);
            extractSlowSettings(parsed, "persistent", slowSettings);
            extractSlowSettings(parsed, "transient", slowSettings);

            result.put("configuredSlowLogSettings", slowSettings);
            if (slowSettings.isEmpty()) {
                result.put("note", "No slow-log settings detected. Configure index.search.slowlog.threshold " +
                        "or index.indexing.slowlog.threshold to enable.");
            }
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("configuredUri", resolveBaseUri());
        }

        // Per-index slow log settings
        try {
            String body = httpGet("/_all/_settings/index*slowlog*");
            Map<String, Object> parsed = JsonMini.parseObject(body);
            Map<String, Object> perIndex = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                Object settings = entry.getValue();
                if (settings instanceof Map<?, ?> m) {
                    Object s = m.get("settings");
                    if (s instanceof Map<?, ?> sm && !sm.isEmpty()) {
                        perIndex.put(entry.getKey(), sm);
                    }
                }
            }
            result.put("indexSlowLogSettings", perIndex);
        } catch (Exception ignored) {}

        return result;
    }

    @SuppressWarnings("unchecked")
    private void extractSlowSettings(Map<String, Object> clusterSettings, String section,
                                     Map<String, Object> out) {
        Object value = clusterSettings.get(section);
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.contains("slowlog")) {
                    out.put(section + "." + key, e.getValue());
                }
            }
        }
    }

    // ---- Minimal JSON helpers (no external dependency) ----

    private static final class JsonMini {
        static Map<String, Object> parseObject(String json) {
            Object v = parse(json);
            return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
        }

        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> parseArray(String json) {
            Object v = parse(json);
            if (v instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map) result.add((Map<String, Object>) o);
                }
                return result;
            }
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        static Object parse(String json) {
            json = json.trim();
            if (json.isEmpty()) return new LinkedHashMap<>();
            try {
                return new MiniParser(json).parseValue();
            } catch (Exception e) {
                return new LinkedHashMap<>();
            }
        }
    }

    private static final class MiniParser {
        private final String s;
        private int pos;

        MiniParser(String s) {
            this.s = s;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') { pos += 4; return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // skip {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                pos++; // skip :
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                break;
            }
            return m;
        }

        List<Object> parseArray() {
            List<Object> l = new ArrayList<>();
            pos++; // skip [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                Object v = parseValue();
                l.add(v);
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                break;
            }
            return l;
        }

        String parseString() {
            skipWs();
            if (s.charAt(pos) != '"') {
                // bare token — read until , } ] or ws
                int start = pos;
                while (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                    pos++;
                }
                return s.substring(start, pos);
            }
            pos++; // skip "
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < s.length()) {
                    char next = s.charAt(pos++);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else break;
            }
            String num = s.substring(start, pos);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return num;
            }
        }

        Boolean parseBool() {
            if (s.charAt(pos) == 't') { pos += 4; return true; }
            pos += 5; return false;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
