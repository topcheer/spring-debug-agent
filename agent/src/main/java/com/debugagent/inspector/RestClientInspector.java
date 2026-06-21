package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Outbound HTTP call tracking.
 * <p>
 * Intercepts RestTemplate / RestClient calls to record URL, method,
 * status code, duration, and exceptions. Uses reflection to register
 * an interceptor on existing RestTemplate beans — no hard dependency.
 * <p>
 * Conditional on {@code org.springframework.web.client.RestTemplate}
 * being on the classpath.
 */
public class RestClientInspector implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(RestClientInspector.class);
    private static final int MAX_ENTRIES = 500;

    private ApplicationContext ctx;
    private final Deque<Map<String, Object>> callHistory = new ConcurrentLinkedDeque<>();
    private volatile boolean interceptorRegistered = false;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Called by the auto-configuration after creation to register interceptors
     * on any RestTemplate beans found in the context.
     */
    public void tryRegisterInterceptors() {
        if (interceptorRegistered) return;
        synchronized (this) {
            if (interceptorRegistered) return;

            try {
                // Find RestTemplate beans
                Map<String, ?> restTemplates = ctx.getBeansOfType(
                        org.springframework.web.client.RestTemplate.class);

                for (Map.Entry<String, ?> entry : restTemplates.entrySet()) {
                    registerInterceptorOnRestTemplate(entry.getKey(), entry.getValue());
                }

                // Also try RestClient (Spring 6.1+)
                try {
                    Class<?> restClientClass = Class.forName("org.springframework.web.client.RestClient", false, ctx.getClassLoader());
                    Map<String, ?> restClients = ctx.getBeansOfType(restClientClass);
                    // RestClient interceptors are harder to inject — skip for now
                    // but note their existence
                    if (!restClients.isEmpty()) {
                        log.debug("Found {} RestClient beans (not yet interceptable)", restClients.size());
                    }
                } catch (ClassNotFoundException ignored) {
                    // RestClient not available
                }

                interceptorRegistered = !restTemplates.isEmpty();
                if (interceptorRegistered) {
                    log.info("RestClientInspector: interceptors registered on {} RestTemplate(s)",
                            restTemplates.size());
                }
            } catch (Exception e) {
                log.debug("Failed to register RestTemplate interceptors: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void registerInterceptorOnRestTemplate(String beanName, Object restTemplate) {
        try {
            // Get current interceptors
            List<Object> existing = (List<Object>) ReflectionHelper.invokeMethod(
                    restTemplate, "getInterceptors");
            if (existing == null) existing = new ArrayList<>();

            // Check if we already registered
            for (Object interceptor : existing) {
                if (interceptor.getClass().getName().contains("OutboundCallTracker")) {
                    return; // Already registered
                }
            }

            // Create our tracking interceptor
            org.springframework.http.client.ClientHttpRequestInterceptor tracker =
                    (request, body, execution) -> {
                        long startTime = System.currentTimeMillis();
                        String url = request.getURI().toString();
                        String method = request.getMethod().name();

                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("timestamp", Instant.now().toString());
                        entry.put("url", url);
                        entry.put("method", method);
                        entry.put("restTemplate", beanName);

                        try {
                            org.springframework.http.client.ClientHttpResponse response =
                                    execution.execute(request, body);
                            long duration = System.currentTimeMillis() - startTime;

                            entry.put("status", response.getStatusCode().value());
                            entry.put("durationMs", duration);
                            entry.put("host", request.getURI().getHost());
                            entry.put("result", response.getStatusCode().isError() ? "ERROR" : "SUCCESS");

                            addEntry(entry);
                            return response;
                        } catch (Exception e) {
                            long duration = System.currentTimeMillis() - startTime;
                            entry.put("status", -1);
                            entry.put("durationMs", duration);
                            entry.put("host", request.getURI().getHost());
                            entry.put("result", "EXCEPTION");
                            entry.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage());
                            addEntry(entry);
                            throw e;
                        }
                    };

            existing.add(tracker);
            // Use plain reflection to call setInterceptors(List)
            java.lang.reflect.Method setMethod = restTemplate.getClass().getMethod("setInterceptors", java.util.List.class);
            setMethod.invoke(restTemplate, existing);
        } catch (Exception e) {
            log.debug("Failed to register interceptor on RestTemplate '{}': {}", beanName, e.getMessage());
        }
    }

    private void addEntry(Map<String, Object> entry) {
        if (callHistory.size() >= MAX_ENTRIES) {
            callHistory.pollFirst();
        }
        callHistory.addLast(entry);
    }

    // ==================== Tools ====================

    @DebugTool(description = "Get recent outbound HTTP calls made by the application (via RestTemplate). Shows URL, method, status code, duration, and target host. Requires RestTemplate to be in use.")
    public List<Map<String, Object>> getOutboundRequests(
            @ToolParam(description = "Maximum entries (default 20, max 100)") Integer limit
    ) {
        tryRegisterInterceptors();
        int max = limit != null ? Math.min(limit, 100) : 20;
        List<Map<String, Object>> snapshot = new ArrayList<>(callHistory);
        Collections.reverse(snapshot);
        if (snapshot.size() > max) snapshot = snapshot.subList(0, max);
        return snapshot;
    }

    @DebugTool(description = "Get the slowest outbound HTTP calls sorted by duration. Useful for finding slow third-party API dependencies or timeout-prone services.")
    public List<Map<String, Object>> getSlowOutboundRequests(
            @ToolParam(description = "Maximum entries (default 10)") Integer limit,
            @ToolParam(description = "Minimum duration in ms (default 500)") Integer minDurationMs
    ) {
        tryRegisterInterceptors();
        int max = limit != null ? Math.min(limit, 50) : 10;
        long minMs = minDurationMs != null ? minDurationMs : 500;

        List<Map<String, Object>> slow = new ArrayList<>();
        for (Map<String, Object> entry : callHistory) {
            long dur = (Long) entry.getOrDefault("durationMs", 0L);
            if (dur >= minMs) slow.add(entry);
        }

        slow.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("durationMs", 0L),
                (Long) a.getOrDefault("durationMs", 0L)));

        if (slow.size() > max) slow = slow.subList(0, max);
        return slow;
    }

    @DebugTool(description = "Get outbound HTTP call statistics: total calls, success rate, average/P95/P99 latency, grouped by target host. Useful for assessing third-party API health.")
    public Map<String, Object> getOutboundRequestStats() {
        tryRegisterInterceptors();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> snapshot = new ArrayList<>(callHistory);
        result.put("totalCalls", snapshot.size());

        if (snapshot.isEmpty()) {
            result.put("note", "No outbound HTTP calls captured yet. Ensure RestTemplate is being used.");
            return result;
        }

        int successCount = 0;
        int errorCount = 0;
        int exceptionCount = 0;
        List<Long> durations = new ArrayList<>();
        Map<String, Integer> byHost = new HashMap<>();
        Map<String, Integer> byStatus = new TreeMap<>();

        for (Map<String, Object> e : snapshot) {
            String res = (String) e.getOrDefault("result", "UNKNOWN");
            if ("SUCCESS".equals(res)) successCount++;
            else if ("ERROR".equals(res)) errorCount++;
            else if ("EXCEPTION".equals(res)) exceptionCount++;

            durations.add((Long) e.getOrDefault("durationMs", 0L));

            byHost.merge((String) e.getOrDefault("host", "unknown"), 1, Integer::sum);

            int status = (Integer) e.getOrDefault("status", -1);
            byStatus.merge(String.valueOf(status), 1, Integer::sum);
        }

        Collections.sort(durations);
        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("exceptionCount", exceptionCount);
        result.put("successRate", String.format("%.1f%%", successCount * 100.0 / snapshot.size()));
        result.put("avgDurationMs", durations.stream().mapToLong(l -> l).average().orElse(0));
        result.put("p50Ms", percentile(durations, 50));
        result.put("p95Ms", percentile(durations, 95));
        result.put("p99Ms", percentile(durations, 99));
        result.put("maxMs", durations.get(durations.size() - 1));
        result.put("byHost", byHost);
        result.put("byStatus", byStatus);

        return result;
    }

    @DebugTool(description = "Get outbound HTTP call errors: exceptions, timeouts, and 4xx/5xx responses. Shows which external services are failing and why.")
    public List<Map<String, Object>> getOutboundErrors(
            @ToolParam(description = "Maximum entries (default 50)") Integer limit
    ) {
        tryRegisterInterceptors();
        int max = limit != null ? Math.min(limit, 100) : 50;

        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> entry : callHistory) {
            String result = (String) entry.getOrDefault("result", "");
            if ("ERROR".equals(result) || "EXCEPTION".equals(result)) {
                errors.add(entry);
            }
        }

        Collections.reverse(errors);
        if (errors.size() > max) errors = errors.subList(0, max);
        return errors;
    }

    private long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
