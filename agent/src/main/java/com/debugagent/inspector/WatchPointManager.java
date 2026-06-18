package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages runtime method watch points using ByteBuddy bytecode instrumentation.
 *
 * This is the core "virtual breakpoint" feature. When a watch point is set,
 * ByteBuddy dynamically instruments the target method in the running JVM
 * to capture every invocation's arguments, return value, timing, and exceptions.
 */
@Component
public class WatchPointManager implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(WatchPointManager.class);

    private ApplicationContext ctx;
    private Instrumentation instrumentation;
    private final Set<String> instrumentedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ==================== Watch Point Tools ====================

    @DebugTool(description = "Set a watch point (virtual breakpoint) on a method. When the method is called, its arguments, return value, and timing will be captured automatically. After setting this, ask the user to trigger the method, then use get_watch_results to see captured data.")
    public Map<String, Object> addWatchPoint(
            @ToolParam(description = "Fully qualified class name, e.g. 'com.example.service.OrderService'", required = true) String className,
            @ToolParam(description = "Method name to watch, e.g. 'createOrder'", required = true) String methodName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            installAgentIfNeeded();

            String wpId = className + "#" + methodName;
            String instrumentationKey = wpId;

            // Activate in holder (makes the advice record)
            WatchPointHolder.activate(wpId, className, methodName);

            // Only instrument once per class+method
            if (!instrumentedKeys.contains(instrumentationKey)) {
                new net.bytebuddy.agent.builder.AgentBuilder.Default()
                        .with(net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        .with(net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy.Default.REDEFINE)
                        .type(ElementMatchers.named(className).or(ElementMatchers.nameStartsWith(className + "$")))
                        .transform((builder, td, cl, module, pd) ->
                                builder.visit(Advice.to(WatchAdvice.class)
                                        .on(ElementMatchers.named(methodName)))
                        )
                        .installOn(instrumentation);

                instrumentedKeys.add(instrumentationKey);
                log.info("ByteBuddy instrumentation installed for {}.{}", className, methodName);
            } else {
                log.info("Watch point re-activated for {}.{} (instrumentation already in place)", className, methodName);
            }

            result.put("status", "active");
            result.put("watchPointId", wpId);
            result.put("message", "Watch point set on " + className + "." + methodName
                    + ". Trigger the method, then call get_watch_results to see captured data.");

        } catch (Exception e) {
            log.error("Failed to set watch point on {}.{}", className, methodName, e);
            result.put("error", e.getMessage());
            result.put("hint", "Make sure the class name is correct and the method exists. "
                    + "Use search_loaded_classes to find the correct class name.");
        }

        return result;
    }

    @DebugTool(description = "Get all captured invocations for a watch point. Returns the arguments, return value, and timing for each call that was made after the watch point was set.")
    public Map<String, Object> getWatchResults(
            @ToolParam(description = "Fully qualified class name", required = true) String className,
            @ToolParam(description = "Method name", required = true) String methodName
    ) {
        String wpId = className + "#" + methodName;
        Map<String, Object> result = new LinkedHashMap<>();

        List<WatchPointRecord> records = WatchPointHolder.getRecords(wpId);

        result.put("watchPointId", wpId);
        result.put("totalCapturedCalls", records.size());

        if (records.isEmpty()) {
            result.put("message", "No calls captured yet. Make sure the method has been invoked "
                    + "after the watch point was set. Ask the user to trigger the method.");
            return result;
        }

        List<Map<String, Object>> captures = new ArrayList<>();
        for (WatchPointRecord rec : records) {
            Map<String, Object> capture = new LinkedHashMap<>();
            capture.put("args", rec.getArgs());
            if (rec.getThrown() != null) {
                capture.put("thrown", rec.getThrown().getClass().getName()
                        + ": " + rec.getThrown().getMessage());
            } else {
                capture.put("returnValue", rec.getReturnValue());
            }
            capture.put("durationMs", rec.getDurationMs());
            captures.add(capture);
        }

        result.put("captures", captures);
        // Clear records after reading so subsequent calls get fresh data
        WatchPointHolder.clearRecords(wpId);
        return result;
    }

    @DebugTool(description = "List all currently active watch points.")
    public List<Map<String, Object>> listWatchPoints() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String wpId : WatchPointHolder.getActiveWatchPointIds()) {
            WatchPointHolder.WatchPointInfo info = WatchPointHolder.getWatchPointInfo(wpId);
            if (info != null) {
                Map<String, Object> wp = new LinkedHashMap<>();
                wp.put("watchPointId", wpId);
                wp.put("className", info.getClassName());
                wp.put("methodName", info.getMethodName());
                wp.put("activeSince", new Date(info.getCreatedAt()).toString());
                result.add(wp);
            }
        }
        return result;
    }

    @DebugTool(description = "Remove a watch point. The method will no longer be monitored.")
    public Map<String, Object> removeWatchPoint(
            @ToolParam(description = "Fully qualified class name", required = true) String className,
            @ToolParam(description = "Method name", required = true) String methodName
    ) {
        String wpId = className + "#" + methodName;
        WatchPointHolder.deactivate(wpId);
        WatchPointHolder.clearRecords(wpId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "removed");
        result.put("watchPointId", wpId);
        return result;
    }

    // ==================== Variable / Field Tools ====================

    @DebugTool(description = "Read the current value of a specific field on a Spring bean. Useful for inspecting runtime state of injected services.")
    public Map<String, Object> getBeanFieldValue(
            @ToolParam(description = "Bean name or simple class name", required = true) String beanName,
            @ToolParam(description = "Field name to read", required = true) String fieldName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object bean = resolveBean(beanName);

        if (bean == null) {
            result.put("error", "Bean not found: " + beanName);
            return result;
        }

        try {
            Class<?> clazz = bean.getClass();
            while (clazz != null && clazz != Object.class) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(bean);
                    result.put("bean", bean.getClass().getSimpleName());
                    result.put("field", fieldName);
                    result.put("type", field.getType().getSimpleName());
                    result.put("value", safeToString(value));
                    return result;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            result.put("error", "Field '" + fieldName + "' not found on bean " + beanName);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return result;
    }

    // ==================== Class Discovery ====================

    @DebugTool(description = "Search for loaded classes by name pattern. Use this to find the correct fully-qualified class name before setting a watch point.")
    public List<Map<String, Object>> searchLoadedClasses(
            @ToolParam(description = "Search pattern (case-insensitive partial match)", required = true) String pattern
    ) {
        installAgentIfNeeded();
        List<Map<String, Object>> result = new ArrayList<>();

        if (instrumentation != null) {
            Class<?>[] allClasses = instrumentation.getAllLoadedClasses();
            String lowerPattern = pattern.toLowerCase();
            int count = 0;

            for (Class<?> clazz : allClasses) {
                if (clazz.getName().toLowerCase().contains(lowerPattern)) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("className", clazz.getName());
                    info.put("simpleName", clazz.getSimpleName());
                    info.put("isInterface", clazz.isInterface());
                    info.put("isAnnotation", clazz.isAnnotation());

                    // List public methods (useful for setting watch points)
                    try {
                        List<String> methods = Arrays.stream(clazz.getDeclaredMethods())
                                .filter(m -> !m.isSynthetic())
                                .map(m -> m.getName() + "("
                                        + Arrays.stream(m.getParameterTypes())
                                                .map(Class::getSimpleName)
                                                .reduce((a, b) -> a + ", " + b)
                                                .orElse("")
                                        + ")")
                                .limit(20)
                                .toList();
                        info.put("methods", methods);
                    } catch (Exception ignored) {
                    }

                    result.add(info);
                    count++;
                    if (count >= 30) {
                        Map<String, Object> truncated = new LinkedHashMap<>();
                        truncated.put("note", "Results truncated. Refine your search pattern for more specific results.");
                        result.add(truncated);
                        break;
                    }
                }
            }
        }

        return result;
    }

    // ==================== Helpers ====================

    private synchronized void installAgentIfNeeded() {
        if (instrumentation == null) {
            log.info("Installing ByteBuddy agent into current JVM...");
            instrumentation = ByteBuddyAgent.install();
            log.info("ByteBuddy agent installed successfully.");
        }
    }

    private Object resolveBean(String nameOrType) {
        // Try by bean name
        try {
            return ctx.getBean(nameOrType);
        } catch (Exception ignored) {
        }
        // Try by simple type name
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

    private String safeToString(Object value) {
        if (value == null) return "null";
        String str = value.toString();
        if (str.length() > 500) {
            return str.substring(0, 500) + "... (truncated)";
        }
        return str;
    }
}
