package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Spring bean dependency graph inspector.
 * Analyzes bean creation order, circular dependencies, lazy beans,
 * and provides deep dependency graph analysis.
 */
@Component
public class BeanGraphInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get bean creation/initialization order. Beans are listed in the order they were defined in the BeanDefinition registry. Useful for understanding startup sequence and finding beans that fail to initialize early.")
    public Map<String, Object> getBeanCreationOrder(
            @ToolParam(description = "Filter by bean name prefix or type. Leave empty for all.") String filter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String[] names = ctx.getBeanDefinitionNames();
            List<Map<String, Object>> beans = new ArrayList<>();

            for (int i = 0; i < names.length; i++) {
                if (filter != null && !filter.isBlank()
                        && !names[i].toLowerCase().contains(filter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> b = new LinkedHashMap<>();
                b.put("order", i + 1);
                b.put("name", names[i]);

                try {
                    Class<?> type = ctx.getType(names[i]);
                    b.put("type", type != null ? type.getSimpleName() : "unknown");
                    b.put("package", type != null ? type.getPackageName() : "");
                } catch (Exception ignored) {}

                b.put("scope", getScope(names[i]));
                b.put("lazy", isLazy(names[i]));
                beans.add(b);
            }

            result.put("beans", beans);
            result.put("totalAnalyzed", beans.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Detect potential circular dependencies in the Spring container. Traverses bean dependency graph and reports any cycles found.")
    public Map<String, Object> getCircularReferences() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ConfigurableListableBeanFactory factory =
                    (ConfigurableListableBeanFactory) ctx.getAutowireCapableBeanFactory();

            String[] names = ctx.getBeanDefinitionNames();
            List<List<String>> cycles = new ArrayList<>();
            Set<String> visited = new HashSet<>();

            for (String name : names) {
                Set<String> path = new LinkedHashSet<>();
                detectCycle(factory, name, path, cycles, visited, 20);
            }

            result.put("cycleCount", cycles.size());
            if (cycles.isEmpty()) {
                result.put("message", "No circular dependencies detected.");
            } else {
                // Deduplicate cycles
                Set<String> seen = new HashSet<>();
                List<List<String>> unique = new ArrayList<>();
                for (List<String> cycle : cycles) {
                    String key = String.join("->", cycle);
                    if (!seen.contains(key)) {
                        seen.add(key);
                        unique.add(cycle);
                    }
                }
                result.put("cycles", unique);
                result.put("uniqueCycleCount", unique.size());
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "List all lazy-initialized beans (@Lazy) and beans that have not yet been instantiated (prototype beans or lazy beans not yet requested).")
    public Map<String, Object> getLazyBeans() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> lazyBeans = new ArrayList<>();
            List<Map<String, Object>> notInstantiated = new ArrayList<>();

            String[] names = ctx.getBeanDefinitionNames();
            for (String name : names) {
                boolean isLazy = isLazy(name);
                boolean isSingleton = "singleton".equals(getScope(name));

                // Check if actually instantiated
                boolean instantiated = true;
                if (isSingleton) {
                    try {
                        instantiated = ctx.getBean(name) != null;
                    } catch (Exception e) {
                        instantiated = false;
                    }
                }

                if (isLazy) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("name", name);
                    b.put("type", safeGetType(name));
                    b.put("instantiated", instantiated);
                    lazyBeans.add(b);
                }

                // For singletons that are not yet created
                if (isSingleton && !instantiated) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("name", name);
                    b.put("type", safeGetType(name));
                    b.put("reason", "Singleton not yet initialized");
                    notInstantiated.add(b);
                }
            }

            result.put("lazyBeans", lazyBeans);
            result.put("lazyCount", lazyBeans.size());

            if (!notInstantiated.isEmpty()) {
                result.put("notInstantiated", notInstantiated);
                result.put("notInstantiatedCount", notInstantiated.size());
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Helpers ====================

    private void detectCycle(ConfigurableListableBeanFactory factory,
                             String beanName, Set<String> path,
                             List<List<String>> cycles, Set<String> globalVisited,
                             int maxDepth) {
        if (path.contains(beanName)) {
            // Found a cycle — extract the cycle path
            List<String> cycle = new ArrayList<>();
            boolean inCycle = false;
            for (String p : path) {
                if (p.equals(beanName)) inCycle = true;
                if (inCycle) cycle.add(p);
            }
            cycle.add(beanName);
            cycles.add(cycle);
            return;
        }

        if (maxDepth <= 0) return;

        path.add(beanName);
        try {
            String[] deps = factory.getDependenciesForBean(beanName);
            for (String dep : deps) {
                detectCycle(factory, dep, path, cycles, globalVisited, maxDepth - 1);
            }
        } catch (Exception ignored) {}
        path.remove(beanName);
    }

    private boolean isLazy(String beanName) {
        try {
            ConfigurableListableBeanFactory factory =
                    (ConfigurableListableBeanFactory) ctx.getAutowireCapableBeanFactory();
            return factory.getBeanDefinition(beanName).isLazyInit();
        } catch (Exception e) {
            return false;
        }
    }

    private String getScope(String beanName) {
        try {
            if (ctx.isSingleton(beanName)) return "singleton";
            if (ctx.isPrototype(beanName)) return "prototype";
            return "custom";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String safeGetType(String name) {
        try {
            Class<?> type = ctx.getType(name);
            return type != null ? type.getSimpleName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
