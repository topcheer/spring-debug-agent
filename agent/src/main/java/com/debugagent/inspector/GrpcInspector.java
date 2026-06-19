package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * gRPC diagnostic tools.
 * All gRPC classes are accessed via reflection (no compile-time dependency on grpc-java).
 * Inspects channels, services, and call statistics.
 */
public class GrpcInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    /** Per-method call statistics maintained in memory by interceptors. */
    private final Map<String, Map<String, Object>> callStats =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List registered gRPC channels/stubs via reflection on io.grpc.ManagedChannel beans. Reports target authority, state, and shutdown status. Useful for diagnosing connectivity issues.")
    public List<Map<String, Object>> getGrpcChannels() {
        List<Map<String, Object>> channels = new ArrayList<>();

        if (!ReflectionHelper.isClassAvailable("io.grpc.ManagedChannel")) {
            channels.add(Map.of(
                    "note", "io.grpc.ManagedChannel not on classpath — gRPC client not present.",
                    "hint", "Add grpc-netty-shaded / grpc-protobuf / grpc-stub to use gRPC."
            ));
            return channels;
        }

        try {
            List<Object> beans = ReflectionHelper.getBeansOfType(ctx, "io.grpc.ManagedChannel");
            if (beans.isEmpty()) {
                // Some apps register channels under ForwardingClientCall etc. — also scan by bean name.
                for (String name : ctx.getBeanDefinitionNames()) {
                    try {
                        Object bean = ctx.getBean(name);
                        if (ReflectionHelper.isClassAvailable("io.grpc.ManagedChannel")
                                && bean.getClass().getName().startsWith("io.grpc")) {
                            Map<String, Object> info = describeChannel(name, bean);
                            if (!info.isEmpty()) {
                                info.putIfAbsent("beanName", name);
                                channels.add(info);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                for (int i = 0; i < beans.size(); i++) {
                    Object bean = beans.get(i);
                    Map<String, Object> info = describeChannel("channel#" + i, bean);
                    if (!info.isEmpty()) channels.add(info);
                }
            }
        } catch (Exception e) {
            channels.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        if (channels.isEmpty()) {
            channels.add(Map.of(
                    "note", "No gRPC ManagedChannel beans found.",
                    "hint", "Channels are usually created by GrpcChannelFactory or @GrpcClient annotations."
            ));
        }
        return channels;
    }

    private Map<String, Object> describeChannel(String label, Object channel) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("label", label);
        info.put("type", channel.getClass().getSimpleName());

        Object authority = ReflectionHelper.invokeMethod(channel, "authority");
        if (authority != null) info.put("authority", authority);

        Object state = ReflectionHelper.invokeMethod(channel, "getState");
        if (state != null) info.put("state", state);

        Object isShutdown = ReflectionHelper.invokeMethod(channel, "isShutdown");
        if (isShutdown != null) info.put("isShutdown", isShutdown);

        Object isTerminated = ReflectionHelper.invokeMethod(channel, "isTerminated");
        if (isTerminated != null) info.put("isTerminated", isTerminated);

        return info;
    }

    @DebugTool(description = "List registered gRPC service definitions via reflection on grpc.server beans and bindable services. Reports service name and method count. Useful for auditing which gRPC services are exposed.")
    public List<Map<String, Object>> getGrpcServices() {
        List<Map<String, Object>> services = new ArrayList<>();

        boolean grpcAvailable = ReflectionHelper.isClassAvailable("io.grpc.Server")
                || ReflectionHelper.isClassAvailable("io.grpc.BindableService");

        if (!grpcAvailable) {
            services.add(Map.of(
                    "note", "io.grpc.Server/BindableService not on classpath — gRPC server not present.",
                    "hint", "Add grpc-server-spring-boot-starter or netty-shaded to expose gRPC services."
            ));
            return services;
        }

        // BindableService beans (service implementations)
        try {
            List<Object> bindables = ReflectionHelper.getBeansOfType(ctx, "io.grpc.BindableService");
            for (Object b : bindables) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("type", "bindable-service");
                info.put("beanType", b.getClass().getSimpleName());

                Object descriptor = ReflectionHelper.invokeMethod(b, "bindService");
                if (descriptor != null) {
                    Object service = ReflectionHelper.invokeMethod(descriptor, "getServiceDescriptor");
                    if (service != null) {
                        Object name = ReflectionHelper.invokeMethod(service, "getName");
                        info.put("serviceName", name);
                        Object methods = ReflectionHelper.invokeMethod(service, "getMethods");
                        if (methods instanceof List<?> list) {
                            List<String> methodNames = new ArrayList<>();
                            for (Object m : list) {
                                Object full = ReflectionHelper.invokeMethod(m, "getFullMethodName");
                                methodNames.add(full != null ? full.toString() : String.valueOf(m));
                            }
                            info.put("methodCount", list.size());
                            info.put("methods", methodNames);
                        }
                    }
                }
                services.add(info);
            }
        } catch (Exception e) {
            services.add(Map.of("error", "BindableService scan failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        // Server beans (running gRPC servers)
        try {
            List<Object> servers = ReflectionHelper.getBeansOfType(ctx, "io.grpc.Server");
            for (Object server : servers) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("type", "grpc-server");
                info.put("beanType", server.getClass().getSimpleName());
                Object isShutdown = ReflectionHelper.invokeMethod(server, "isShutdown");
                Object isStarted = ReflectionHelper.invokeMethod(server, "isStarted");
                info.put("isStarted", isStarted);
                info.put("isShutdown", isShutdown);
                services.add(info);
            }
        } catch (Exception ignored) {}

        // grpc-spring-boot-starter (LogNet) bean name patterns
        try {
            for (String name : ctx.getBeanDefinitionNames()) {
                if (name.toLowerCase().contains("grpcserver")
                        || name.toLowerCase().contains("grpcservice")) {
                    Object bean = ctx.getBean(name);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("type", "grpc-spring-bean");
                    info.put("beanName", name);
                    info.put("beanType", bean.getClass().getSimpleName());
                    if (!services.contains(info)) services.add(info);
                }
            }
        } catch (Exception ignored) {}

        if (services.isEmpty()) {
            services.add(Map.of("note", "No gRPC services detected in the context."));
        }
        return services;
    }

    @DebugTool(description = "Show gRPC call statistics collected from in-memory interceptors: per-method invocation count, success/failure counts, and latency. Pass a method name to filter.")
    public List<Map<String, Object>> getGrpcCallStats(
            @ToolParam(description = "Filter by full method name substring (optional)", required = false) String methodFilter
    ) {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : callStats.entrySet()) {
            if (methodFilter != null && !methodFilter.isBlank()
                    && !entry.getKey().toLowerCase().contains(methodFilter.toLowerCase())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", entry.getKey());
            row.putAll(entry.getValue());
            stats.add(row);
        }

        stats.sort((a, b) -> {
            long ca = a.get("count") instanceof Number na ? na.longValue() : 0L;
            long cb = b.get("count") instanceof Number nb ? nb.longValue() : 0L;
            return Long.compare(cb, ca);
        });

        if (stats.isEmpty()) {
            stats.add(Map.of(
                    "note", "No gRPC call statistics captured. Install a ServerInterceptor/ClientInterceptor " +
                            "to populate callStats, or instrument via Micrometer grpc-metrics."
            ));
        }
        return stats;
    }

    /**
     * Public entry point used by gRPC interceptors to record a call result.
     * Not annotated as a debug tool — called from application interceptors.
     */
    public void recordCall(String fullMethodName, long durationNanos, boolean success, String errorType) {
        Map<String, Object> stat = callStats.computeIfAbsent(fullMethodName, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", 0L);
            m.put("success", 0L);
            m.put("failure", 0L);
            m.put("totalDurationNanos", 0L);
            m.put("lastErrorType", null);
            return m;
        });
        synchronized (stat) {
            stat.merge("count", 1L, (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
            if (success) {
                stat.merge("success", 1L, (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
            } else {
                stat.merge("failure", 1L, (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
                stat.put("lastErrorType", errorType);
            }
            stat.merge("totalDurationNanos", durationNanos,
                    (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
            long count = ((Number) stat.get("count")).longValue();
            stat.put("avgDurationNanos", ((Number) stat.get("totalDurationNanos")).longValue() / count);
        }
    }
}
