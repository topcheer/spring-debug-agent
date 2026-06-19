package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Distributed tracing diagnostic tools (OpenTelemetry / Micrometer Tracing).
 * Inspects tracer configuration, recent spans, dependency graph, and slow spans.
 * Conditional on OpenTelemetry or Micrometer Tracing being on classpath.
 */
public class TracingInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    /** In-memory ring buffer of recent spans, capped at 500 entries. */
    private final List<Map<String, Object>> recentSpans =
            Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_SPANS = 500;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Check whether OpenTelemetry or Micrometer Tracing is active. Reports tracer type, configured exporter, and sampling configuration. Useful to verify tracing is wired correctly.")
    public Map<String, Object> getTraceInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        boolean otelAvailable = ReflectionHelper.isClassAvailable("io.opentelemetry.api.OpenTelemetry");
        boolean micrometerAvailable = ReflectionHelper.isClassAvailable("io.micrometer.tracing.Tracer");
        boolean braveAvailable = ReflectionHelper.isClassAvailable("brave.Tracer");

        result.put("openTelemetryOnClasspath", otelAvailable);
        result.put("micrometerTracingOnClasspath", micrometerAvailable);
        result.put("braveOnClasspath", braveAvailable);

        String tracerType = "none";
        Object tracerBean = null;

        if (micrometerAvailable) {
            tracerBean = ReflectionHelper.getFirstBeanOfType(ctx, "io.micrometer.tracing.Tracer");
            if (tracerBean != null) {
                tracerType = "micrometer-tracing (" + tracerBean.getClass().getSimpleName() + ")";
            }
        }
        if (tracerBean == null && otelAvailable) {
            Object otel = ReflectionHelper.getFirstBeanOfType(ctx, "io.opentelemetry.api.OpenTelemetry");
            if (otel != null) {
                tracerType = "open-telemetry";
                Object tracer = ReflectionHelper.invokeMethod(otel, "getTracer");
                if (tracer != null) {
                    result.put("otelTracerProvider", tracer.getClass().getName());
                }
            }
        }
        if (tracerBean == null && braveAvailable) {
            tracerBean = ReflectionHelper.getFirstBeanOfType(ctx, "brave.Tracer");
            if (tracerBean != null) {
                tracerType = "brave (" + tracerBean.getClass().getSimpleName() + ")";
            }
        }

        result.put("tracerType", tracerType);
        result.put("tracerActive", tracerBean != null);

        // Sampling configuration
        try {
            String samplingProp = ctx.getEnvironment().getProperty(
                    "management.tracing.sampling.probability");
            result.put("samplingProbability", samplingProp != null ? samplingProp : "default (0.10)");
        } catch (Exception ignored) {}

        // Exporter configuration hints
        Map<String, Object> exporters = new LinkedHashMap<>();
        try {
            exporters.put("otelExporterOtlpEndpoint",
                    ctx.getEnvironment().getProperty("otel.exporter.otlp.endpoint"));
            exporters.put("otelExporterOtlpProtocol",
                    ctx.getEnvironment().getProperty("otel.exporter.otlp.protocol"));
            exporters.put("managementZipkinTracingEndpoint",
                    ctx.getEnvironment().getProperty("management.zipkin.tracing.endpoint"));
        } catch (Exception ignored) {}
        result.put("exporterConfig", exporters);

        // In-memory buffer status
        result.put("bufferedSpans", recentSpans.size());

        if (tracerBean == null) {
            result.put("hint", "No tracer bean detected. Ensure spring-boot-starter-actuator with " +
                    "micrometer-tracing-bridge-otel (or -brave) is on classpath.");
        }

        return result;
    }

    @DebugTool(description = "Return recent spans captured in the in-memory ring buffer (max 500 entries). Each span includes traceId, spanId, name, duration, and status. Pass a limit to truncate the result.")
    public List<Map<String, Object>> getRecentSpans(
            @ToolParam(description = "Maximum number of spans to return (default 50)", required = false) Integer limit
    ) {
        int n = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;
        List<Map<String, Object>> snapshot;
        synchronized (recentSpans) {
            int size = recentSpans.size();
            int from = Math.max(0, size - n);
            snapshot = new ArrayList<>(recentSpans.subList(from, size));
        }
        Collections.reverse(snapshot);
        return snapshot;
    }

    @DebugTool(description = "Build a service dependency graph from buffered trace data. Each entry shows a source service, target service, and the span count that flowed between them.")
    public List<Map<String, Object>> getTraceDependencies() {
        Map<String, Long> edges = new LinkedHashMap<>();
        synchronized (recentSpans) {
            for (Map<String, Object> span : recentSpans) {
                Object src = span.get("sourceService");
                Object dst = span.get("targetService");
                if (src == null) src = "self";
                if (dst == null) dst = "self";
                String key = src + " -> " + dst;
                edges.merge(key, 1L, Long::sum);
            }
        }

        List<Map<String, Object>> deps = new ArrayList<>();
        edges.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    String[] parts = e.getKey().split(" -> ");
                    Map<String, Object> dep = new LinkedHashMap<>();
                    dep.put("source", parts.length > 0 ? parts[0] : "self");
                    dep.put("target", parts.length > 1 ? parts[1] : "self");
                    dep.put("spanCount", e.getValue());
                    deps.add(dep);
                });

        if (deps.isEmpty()) {
            deps.add(Map.of("note", "No buffered spans available. The dependency graph is built from " +
                    "captured spans; run more traced requests or use an external backend (Tempo/Jaeger)."));
        }
        return deps;
    }

    @DebugTool(description = "Filter buffered spans whose duration is greater than or equal to the given threshold. Useful for finding slow operations within traces.")
    public List<Map<String, Object>> getSlowSpans(
            @ToolParam(description = "Minimum span duration in milliseconds (default 500)", required = false) Integer minDurationMs
    ) {
        long threshold = minDurationMs != null ? Math.max(0, minDurationMs) : 500L;
        List<Map<String, Object>> slow = new ArrayList<>();
        synchronized (recentSpans) {
            for (Map<String, Object> span : recentSpans) {
                Object dur = span.get("durationMs");
                if (dur instanceof Number n && n.longValue() >= threshold) {
                    slow.add(new LinkedHashMap<>(span));
                }
            }
        }
        slow.sort((a, b) -> {
            long da = a.get("durationMs") instanceof Number na ? na.longValue() : 0L;
            long db = b.get("durationMs") instanceof Number nb ? nb.longValue() : 0L;
            return Long.compare(db, da);
        });
        return slow;
    }
}
