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
}
