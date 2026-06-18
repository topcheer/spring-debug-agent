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

    // ==================== Bean Methods & Annotations ====================

    @DebugTool(description = "List all public methods of a Spring bean, including parameter types and return type. Useful for understanding a bean's API without reading source code.")
    public List<Map<String, Object>> getBeanMethods(
            @ToolParam(description = "Bean name or type", required = true) String beanName
    ) {
        Object bean = resolveBean(beanName);
        if (bean == null) {
            return Collections.singletonList(Map.of("error", "Bean not found: " + beanName));
        }

        List<Map<String, Object>> methods = new ArrayList<>();
        Class<?> clazz = bean.getClass();
        Set<String> seen = new HashSet<>();

        // Walk class hierarchy
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                if (m.isSynthetic() || m.isBridge()) continue;

                String sig = m.getName() + "(" + Arrays.toString(m.getParameterTypes()) + ")";
                if (seen.contains(sig)) continue;
                seen.add(sig);

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", m.getName());
                info.put("returnType", m.getReturnType().getSimpleName());
                info.put("declaringClass", m.getDeclaringClass().getSimpleName());

                // Parameters
                List<Map<String, String>> params = new ArrayList<>();
                java.lang.reflect.Parameter[] parameters = m.getParameters();
                java.lang.annotation.Annotation[][] paramAnnotations = m.getParameterAnnotations();
                for (int i = 0; i < parameters.length; i++) {
                    Map<String, String> param = new LinkedHashMap<>();
                    param.put("type", parameters[i].getType().getSimpleName());
                    param.put("name", parameters[i].isNamePresent() ? parameters[i].getName() : "arg" + i);
                    // Check for @RequestParam, @PathVariable, etc.
                    for (java.lang.annotation.Annotation a : paramAnnotations[i]) {
                        String annName = a.annotationType().getSimpleName();
                        if (annName.equals("RequestParam") || annName.equals("PathVariable")
                                || annName.equals("RequestBody") || annName.equals("RequestHeader")) {
                            param.put("annotation", annName);
                        }
                    }
                    params.add(param);
                }
                info.put("parameters", params);
                info.put("parameterCount", params.size());

                // Annotations on the method
                List<String> methodAnnotations = new ArrayList<>();
                for (java.lang.annotation.Annotation a : m.getAnnotations()) {
                    methodAnnotations.add(a.annotationType().getSimpleName());
                }
                if (!methodAnnotations.isEmpty()) {
                    info.put("annotations", methodAnnotations);
                }

                methods.add(info);
            }
            clazz = clazz.getSuperclass();
        }

        return methods;
    }

    @DebugTool(description = "Get all annotations on a Spring bean class and its fields. Shows @Transactional, @Cacheable, @Scheduled, @Service, etc. Useful for understanding a bean's behavior configuration.")
    public Map<String, Object> getBeanAnnotations(
            @ToolParam(description = "Bean name or type", required = true) String beanName
    ) {
        Object bean = resolveBean(beanName);
        if (bean == null) {
            return Map.of("error", "Bean not found: " + beanName);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("beanName", beanName);
        result.put("className", bean.getClass().getName());

        // Class-level annotations
        List<Map<String, Object>> classAnnotations = new ArrayList<>();
        Class<?> clazz = bean.getClass();
        for (java.lang.annotation.Annotation a : clazz.getAnnotations()) {
            Map<String, Object> annInfo = new LinkedHashMap<>();
            annInfo.put("type", a.annotationType().getSimpleName());
            annInfo.put("fullName", a.annotationType().getName());
            // Try to extract annotation values
            try {
                Map<String, Object> values = new LinkedHashMap<>();
                for (java.lang.reflect.Method am : a.annotationType().getDeclaredMethods()) {
                    Object val = am.invoke(a);
                    if (val != null && !val.equals(am.getDefaultValue())) {
                        values.put(am.getName(), val.toString());
                    }
                }
                if (!values.isEmpty()) annInfo.put("attributes", values);
            } catch (Exception ignored) {}
            classAnnotations.add(annInfo);
        }
        result.put("classAnnotations", classAnnotations);

        // Field-level annotations
        List<Map<String, Object>> fieldAnnotations = new ArrayList<>();
        Class<?> fieldClazz = bean.getClass();
        while (fieldClazz != null && fieldClazz != Object.class) {
            for (Field f : fieldClazz.getDeclaredFields()) {
                java.lang.annotation.Annotation[] anns = f.getAnnotations();
                if (anns.length == 0) continue;
                Map<String, Object> fieldInfo = new LinkedHashMap<>();
                fieldInfo.put("field", f.getName());
                fieldInfo.put("type", f.getType().getSimpleName());
                List<String> fieldAnns = new ArrayList<>();
                for (java.lang.annotation.Annotation a : anns) {
                    fieldAnns.add(a.annotationType().getSimpleName());
                }
                fieldInfo.put("annotations", fieldAnns);
                fieldAnnotations.add(fieldInfo);
            }
            fieldClazz = fieldClazz.getSuperclass();
        }
        result.put("fieldAnnotations", fieldAnnotations);

        return result;
    }

    @DebugTool(description = "Get all configuration properties from a specific property source (e.g., 'applicationConfig', 'systemEnvironment', 'commandLineArgs'). More detailed than get_property.")
    public Map<String, Object> getEnvironmentProperties(
            @ToolParam(description = "Filter by property source name (case-insensitive partial match). Leave empty to list all sources.") String sourceFilter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (ctx.getEnvironment() instanceof org.springframework.core.env.ConfigurableEnvironment configEnv) {
            List<Map<String, Object>> sourceList = new ArrayList<>();
            org.springframework.core.env.MutablePropertySources sources = configEnv.getPropertySources();

            for (org.springframework.core.env.PropertySource<?> ps : sources) {
                String name = ps.getName();
                if (sourceFilter != null && !sourceFilter.isBlank()
                        && !name.toLowerCase().contains(sourceFilter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> sourceInfo = new LinkedHashMap<>();
                sourceInfo.put("name", name);
                sourceInfo.put("priority", sources.precedenceOf(ps));

                if (ps.getSource() instanceof Map<?, ?> map) {
                    Map<String, String> props = new TreeMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        String val = String.valueOf(entry.getValue());
                        if (isSensitive(key)) val = maskValue(val);
                        if (val.length() > 500) val = val.substring(0, 500) + "... (truncated)";
                        props.put(key, val);
                    }
                    sourceInfo.put("propertyCount", props.size());
                    sourceInfo.put("properties", props);
                } else {
                    sourceInfo.put("sourceType", ps.getSource().getClass().getSimpleName());
                }
                sourceList.add(sourceInfo);
            }

            result.put("sources", sourceList);
            result.put("totalSources", sourceList.size());
        } else {
            result.put("error", "Environment is not a ConfigurableEnvironment");
        }

        return result;
    }

    private boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("key")
                || lower.contains("token") || lower.contains("credential");
    }
}
