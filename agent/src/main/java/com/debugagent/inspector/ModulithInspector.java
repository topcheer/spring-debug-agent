package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Modulith diagnostic tools.
 * Uses reflection on ApplicationModules — spring-modulith-core is optional at runtime.
 */
public class ModulithInspector implements ApplicationContextAware {

    private static final String APP_MODULES_CLASS =
            "org.springframework.modulith.core.ApplicationModules";
    private static final String MODULE_CLASS =
            "org.springframework.modulith.core.ApplicationModule";

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all Spring Modulith application modules: name, base package, contained types, and whether the module is open. Returns a note when Modulith is not on the classpath.")
    public List<Map<String, Object>> getModules() {
        List<Map<String, Object>> results = new ArrayList<>();

        Object modules = resolveApplicationModules();
        if (modules == null) {
            results.add(Map.of("error", "Spring Modulith not on classpath or no modules detected. " +
                    "Add spring-modulith-core and ensure @SpringBootApplication class is in a base package."));
            return results;
        }

        // Iterate modules via stream()/iterator()
        Object bootstrap = ReflectionHelper.invokeMethod(modules, "bootstrapMode");
        // Stream<ApplicationModule>
        Iterator<?> it = streamIterator(modules);
        if (it == null) {
            results.add(Map.of("error", "ApplicationModules instance could not be iterated."));
            return results;
        }

        while (it.hasNext()) {
            Object module = it.next();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", ReflectionHelper.invokeMethod(module, "getName"));
            info.put("basePackage", ReflectionHelper.invokeMethod(module, "getBasePackage"));
            info.put("isOpen", ReflectionHelper.invokeMethod(module, "isOpen"));
            Object displayName = ReflectionHelper.invokeMethod(module, "getDisplayName");
            if (displayName != null) info.put("displayName", displayName);

            // Contained types via getNamedInterfaces() / SpringTypes / etc. — best effort
            try {
                Object namedInterfaces = ReflectionHelper.invokeMethod(module, "getNamedInterfaces");
                if (namedInterfaces != null) {
                    info.put("namedInterfaces", ReflectionHelper.safeToString(namedInterfaces));
                }
            } catch (Exception ignored) {}

            // Module dependencies
            Object deps = ReflectionHelper.invokeMethod(module, "getDirectDependencies");
            if (deps != null) {
                List<String> depNames = toStringCollection(deps);
                if (!depNames.isEmpty()) info.put("directDependencies", depNames);
            }

            results.add(info);
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No application modules detected."));
        }

        return results;
    }

    @DebugTool(description = "Run Spring Modulith boundary verification and report any violations (e.g. modules referencing internal types of other modules).")
    public Map<String, Object> verifyModuleBoundaries() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object modules = resolveApplicationModules();
        if (modules == null) {
            result.put("error", "Spring Modulith not on classpath or no modules detected.");
            return result;
        }

        result.put("moduleCount", countModules(modules));

        // ApplicationModules.verify() throws VerificationException on failure
        try {
            try {
                Method verify = findNoArgMethod(modules.getClass(), "verify");
                if (verify == null) {
                    result.put("error", "verify() method not found on ApplicationModules");
                    return result;
                }
                verify.setAccessible(true);
                verify.invoke(modules);
                result.put("verified", true);
                result.put("status", "All module boundaries satisfied.");
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause == null) cause = ite;
                result.put("verified", false);
                result.put("violationType", cause.getClass().getSimpleName());
                result.put("violationMessage", cause.getMessage());
                result.put("status", "Module boundary violations detected.");

                // VerificationException.getVerificationFailedDescriptions() / messages
                Object descriptions = null;
                try {
                    Method m = findNoArgMethod(cause.getClass(), "getVerificationFailedDescriptions");
                    if (m == null) {
                        for (Method mm : cause.getClass().getMethods()) {
                            if (mm.getName().toLowerCase().contains("description")
                                    && mm.getParameterCount() == 0) {
                                m = mm;
                                break;
                            }
                        }
                    }
                    if (m != null) {
                        m.setAccessible(true);
                        descriptions = m.invoke(cause);
                    }
                } catch (Exception ignored) {}

                if (descriptions instanceof Iterable<?> iter) {
                    List<String> list = new ArrayList<>();
                    for (Object d : iter) list.add(ReflectionHelper.safeToString(d));
                    result.put("violations", list);
                } else if (descriptions != null) {
                    result.put("violations", ReflectionHelper.safeToString(descriptions));
                }
            }
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Return the Spring Modulith dependency graph: which module depends on which other modules, directly and transitively.")
    public Map<String, Object> getModuleDependencies() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object modules = resolveApplicationModules();
        if (modules == null) {
            result.put("error", "Spring Modulith not on classpath or no modules detected.");
            return result;
        }

