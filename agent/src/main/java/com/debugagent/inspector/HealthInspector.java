package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Spring Boot Actuator health inspection tool.
 * Uses reflection on HealthEndpoint — no hard actuator dependency.
 */
public class HealthInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get application health status from Spring Boot Actuator (database, disk, ping, custom health indicators). Returns component health and overall status.")
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Object healthEndpoint = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.boot.actuate.health.HealthEndpoint");
            if (healthEndpoint == null) {
                result.put("error", "Spring Boot Actuator HealthEndpoint not found. Add spring-boot-starter-actuator dependency.");
                return result;
            }

            // Call health() via reflection
            Object healthResult = ReflectionHelper.invokeMethod(healthEndpoint, "health");
            if (healthResult == null) {
                result.put("error", "HealthEndpoint.health() returned null");
                return result;
            }

            // Extract status
            Object status = ReflectionHelper.invokeMethod(healthResult, "getStatus");
            result.put("status", status != null ? status.toString() : "UNKNOWN");

            // Extract details map
            Object details = ReflectionHelper.invokeMethod(healthResult, "getDetails");
            if (details instanceof Map<?, ?> detailMap) {
                List<Map<String, Object>> components = new ArrayList<>();
                for (Map.Entry<?, ?> entry : detailMap.entrySet()) {
                    Map<String, Object> component = new LinkedHashMap<>();
                    component.put("component", entry.getKey().toString());

                    Object compHealth = entry.getValue();
                    Object compStatus = ReflectionHelper.invokeMethod(compHealth, "getStatus");
                    component.put("status", compStatus != null ? compStatus.toString() : "UNKNOWN");

                    Object compDetails = ReflectionHelper.invokeMethod(compHealth, "getDetails");
                    if (compDetails instanceof Map<?, ?> cd) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : cd.entrySet()) {
                            d.put(e.getKey().toString(), ReflectionHelper.safeToString(e.getValue()));
                        }
                        if (!d.isEmpty()) component.put("details", d);
                    }
                    components.add(component);
                }
                result.put("components", components);
            }

        } catch (Exception e) {
            result.put("error", "Failed to get health: " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Deep-dive into a specific health component (e.g. 'db', 'redis', 'disk'). "
            + "Returns full details for one component rather than the overview.")
    public Map<String, Object> getHealthComponentDetail(
            @ToolParam(description = "Component name to inspect (e.g. 'db', 'redis', 'ping')") String componentName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Object healthEndpoint = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.boot.actuate.health.HealthEndpoint");
            if (healthEndpoint == null) {
                result.put("error", "Spring Boot Actuator HealthEndpoint not found");
                return result;
            }

            // Call healthForPath(componentName) — Spring Boot 2.x/3.x
            Object healthResult = null;
            try {
                java.lang.reflect.Method healthForPath = healthEndpoint.getClass()
                        .getMethod("healthForPath", String[].class);
                healthResult = healthForPath.invoke(healthEndpoint, new Object[]{new String[]{componentName}});
            } catch (NoSuchMethodException e) {
                // Fall back to health() and extract component
                healthResult = ReflectionHelper.invokeMethod(healthEndpoint, "health");
                if (healthResult != null) {
                    Object details = ReflectionHelper.invokeMethod(healthResult, "getDetails");
                    if (details instanceof Map<?, ?> detailMap) {
                        Object comp = detailMap.get(componentName);
                        if (comp != null) {
                            healthResult = comp;
                        } else {
                            result.put("error", "Component not found: " + componentName);
                            result.put("availableComponents", detailMap.keySet());
                            return result;
                        }
                    }
                }
            }

            if (healthResult == null) {
                result.put("error", "No health result for component: " + componentName);
                return result;
            }

            Object status = ReflectionHelper.invokeMethod(healthResult, "getStatus");
            result.put("component", componentName);
            result.put("status", status != null ? status.toString() : "UNKNOWN");

            Object details = ReflectionHelper.invokeMethod(healthResult, "getDetails");
            if (details instanceof Map<?, ?> detailMap) {
                Map<String, Object> d = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : detailMap.entrySet()) {
                    d.put(entry.getKey().toString(), ReflectionHelper.safeToString(entry.getValue()));
                }
                result.put("details", d);
            }
        } catch (Exception e) {
            result.put("error", "Failed to get health component: " + e.getMessage());
        }

        return result;
    }
}
