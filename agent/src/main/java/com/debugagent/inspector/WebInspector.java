package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Web-layer inspection tools: HTTP endpoint mapping discovery + method invocation.
 * Uses reflection on Spring Web's HandlerMapping — no hard web dependency.
 */
public class WebInspector implements ApplicationContextAware {

    private final ObjectMapper mapper = new ObjectMapper();
    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ==================== HTTP Endpoints ====================

    @DebugTool(description = "List all HTTP endpoints (REST API routes) registered in the application. Returns HTTP method, URL path, controller class, and method name.")
    public List<Map<String, Object>> getHttpEndpoints(
            @ToolParam(description = "Optional filter by controller or path keyword (case-insensitive)") String filter
    ) {
        List<Map<String, Object>> endpoints = new ArrayList<>();

        try {
            // Try RequestMappingHandlerMapping (Spring MVC)
            Object handlerMapping = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping");
            if (handlerMapping == null) return Collections.singletonList(errorMap("Spring Web MVC not found on classpath"));

            // Call getHandlerMethods() via reflection
            Object handlerMethodsMap = ReflectionHelper.invokeMethod(handlerMapping, "getHandlerMethods");
            if (!(handlerMethodsMap instanceof Map)) return Collections.singletonList(errorMap("Could not retrieve handler methods"));

            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) handlerMethodsMap;

            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object info = entry.getKey();
                Object handler = entry.getValue();

                Map<String, Object> ep = new LinkedHashMap<>();
                // Extract URL patterns
                Object patternsCondition = ReflectionHelper.invokeMethod(info, "getPathPatternsCondition");
                if (patternsCondition == null) {
                    patternsCondition = ReflectionHelper.invokeMethod(info, "getPatternsCondition");
                }
                Object patterns = patternsCondition != null ? ReflectionHelper.invokeMethod(patternsCondition, "getPatterns") : null;

                // Extract HTTP methods
                Object methodsCondition = ReflectionHelper.invokeMethod(info, "getMethodsCondition");
                Object methods = methodsCondition != null ? ReflectionHelper.invokeMethod(methodsCondition, "getMethods") : null;

                String urlPaths = patterns != null ? patterns.toString() : "[]";
                String httpMethods = methods != null ? methods.toString() : "[]";

                // Handler method info
                Object beanMethod = ReflectionHelper.invokeMethod(handler, "getMethod");
                String controllerName = "";
                String methodName = "";
                if (beanMethod instanceof Method m) {
                    methodName = m.getName();
                    controllerName = m.getDeclaringClass().getSimpleName();
                }

                ep.put("methods", httpMethods);
                ep.put("path", urlPaths);
                ep.put("controller", controllerName);
                ep.put("method", methodName);

                if (filter == null || filter.isBlank() ||
                        (controllerName + methodName + urlPaths).toLowerCase().contains(filter.toLowerCase())) {
                    endpoints.add(ep);
                }
            }
        } catch (Exception e) {
            endpoints.add(errorMap("Failed to list endpoints: " + e.getMessage()));
        }

        return endpoints;
    }

    // ==================== Invoke Bean Method ====================

    @DebugTool(description = "Invoke a method on a Spring bean by name. Useful for testing business logic at runtime. Parameters are passed as a JSON array string matching method signature.")
    public Map<String, Object> invokeBeanMethod(
            @ToolParam(description = "Spring bean name (use get_all_beans to find)") String beanName,
            @ToolParam(description = "Method name to invoke") String methodName,
            @ToolParam(description = "JSON array of arguments, e.g. [42, \"hello\"]. Use [] for no-arg methods.", required = false) String argsJson
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            if (!ctx.containsBean(beanName)) {
                result.put("error", "Bean not found: " + beanName);
                return result;
            }

            Object bean = ctx.getBean(beanName);

            // Parse args
            Object[] args;
            if (argsJson == null || argsJson.isBlank() || argsJson.equals("[]")) {
                args = new Object[0];
            } else {
                args = mapper.readValue(argsJson, Object[].class);
            }

            // Find matching method
            Method matched = findMatchingMethod(bean.getClass(), methodName, args.length);
            if (matched == null) {
                result.put("error", "Method '" + methodName + "' with " + args.length + " params not found on " + bean.getClass().getName());
                return result;
            }

            matched.setAccessible(true);

            // Convert args to method param types
            Object[] converted = convertArgs(matched, args);

            Object returnValue = matched.invoke(bean, converted);
            result.put("bean", beanName);
            result.put("method", methodName);
            result.put("success", true);
            result.put("returnValue", ReflectionHelper.safeToString(returnValue));
            result.put("returnType", returnValue != null ? returnValue.getClass().getName() : "void");

        } catch (Exception e) {
            result.put("bean", beanName);
            result.put("method", methodName);
            result.put("success", false);
            result.put("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }

        return result;
    }

    private Method findMatchingMethod(Class<?> clazz, String name, int paramCount) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        // Try superclass
        Class<?> sup = clazz.getSuperclass();
        if (sup != null) return findMatchingMethod(sup, name, paramCount);
        return null;
    }

    private Object[] convertArgs(Method method, Object[] args) {
        Parameter[] params = method.getParameters();
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Class<?> targetType = params[i].getType();
            Object val = args[i];
            if (val instanceof Number n) {
                if (targetType == Long.class || targetType == long.class) converted[i] = n.longValue();
                else if (targetType == Integer.class || targetType == int.class) converted[i] = n.intValue();
                else if (targetType == Double.class || targetType == double.class) converted[i] = n.doubleValue();
                else if (targetType == Float.class || targetType == float.class) converted[i] = n.floatValue();
                else converted[i] = val;
            } else if (val instanceof String s && (targetType == Long.class || targetType == long.class)) {
                converted[i] = Long.parseLong(s);
            } else if (val instanceof String s && (targetType == Integer.class || targetType == int.class)) {
                converted[i] = Integer.parseInt(s);
            } else if (val instanceof String s && targetType.isEnum()) {
                Object[] constants = targetType.getEnumConstants();
                for (Object c : constants) {
                    if (c.toString().equals(s)) { converted[i] = c; break; }
                }
            } else {
                converted[i] = val;
            }
        }
        return converted;
    }

    private Map<String, Object> errorMap(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    @DebugTool(description = "List all registered servlet filter chains. Shows which filters apply to which URL patterns. Useful for debugging Spring Security, CORS, or request lifecycle issues.")
    public List<Map<String, Object>> getFilterChains() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            // Try to get FilterRegistrationBeans from the context
            Map<String, ?> filterBeans = ctx.getBeansOfType(
                    jakarta.servlet.Filter.class);

            for (Map.Entry<String, ?> entry : filterBeans.entrySet()) {
                Map<String, Object> filterInfo = new LinkedHashMap<>();
                filterInfo.put("beanName", entry.getKey());
                filterInfo.put("filterClass", entry.getValue().getClass().getSimpleName());

                // Check for registration
                try {
                    Object registration =
                            ReflectionHelper.invokeMethod(entry.getValue(), "getRegistration");
                    if (registration != null) {
                        Map<String, Object> regInfo = new LinkedHashMap<>();
                        regInfo.put("class", registration.getClass().getSimpleName());
                        result.add(regInfo);
                    }
                } catch (Exception ignored) {}

                // Check if it's a Spring Security filter
                String className = entry.getValue().getClass().getName();
                if (className.contains("security") || className.contains("Security")) {
                    filterInfo.put("category", "security");
                } else if (className.contains("cors") || className.contains("Cors")) {
                    filterInfo.put("category", "cors");
                } else if (className.contains("CharacterEncoding") || className.contains("RequestContext")) {
                    filterInfo.put("category", "spring-core");
                } else {
                    filterInfo.put("category", "other");
                }

                result.add(filterInfo);
            }

            // Also try to get FilterChainProxy (Spring Security)
            try {
                Object filterChainProxy = ctx.getBean("springSecurityFilterChain");
                Map<String, Object> secInfo = new LinkedHashMap<>();
                secInfo.put("beanName", "springSecurityFilterChain");
                secInfo.put("filterClass", filterChainProxy.getClass().getSimpleName());
                secInfo.put("category", "spring-security-chain");
                secInfo.put("note", "This is the main Spring Security filter chain proxy. " +
                        "It delegates to multiple SecurityFilterChain instances based on URL pattern.");
                result.add(secInfo);
            } catch (Exception ignored) {
                // Spring Security not present
            }

        } catch (Exception e) {
            result.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return result;
    }
}