        Map<String, List<String>> graph = new LinkedHashMap<>();
        Iterator<?> it = streamIterator(modules);
        if (it == null) {
            result.put("error", "ApplicationModules instance could not be iterated.");
            return result;
        }

        while (it.hasNext()) {
            Object module = it.next();
            Object nameObj = ReflectionHelper.invokeMethod(module, "getName");
            String name = nameObj != null ? nameObj.toString() : "unknown";
            Object deps = ReflectionHelper.invokeMethod(module, "getDirectDependencies");
            graph.put(name, toStringCollection(deps));
        }

        result.put("graph", graph);
        result.put("moduleCount", graph.size());
        return result;
    }

    // ==================== Helpers ====================

    private Object resolveApplicationModules() {
        if (!ReflectionHelper.isClassAvailable(APP_MODULES_CLASS)) return null;

        // Try an ApplicationModules bean first
        Object bean = ReflectionHelper.getFirstBeanOfType(ctx, APP_MODULES_CLASS);
        if (bean != null) return bean;

        // Otherwise build via ApplicationModules.of(ctx)
        try {
            Class<?> clazz = Class.forName(APP_MODULES_CLASS);
            Method of = null;
            for (Method m : clazz.getMethods()) {
                if ("of".equals(m.getName()) && m.getParameterCount() == 1) {
                    Class<?> param = m.getParameterTypes()[0];
                    if (param.isAssignableFrom(ctx.getClass())
                            || param.isAssignableFrom(ApplicationContext.class)) {
                        of = m;
                        break;
                    }
                }
            }
            if (of == null) {
                for (Method m : clazz.getMethods()) {
                    if ("of".equals(m.getName()) && m.getParameterCount() == 1) {
                        of = m;
                        break;
                    }
                }
            }
            if (of == null) return null;
            of.setAccessible(true);
            return of.invoke(null, ctx);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Iterator<?> streamIterator(Object modules) {
        try {
            Method stream = findNoArgMethod(modules.getClass(), "stream");
            if (stream == null) return null;
            stream.setAccessible(true);
            Object streamObj = stream.invoke(modules);
            if (streamObj == null) return null;
            Method iterator = streamObj.getClass().getMethod("iterator");
            iterator.setAccessible(true);
            Object iter = iterator.invoke(streamObj);
            if (iter instanceof Iterator<?> i) return i;
        } catch (Exception ignored) {}
        return null;
    }

    private int countModules(Object modules) {
        Iterator<?> it = streamIterator(modules);
        if (it == null) return 0;
        int count = 0;
        while (it.hasNext()) { it.next(); count++; }
        return count;
    }

    private static Method findNoArgMethod(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static List<String> toStringCollection(Object value) {
        if (value == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iter) {
            for (Object o : iter) result.add(o == null ? "null" : o.toString());
        } else if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                Object o = java.lang.reflect.Array.get(value, i);
                result.add(o == null ? "null" : o.toString());
            }
        } else {
            result.add(value.toString());
        }
        return result;
    }
}
