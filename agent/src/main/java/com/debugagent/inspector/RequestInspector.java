package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * HTTP request tracing inspector.
 * Reads from the RequestCaptureFilter's in-memory ring buffer
 * to show recent/slow HTTP requests.
 * <p>
 * Only available when Spring Web MVC is on the classpath
 * (because RequestCaptureFilter depends on jakarta.servlet).
 */
@Component
public class RequestInspector {

    @DebugTool(description = "Get recent HTTP requests from in-memory ring buffer. Shows method, path, status code, duration, client IP, and exception info.")
    public Map<String, Object> getRecentRequests(
            @ToolParam(description = "Filter by path prefix (e.g., '/api/'). Leave empty for all.") String pathFilter,
            @ToolParam(description = "Filter by HTTP status code range (e.g., '5xx', '4xx', '2xx'). Leave empty for all.") String statusFilter,
            @ToolParam(description = "Maximum number of requests to return (default 30)") Integer limit
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int max = limit != null && limit > 0 ? limit : 30;

        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> req : RequestCaptureFilter.recentRequests) {
            // Path filter
            String path = (String) req.get("path");
            if (pathFilter != null && !pathFilter.isBlank()
                    && !path.toLowerCase().contains(pathFilter.toLowerCase())) {
                continue;
            }

            // Status filter
            if (statusFilter != null && !statusFilter.isBlank()) {
                int status = (Integer) req.getOrDefault("status", 0);
                String prefix = statusFilter.toLowerCase().replaceAll("xx", "");
                if (!String.valueOf(status).startsWith(prefix)) {
                    continue;
                }
            }

            requests.add(req);
            if (requests.size() >= max) break;
        }

        result.put("requests", requests);
        result.put("returnedCount", requests.size());
        result.put("totalCaptured", RequestCaptureFilter.recentRequests.size());

        // Summary stats
        if (!requests.isEmpty()) {
            result.put("summary", computeSummary(requests));
        }
        return result;
    }

    @DebugTool(description = "Get the slowest HTTP requests. Sorted by duration descending. Useful for finding performance bottlenecks in API endpoints.")
    public Map<String, Object> getSlowRequests(
            @ToolParam(description = "Filter by path prefix. Leave empty for all.") String pathFilter,
            @ToolParam(description = "Maximum number of requests to return (default 20)") Integer limit
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int max = limit != null && limit > 0 ? limit : 20;

        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> req : RequestCaptureFilter.recentRequests) {
            String path = (String) req.get("path");
            if (pathFilter != null && !pathFilter.isBlank()
                    && !path.toLowerCase().contains(pathFilter.toLowerCase())) {
                continue;
            }
            all.add(new LinkedHashMap<>(req));
        }

        // Sort by duration descending
        all.sort((a, b) -> {
            Long da = ((Number) a.getOrDefault("durationMs", 0)).longValue();
            Long db = ((Number) b.getOrDefault("durationMs", 0)).longValue();
            return db.compareTo(da);
        });

        List<Map<String, Object>> top = all.size() > max ? all.subList(0, max) : all;

        result.put("slowestRequests", top);
        result.put("returnedCount", top.size());

        // Stats
        if (!top.isEmpty()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            Long maxDur = ((Number) top.get(0).get("durationMs")).longValue();
            Long minDur = ((Number) top.get(top.size() - 1).get("durationMs")).longValue();
            double avgDur = top.stream()
                    .mapToDouble(r -> ((Number) r.getOrDefault("durationMs", 0)).doubleValue())
                    .average().orElse(0);
            stats.put("maxDurationMs", maxDur);
            stats.put("minDurationMs", minDur);
            stats.put("avgDurationMs", String.format("%.1f", avgDur));
            result.put("stats", stats);
        }

        return result;
    }

    @DebugTool(description = "Get HTTP request statistics summary: total requests, status code distribution, average/p95/p99 latency, and error rate.")
    public Map<String, Object> getRequestStats(
            @ToolParam(description = "Filter by path prefix. Leave empty for all.") String pathFilter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Long> durations = new ArrayList<>();
        Map<String, Integer> statusDistribution = new TreeMap<>();
        Map<String, Integer> methodDistribution = new TreeMap<>();
        int errorCount = 0;
        int slowCount = 0;
        Set<String> uniquePaths = new HashSet<>();

        for (Map<String, Object> req : RequestCaptureFilter.recentRequests) {
            String path = (String) req.get("path");
            if (pathFilter != null && !pathFilter.isBlank()
                    && !path.toLowerCase().contains(pathFilter.toLowerCase())) {
                continue;
            }

            durations.add(((Number) req.getOrDefault("durationMs", 0)).longValue());
            uniquePaths.add(path);

            int status = (Integer) req.getOrDefault("status", 0);
            String statusKey = (status / 100) + "xx";
            statusDistribution.merge(statusKey, 1, Integer::sum);
            if (status >= 400) errorCount++;

            String method = (String) req.getOrDefault("method", "UNKNOWN");
            methodDistribution.merge(method, 1, Integer::sum);

            if (req.containsKey("slow")) slowCount++;
        }

        if (durations.isEmpty()) {
            result.put("message", "No requests captured yet.");
            result.put("note", "Requests are captured by RequestCaptureFilter (max 500 in memory).");
            return result;
        }

        Collections.sort(durations);

        result.put("totalRequests", durations.size());
        result.put("uniquePaths", uniquePaths.size());
        result.put("statusDistribution", statusDistribution);
        result.put("methodDistribution", methodDistribution);
        result.put("errorCount", errorCount);
        result.put("errorRate", String.format("%.1f%%", errorCount * 100.0 / durations.size()));
        result.put("slowRequestCount", slowCount);

        // Latency stats
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("minMs", durations.get(0));
        latency.put("maxMs", durations.get(durations.size() - 1));
        latency.put("avgMs", String.format("%.1f", durations.stream().mapToLong(l -> l).average().orElse(0)));
        latency.put("p50Ms", percentile(durations, 50));
        latency.put("p95Ms", percentile(durations, 95));
        latency.put("p99Ms", percentile(durations, 99));
        result.put("latency", latency);

        return result;
    }

    // ==================== Helpers ====================

    private Map<String, Object> computeSummary(List<Map<String, Object>> requests) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Integer> statusDist = new TreeMap<>();
        for (Map<String, Object> r : requests) {
            int status = (Integer) r.getOrDefault("status", 0);
            String key = (status / 100) + "xx";
            statusDist.merge(key, 1, Integer::sum);
        }
        summary.put("statusDistribution", statusDist);
        return summary;
    }

    private long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
