package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.*;

/**
 * Environment and property inspection tools.
 * Shows which properties override defaults and from which source they originate.
 */
public class EnvironmentInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Show all active property sources and highlight overridden values. Compares configuration sources (application.yml, env vars, command-line args).")
    public Map<String, Object> getEnvironmentDiff() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Environment env = ctx.getEnvironment();
            if (!(env instanceof ConfigurableEnvironment configEnv)) {
                result.put("error", "Environment is not ConfigurableEnvironment");
                return result;
            }

            MutablePropertySources sources = configEnv.getPropertySources();
            List<Map<String, Object>> sourceList = new ArrayList<>();
            Set<String> allPropertyNames = new LinkedHashSet<>();

            for (PropertySource<?> ps : sources) {
                Map<String, Object> sourceInfo = new LinkedHashMap<>();
                sourceInfo.put("name", ps.getName());
                sourceInfo.put("type", ps.getClass().getSimpleName());

                if (ps.getSource() instanceof Map<?, ?> map) {
                    int count = 0;
                    List<String> keys = new ArrayList<>();
                    for (Object key : map.keySet()) {
                        String keyStr = key.toString();
                        if (!keyStr.startsWith("java.") && !keyStr.startsWith("javax.")) {
                            keys.add(keyStr);
                            allPropertyNames.add(keyStr);
                            count++;
                        }
                    }
                    sourceInfo.put("propertyCount", count);
                    sourceInfo.put("properties", keys.size() > 50 ? keys.subList(0, 50) : keys);
                } else {
                    sourceInfo.put("sourceType", ps.getSource() != null ? ps.getSource().getClass().getName() : "null");
                }

                sourceList.add(sourceInfo);
            }

            result.put("activeProfiles", Arrays.asList(env.getActiveProfiles()));
            result.put("propertySources", sourceList);
            result.put("totalUniqueProperties", allPropertyNames.size());

        } catch (Exception e) {
            result.put("error", "Failed to get environment info: " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "List all @ConfigurationProperties beans with their prefix and resolved values. "
            + "Useful for verifying how configuration classes are bound at runtime.")
    public List<Map<String, Object>> getConfigurationPropertiesReport() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // Find all beans annotated with @ConfigurationProperties
            Map<String, Object> configBeans = ctx.getBeansWithAnnotation(
                    org.springframework.boot.context.properties.ConfigurationProperties.class);
            if (configBeans.isEmpty()) {
                results.add(Map.of("info", "No @ConfigurationProperties beans found"));
                return results;
            }

            for (Map.Entry<String, Object> entry : configBeans.entrySet()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("beanName", entry.getKey());
                info.put("className", entry.getValue().getClass().getName());

                ConfigurationProperties cp = entry.getValue().getClass().getAnnotation(ConfigurationProperties.class);
                if (cp != null) {
                    info.put("prefix", cp.prefix().isEmpty() ? cp.value() : cp.prefix());
                    info.put("ignoreUnknownFields", cp.ignoreUnknownFields());
                    info.put("ignoreInvalidFields", cp.ignoreInvalidFields());
                }

                // Try to serialize key fields (skip complex objects)
                try {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (java.lang.reflect.Field f : entry.getValue().getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object val = f.get(entry.getValue());
                        if (val == null || val instanceof String || val instanceof Number
                                || val instanceof Boolean || val instanceof java.util.Collection) {
                            values.put(f.getName(), val);
                        } else {
                            values.put(f.getName(), ReflectionHelper.safeToString(val));
                        }
                    }
                    if (!values.isEmpty()) info.put("values", values);
                } catch (Exception ignored) {}

                results.add(info);
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get configuration properties: " + e.getMessage()));
        }

        return results;
    }
}
