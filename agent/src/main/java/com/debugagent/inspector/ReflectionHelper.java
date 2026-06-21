package com.debugagent.inspector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Utility for safe reflective access to optional beans and their internals.
 *
 * All methods catch exceptions and return null/empty — never throw.
 * This lets inspectors degrade gracefully when a library isn't present.
 */
public final class ReflectionHelper {

    private static final Logger log = LoggerFactory.getLogger(ReflectionHelper.class);

    private ReflectionHelper() {}

    /**
     * Safely get a bean by name, returning null if it doesn't exist.
     */
    public static Object getBeanSafely(ApplicationContext ctx, String name) {
        try {
            if (ctx.containsBean(name)) {
                return ctx.getBean(name);
            }
        } catch (Exception e) {
            log.debug("Failed to get bean '{}': {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * Safely get beans by type name (string), returning empty list if type not on classpath.
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getBeansOfType(ApplicationContext ctx, String className) {
        Class<?> clazz = resolveClass(className, ctx);
        if (clazz == null) return Collections.emptyList();
        try {
            Map<String, ?> beans = ctx.getBeansOfType((Class<Object>) clazz);
            return new ArrayList<>((Collection<T>) beans.values());
        } catch (Exception e) {
            log.debug("getBeansOfType failed for {}: {}", className, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Resolve a class by name using multiple classloaders (app context, current thread,
     * this class, system) to handle fat-jar and multi-classloader scenarios.
     */
    public static Class<?> resolveClass(String className, ApplicationContext ctx) {
        ClassLoader[] loaders = new ClassLoader[] {
            ctx != null ? ctx.getClassLoader() : null,
            Thread.currentThread().getContextClassLoader(),
            ReflectionHelper.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                return Class.forName(className, false, cl);
            } catch (Throwable ignored) {}
        }
        log.debug("Type {} not available in any classloader", className);
        return null;
    }

    /**
     * Get a bean of type by class name, returning the first match or null.
     */
    public static Object getFirstBeanOfType(ApplicationContext ctx, String className) {
        List<?> beans = getBeansOfType(ctx, className);
        return beans.isEmpty() ? null : beans.get(0);
    }

    /**
     * Safely invoke a no-arg method by name on an object.
     */
    public static Object invokeMethod(Object target, String methodName) {
        if (target == null) return null;
        try {
            // First try getMethods() which includes all public interface methods
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m.invoke(target);
                }
            }
            // Fallback to findMethod (searches declared + inherited)
            Method m = findMethod(target.getClass(), methodName);
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(target);
            }
        } catch (Exception e) {
            log.debug("Failed to invoke '{}' on {}: {}", methodName, target.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    /**
     * Safely invoke a method by name with arguments on an object.
     * Args are matched by count — the first method with the right name and parameter count is used.
     */
    public static Object invokeMethod(Object target, String methodName, Object... args) {
        if (target == null) return null;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
            // Also search declared methods (including non-public)
            Class<?> current = target.getClass();
            while (current != null) {
                for (Method m : current.getDeclaredMethods()) {
                    if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Exception e) {
            log.debug("Failed to invoke '{}' on {}: {}", methodName, target.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    /**
     * Find a method by name in the class hierarchy (including interfaces).
     */
    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                // Try without param types
                if (paramTypes.length == 0) {
                    for (Method m : current.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                            return m;
                        }
                    }
                    // Also search interfaces for default methods
                    for (Class<?> iface : current.getInterfaces()) {
                        for (Method m : iface.getMethods()) {
                            if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                                return m;
                            }
                        }
                    }
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Safely read a field value by name from an object (searches superclasses).
     */
    public static Object getFieldValue(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Exception e) {
                log.debug("Failed to read field '{}' on {}: {}", fieldName, target.getClass().getSimpleName(), e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Check if a class is available on the classpath.
     */
    public static boolean isClassAvailable(String className) {
        return isClassAvailable(className, null);
    }

    /**
     * Check if a class is available on the classpath (using the given context's ClassLoader if provided).
     */
    public static boolean isClassAvailable(String className, ApplicationContext ctx) {
        // Try multiple classloaders in order of preference
        ClassLoader[] loaders = new ClassLoader[] {
            ctx != null ? ctx.getClassLoader() : null,
            Thread.currentThread().getContextClassLoader(),
            ReflectionHelper.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class.forName(className, false, cl);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Convert a value to a safe string representation.
     */
    public static String safeToString(Object obj) {
        if (obj == null) return "null";
        try {
            String s = obj.toString();
            return s.length() > 500 ? s.substring(0, 500) + "..." : s;
        } catch (Exception e) {
            return "[toString failed: " + e.getClass().getSimpleName() + "]";
        }
    }
}
