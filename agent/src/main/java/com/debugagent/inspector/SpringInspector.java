package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Spring Framework-level diagnostic tools.
 * Directly accesses ApplicationContext, Environment, and bean internals.
 */
@Component
public class SpringInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ==================== Bean Tools ====================

    @DebugTool(description = "List all Spring beans. Returns bean name, type, and scope. Optional filter by type name.")
    public List<Map<String, Object>> getAllBeans(
            @ToolParam(description = "Filter beans by type name (case-insensitive partial match). Leave empty for all.") String typeFilter
    ) {
        List<Map<String, Object>> beans = new ArrayList<>();
        String[] names = ctx.getBeanDefinitionNames();
        Arrays.sort(names);

        for (String name : names) {
            try {
                Class<?> type = ctx.getType(name);
                if (type == null) continue;

                String typeName = type.getSimpleName();
                if (typeFilter != null && !typeFilter.isBlank()
                        && !typeName.toLowerCase().contains(typeFilter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", name);
                info.put("type", typeName);
                info.put("package", type.getPackageName());
                info.put("scope", getBeanScope(name));
                info.put("aliases", Arrays.asList(ctx.getAliases(name)));
                beans.add(info);
            } catch (Exception ignored) {
            }
        }

        return beans;
    }

    @DebugTool(description = "Get detailed information about a specific Spring bean, including its fields and current values.")
    public Map<String, Object> getBeanDetails(
            @ToolParam(description = "The bean name or type name", required = true) String beanName
    ) {
        Object bean = resolveBean(beanName);
        if (bean == null) {
            return Map.of("error", "Bean not found: " + beanName
                    + ". Use getAllBeans to see available beans.");
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", beanName);
        info.put("className", bean.getClass().getName());
        info.put("scope", getBeanScope(beanName));

        // List all fields with current values
        List<Map<String, Object>> fields = new ArrayList<>();
        Class<?> clazz = bean.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                // Skip synthetic/lombok generated fields
                if (field.isSynthetic()) continue;

                Map<String, Object> fieldInfo = new LinkedHashMap<>();
                fieldInfo.put("name", field.getName());
                fieldInfo.put("type", field.getType().getSimpleName());
                fieldInfo.put("declaringClass", clazz.getSimpleName());

                try {
                    field.setAccessible(true);
                    Object value = field.get(bean);
                    fieldInfo.put("value", safeToString(value));
                } catch (Exception e) {
                    fieldInfo.put("value", "<not accessible: " + e.getMessage() + ">");
                }
                fields.add(fieldInfo);
            }
            clazz = clazz.getSuperclass();
        }
        info.put("fields", fields);

        return info;
    }

    @DebugTool(description = "Get all Spring beans that depend on the specified bean, and all beans it depends on.")
    public Map<String, Object> getBeanDependencies(
            @ToolParam(description = "The bean name", required = true) String beanName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory =
                    (org.springframework.beans.factory.config.ConfigurableListableBeanFactory)
                            ctx.getAutowireCapableBeanFactory();

            // Get dependent beans (who depends on me)
            String[] dependents = factory.getDependentBeans(beanName);
            result.put("dependedOnBy", Arrays.asList(dependents));

            // Get dependencies (who I depend on)
            String[] dependencies = factory.getDependenciesForBean(beanName);
            result.put("dependsOn", Arrays.asList(dependencies));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return result;
    }

    // ==================== Configuration Tools ====================

    @DebugTool(description = "Get the value of a specific configuration property. Works with application.yml, environment variables, and command-line args.")
    public Map<String, Object> getProperty(
            @ToolParam(description = "Property key, e.g. 'spring.datasource.url'", required = true) String key
    ) {
        Environment env = ctx.getEnvironment();
        String value = env.getProperty(key);

        Map<String, Object> result = new LinkedHashMap<>();
        if (value != null) {
            // Mask sensitive properties
            if (isSensitiveKey(key)) {
                result.put("value", maskValue(value));
                result.put("masked", true);
            } else {
                result.put("value", value);
            }
        } else {
            result.put("error", "Property '" + key + "' not found");
            result.put("suggestion", "Use searchProperties with a pattern to find similar keys");
        }
        return result;
    }

    @DebugTool(description = "Search configuration properties by key pattern. Returns all matching property keys and values (sensitive values masked).")
    public List<Map<String, Object>> searchProperties(
            @ToolParam(description = "Search keyword or pattern (case-insensitive)", required = true) String keyword
    ) {
        Environment env = ctx.getEnvironment();
        List<Map<String, Object>> results = new ArrayList<>();

        // Get all known property keys from the environment
        // This is a best-effort approach since Environment doesn't enumerate all properties directly
        Set<String> checkedKeys = collectPropertyKeys(env);

        for (String key : checkedKeys) {
            if (key.toLowerCase().contains(keyword.toLowerCase())) {
                String value = env.getProperty(key);
                if (value != null) {
                    Map<String, Object> prop = new LinkedHashMap<>();
                    prop.put("key", key);
                    if (isSensitiveKey(key)) {
                        prop.put("value", maskValue(value));
                        prop.put("masked", true);
                    } else {
                        prop.put("value", value);
                    }
                    results.add(prop);
                }
            }
        }

        return results;
    }

    @DebugTool(description = "Get all active Spring profiles and all defined profiles.")
    public Map<String, Object> getActiveProfiles() {
        Environment env = ctx.getEnvironment();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeProfiles", Arrays.asList(env.getActiveProfiles()));
        result.put("defaultProfiles", Arrays.asList(env.getDefaultProfiles()));
        return result;
    }

    // ==================== Context Info ====================

    @DebugTool(description = "Get basic info about the Spring ApplicationContext: startup date, bean count, parent context, etc.")
    public Map<String, Object> getContextInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("applicationName", ctx.getApplicationName());
        info.put("displayName", ctx.getDisplayName());
        info.put("startupDate", ctx.getStartupDate());
        info.put("beanDefinitionCount", ctx.getBeanDefinitionCount());

        long uptime = System.currentTimeMillis() - ctx.getStartupDate();
        info.put("uptimeMs", uptime);

        ApplicationContext parent = ctx.getParent();
        info.put("hasParentContext", parent != null);
        if (parent != null) {
            info.put("parentContextName", parent.getDisplayName());
        }

        return info;
    }

    // ==================== Helpers ====================

    private Object resolveBean(String nameOrType) {
        // Try by bean name first
        try {
            return ctx.getBean(nameOrType);
        } catch (Exception ignored) {
        }

        // Try by type name (simple or full)
        try {
            Class<?> type = ctx.getType(nameOrType);
            if (type != null) {
                return ctx.getBean(type);
            }
        } catch (Exception ignored) {
        }

        // Search by simple type name
        for (String beanName : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(beanName);
            if (type != null && type.getSimpleName().equalsIgnoreCase(nameOrType)) {
                try {
                    return ctx.getBean(beanName);
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    private String getBeanScope(String beanName) {
        try {
            if (ctx.isSingleton(beanName)) return "singleton";
            if (ctx.isPrototype(beanName)) return "prototype";
            return "custom";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("credential") || lower.contains("token")
                || lower.contains("key") && !lower.contains("keyword");
    }

    private String maskValue(String value) {
        if (value == null || value.length() <= 4) return "****";
        return value.substring(0, 2) + "****"
                + value.substring(value.length() - 2);
    }

    private Set<String> collectPropertyKeys(Environment env) {
        Set<String> keys = new TreeSet<>();
        if (env instanceof org.springframework.core.env.ConfigurableEnvironment configEnv) {
            configEnv.getPropertySources().forEach(ps -> {
                if (ps.getSource() instanceof Map<?, ?> map) {
                    for (Object key : map.keySet()) {
                        if (key instanceof String s) {
                            keys.add(s);
                        }
                    }
                }
            });
        }
        return keys;
    }

    private String safeToString(Object value) {
        if (value == null) return "null";
        String str = value.toString();
        if (str.length() > 200) {
            return str.substring(0, 200) + "... (truncated)";
        }
        return str;
    }
}
