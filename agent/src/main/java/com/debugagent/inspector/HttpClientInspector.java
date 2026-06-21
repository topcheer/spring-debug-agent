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

    @DebugTool(description = "Get per-route (per-host) HTTP connection pool breakdown for Apache HttpClient. "
            + "Shows leased, available, and max connections grouped by target host.")
    public List<Map<String, Object>> getHttpConnectionDetail() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            List<Object> pools = ReflectionHelper.getBeansOfType(ctx,
                    "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager");
            if (pools.isEmpty()) {
                pools = ReflectionHelper.getBeansOfType(ctx,
                        "org.apache.http.impl.conn.PoolingHttpClientConnectionManager");
            }

            for (Object pool : pools) {
                Map<String, Object> poolInfo = new LinkedHashMap<>();
                poolInfo.put("type", pool.getClass().getSimpleName());

                // Try to get route stats
                try {
                    Object connPool = ReflectionHelper.invokeMethod(pool, "getTotalStats");
                    if (connPool != null) {
                        poolInfo.put("totalLeased", ReflectionHelper.invokeMethod(connPool, "getLeased"));
                        poolInfo.put("totalAvailable", ReflectionHelper.invokeMethod(connPool, "getAvailable"));
                        poolInfo.put("totalPending", ReflectionHelper.invokeMethod(connPool, "getPending"));
                        poolInfo.put("totalMax", ReflectionHelper.invokeMethod(connPool, "getMax"));
                    }

                    // Per-route config
                    Object maxPerRoute = ReflectionHelper.invokeMethod(pool, "getDefaultMaxPerRoute");
                    if (maxPerRoute != null) poolInfo.put("defaultMaxPerRoute", maxPerRoute);

                    Object maxTotal = ReflectionHelper.invokeMethod(pool, "getMaxTotal");
                    if (maxTotal != null) poolInfo.put("maxTotal", maxTotal);

                    // Try to enumerate route stats
                    Object routes = ReflectionHelper.invokeMethod(pool, "getRoutes");
                    if (routes instanceof Set<?> routeSet) {
                        List<Map<String, Object>> routeList = new ArrayList<>();
                        for (Object route : routeSet) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("route", route.toString());
                            Object stats = ReflectionHelper.invokeMethod(pool, "getStats", route);
                            if (stats != null) {
                                r.put("leased", ReflectionHelper.invokeMethod(stats, "getLeased"));
                                r.put("available", ReflectionHelper.invokeMethod(stats, "getAvailable"));
                                r.put("max", ReflectionHelper.invokeMethod(stats, "getMax"));
                            }
                            routeList.add(r);
                        }
                        if (!routeList.isEmpty()) poolInfo.put("routes", routeList);
                    }
                } catch (Exception ignored) {}

                if (poolInfo.size() > 1) results.add(poolInfo);
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No Apache HttpClient connection pools found"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get connection detail: " + e.getMessage()));
        }

        return results;
    }
}
