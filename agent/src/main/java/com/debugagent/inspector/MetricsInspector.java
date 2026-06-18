package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Micrometer metrics inspector.
 * Accesses MeterRegistry via reflection — zero hard dependency on Micrometer.
 * Conditional on io.micrometer.core.instrument.MeterRegistry on classpath.
 */
@Component
public class MetricsInspector implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(MetricsInspector.class);
    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all registered Micrometer metrics (gauges, counters, timers, distribution summaries). Returns meter name, type, and tags.")
    public Map<String, Object> getMetricsList(
            @ToolParam(description = "Filter by metric name prefix (e.g., 'jvm.', 'hikaricp.', 'http.server'). Leave empty for all.") String nameFilter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object registry = getMeterRegistry();
            if (registry == null) {
                result.put("error", "No MeterRegistry found. Add micrometer-core to classpath.");
                result.put("suggestion", "Add spring-boot-starter-actuator which includes Micrometer.");
                return result;
            }

            // Call getMeters() via reflection
            Object meters = ReflectionHelper.invokeMethod(registry, "getMeters");
            if (!(meters instanceof Iterable<?> iterable)) {
                result.put("error", "Unexpected meters type: " + (meters == null ? "null" : meters.getClass().getName()));
                return result;
            }

            List<Map<String, Object>> metrics = new ArrayList<>();
            int total = 0;
            for (Object meter : iterable) {
                total++;
                String name = (String) ReflectionHelper.invokeMethod(meter, "getId") != null
                        ? getNameFromId(ReflectionHelper.invokeMethod(meter, "getId"))
                        : "unknown";

                if (nameFilter != null && !nameFilter.isBlank()
                        && !name.toLowerCase().contains(nameFilter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("type", meter.getClass().getInterfaces().length > 0
                        ? meter.getClass().getInterfaces()[0].getSimpleName()
                        : meter.getClass().getSimpleName());

                // Extract tags from getId()
                Object id = ReflectionHelper.invokeMethod(meter, "getId");
                Map<String, String> tags = getTagsFromId(id);
                if (!tags.isEmpty()) m.put("tags", tags);

                metrics.add(m);
            }

            result.put("metrics", metrics);
            result.put("totalMeters", total);
            result.put("filteredCount", metrics.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Read the current value of a specific Micrometer metric. For gauges returns value, for counters returns count, for timers returns count/total/max.")
    public Map<String, Object> getMetricValue(
            @ToolParam(description = "Metric name (exact match)", required = true) String metricName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object registry = getMeterRegistry();
            if (registry == null) {
                result.put("error", "No MeterRegistry found");
                return result;
            }

            Object meters = ReflectionHelper.invokeMethod(registry, "getMeters");
            if (!(meters instanceof Iterable<?> iterable)) {
                result.put("error", "Cannot iterate meters");
                return result;
            }

            List<Map<String, Object>> matches = new ArrayList<>();
            for (Object meter : iterable) {
                Object id = ReflectionHelper.invokeMethod(meter, "getId");
                String name = getNameFromId(id);
                if (metricName.equals(name)) {
                    matches.add(extractMeterValues(meter));
                }
            }

            if (matches.isEmpty()) {
                result.put("error", "No meter found with name '" + metricName + "'");
                result.put("suggestion", "Use get_metrics_list to see available metric names");
            } else {
                result.put("metricName", metricName);
                result.put("matches", matches);
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "List all MeterRegistry instances and their types (Prometheus, JMX, Simple, etc.). Shows which backends are receiving metrics.")
    public Map<String, Object> getMeterRegistries() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object composite = getMeterRegistry();
            if (composite == null) {
                result.put("error", "No MeterRegistry found");
                return result;
            }

            List<Map<String, Object>> registries = new ArrayList<>();

            // Check if it's a CompositeMeterRegistry
            String compositeClassName = composite.getClass().getName();
            if (compositeClassName.contains("CompositeMeterRegistry")) {
                // Get registries from composite
                Object children = ReflectionHelper.invokeMethod(composite, "getRegistries");
                if (children instanceof Iterable<?> iterable) {
                    for (Object child : iterable) {
                        registries.add(describeRegistry(child));
                    }
                }
            } else {
                registries.add(describeRegistry(composite));
            }

            result.put("registries", registries);
            result.put("count", registries.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Helpers ====================

    private Object getMeterRegistry() {
        return ReflectionHelper.getFirstBeanOfType(ctx, "io.micrometer.core.instrument.MeterRegistry");
    }

    private String getNameFromId(Object id) {
        if (id == null) return "unknown";
        Object name = ReflectionHelper.invokeMethod(id, "getName");
        return name != null ? name.toString() : "unknown";
    }

    private Map<String, String> getTagsFromId(Object id) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (id == null) return tags;
        Object tagsObj = ReflectionHelper.invokeMethod(id, "getTags");
        if (tagsObj instanceof Iterable<?> iterable) {
            for (Object tag : iterable) {
                String key = ReflectionHelper.invokeMethod(tag, "getKey") != null
                        ? ReflectionHelper.invokeMethod(tag, "getKey").toString() : "?";
                String val = ReflectionHelper.invokeMethod(tag, "getValue") != null
                        ? ReflectionHelper.invokeMethod(tag, "getValue").toString() : "?";
                tags.put(key, val);
            }
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMeterValues(Object meter) {
        Map<String, Object> values = new LinkedHashMap<>();
        String type = meter.getClass().getInterfaces().length > 0
                ? meter.getClass().getInterfaces()[0].getSimpleName()
                : meter.getClass().getSimpleName();
        values.put("type", type);

        // Extract tags
        Object id = ReflectionHelper.invokeMethod(meter, "getId");
        values.put("tags", getTagsFromId(id));

        // Read value based on meter type
        switch (type) {
            case "Gauge" -> {
                Object val = ReflectionHelper.invokeMethod(meter, "value");
                values.put("value", val);
            }
            case "Counter" -> {
                Object val = ReflectionHelper.invokeMethod(meter, "count");
                values.put("count", val);
            }
            case "Timer" -> {
                values.put("count", ReflectionHelper.invokeMethod(meter, "count"));
                values.put("mean", ReflectionHelper.invokeMethod(meter, "mean"));
                Object max = ReflectionHelper.invokeMethod(meter, "max");
                values.put("max", max != null ? max : "n/a");
            }
            case "DistributionSummary" -> {
                values.put("count", ReflectionHelper.invokeMethod(meter, "count"));
                values.put("mean", ReflectionHelper.invokeMethod(meter, "mean"));
                values.put("max", ReflectionHelper.invokeMethod(meter, "max"));
                values.put("totalAmount", ReflectionHelper.invokeMethod(meter, "totalAmount"));
            }
            default -> {
                // Try common methods
                for (String method : List.of("value", "count", "measure")) {
                    Object val = ReflectionHelper.invokeMethod(meter, method);
                    if (val != null) values.put(method, val);
                }
            }
        }
        return values;
    }

    private Map<String, Object> describeRegistry(Object registry) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("class", registry.getClass().getSimpleName());
        info.put("className", registry.getClass().getName());
        try {
            Object meters = ReflectionHelper.invokeMethod(registry, "getMeters");
            if (meters instanceof Iterable<?> iterable) {
                int count = 0;
                for (Object ignored : iterable) count++;
                info.put("meterCount", count);
            }
        } catch (Exception ignored) {}
        return info;
    }
}
