package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * HTTP client connection pool inspection tool.
 * Uses reflection on Apache HttpClient pool manager and Reactor Netty connection provider.
 */
public class HttpClientInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get HTTP client connection pool statistics for RestTemplate (Apache HttpClient) and WebClient (Reactor Netty). Shows leased, available, and pending connections.")
    public List<Map<String, Object>> getHttpClientPoolStats() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // Try Apache HttpClient PoolingHttpClientConnectionManager
            results.addAll(inspectApacheHttpClient());
            // Try Reactor Netty connection provider
            results.addAll(inspectReactorNetty());

            if (results.isEmpty()) {
                results.add(Map.of("info", "No HTTP client connection pools found"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get HTTP client stats: " + e.getMessage()));
        }

        return results;
    }

    private List<Map<String, Object>> inspectApacheHttpClient() {
        List<Map<String, Object>> results = new ArrayList<>();

        // Look for PoolingHttpClientConnectionManager beans
        List<Object> pools = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager");
        if (pools.isEmpty()) {
            pools = ReflectionHelper.getBeansOfType(ctx,
                    "org.apache.http.impl.conn.PoolingHttpClientConnectionManager");
        }

        for (Object pool : pools) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", "Apache HttpClient");

            Object totalStats = ReflectionHelper.invokeMethod(pool, "getTotalStats");
            if (totalStats != null) {
                info.put("leased", ReflectionHelper.invokeMethod(totalStats, "getLeased"));
                info.put("available", ReflectionHelper.invokeMethod(totalStats, "getAvailable"));
                info.put("pending", ReflectionHelper.invokeMethod(totalStats, "getPending"));
                info.put("max", ReflectionHelper.invokeMethod(totalStats, "getMax"));
            }

            Object maxTotal = ReflectionHelper.invokeMethod(pool, "getMaxTotal");
            if (maxTotal != null) info.put("maxTotal", maxTotal);

            if (info.size() > 1) results.add(info);
        }

        return results;
    }

    private List<Map<String, Object>> inspectReactorNetty() {
        List<Map<String, Object>> results = new ArrayList<>();

        // Look for ConnectionProvider beans
        List<Object> providers = ReflectionHelper.getBeansOfType(ctx,
                "reactor.netty.resources.ConnectionProvider");
        for (Object provider : providers) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", "Reactor Netty");
            info.put("name", ReflectionHelper.invokeMethod(provider, "name"));

            try {
                // ConnectionProvider has maxConnections and pendingAcquireMaxCount
                Object maxConnections = ReflectionHelper.invokeMethod(provider, "maxConnections");
                if (maxConnections != null) info.put("maxConnections", maxConnections);
            } catch (Exception ignored) {}

            results.add(info);
        }

        return results;
    }
}
