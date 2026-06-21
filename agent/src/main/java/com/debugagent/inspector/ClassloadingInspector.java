package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.*;

/**
 * Classloader inspection tools.
 * Shows classloader hierarchy, loaded class counts, and helps find class conflicts.
 */
public class ClassloadingInspector {

    @DebugTool(description = "Get classloader statistics: loaded class count, total loaded, unloaded, and classloader hierarchy.")
    public Map<String, Object> getClassloadingInfo() {
        Map<String, Object> info = new LinkedHashMap<>();

        try {
            ClassLoadingMXBean clBean = ManagementFactory.getClassLoadingMXBean();
            info.put("loadedClassCount", clBean.getLoadedClassCount());
            info.put("totalLoadedClassCount", clBean.getTotalLoadedClassCount());
            info.put("unloadedClassCount", clBean.getUnloadedClassCount());
            info.put("verboseClassLoading", clBean.isVerbose());

            // Classloader hierarchy
            List<Map<String, String>> hierarchy = new ArrayList<>();
            ClassLoader cl = this.getClass().getClassLoader();
            while (cl != null) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("classLoader", cl.getClass().getName());
                entry.put("toString", ReflectionHelper.safeToString(cl));
                hierarchy.add(entry);
                cl = cl.getParent();
            }

            // Add the bootstrap classloader (null parent)
            Map<String, String> bootstrap = new LinkedHashMap<>();
            bootstrap.put("classLoader", "Bootstrap ClassLoader (JVM internal)");
            hierarchy.add(bootstrap);

            info.put("classLoaderHierarchy", hierarchy);

        } catch (Exception e) {
            info.put("error", "Failed to get classloading info: " + e.getMessage());
        }

        return info;
    }

    @DebugTool(description = "Find where a class is loaded from (JAR file path). Useful for detecting class conflicts or duplicate libraries on classpath.")
    public Map<String, Object> findClassLocation(
            @ToolParam(description = "Fully qualified class name, e.g. org.springframework.context.ApplicationContext") String className
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            result.put("className", className);
            result.put("classLoader", clazz.getClassLoader() != null
                    ? clazz.getClassLoader().getClass().getName()
                    : "Bootstrap ClassLoader");
            result.put("protectionDomain", clazz.getProtectionDomain() != null
                    ? ReflectionHelper.safeToString(clazz.getProtectionDomain().getCodeSource())
                    : "unknown");

            // Try to get the actual URL
            if (clazz.getProtectionDomain() != null && clazz.getProtectionDomain().getCodeSource() != null) {
                java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
                URL location = cs.getLocation();
                if (location != null) {
                    result.put("location", location.toString());
                }
            }
        } catch (ClassNotFoundException e) {
            result.put("className", className);
            result.put("error", "Class not found on classpath");
        } catch (Exception e) {
            result.put("error", "Failed: " + e.getMessage());
        }

        return result;
    }
}
