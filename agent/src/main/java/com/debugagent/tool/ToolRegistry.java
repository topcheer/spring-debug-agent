package com.debugagent.tool;

import com.debugagent.llm.model.ToolDefinition;
import com.debugagent.tool.annotation.DebugTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all available diagnostic tools.
 *
 * Scans Spring beans for @DebugTool methods and registers them.
 * Provides lookup by name and exposes tool definitions for the LLM.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolMethodInfo> tools = new ConcurrentHashMap<>();

    /**
     * Scan an object for @DebugTool methods and register them.
     */
    public void register(Object bean) {
        Class<?> clazz = bean.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            DebugTool annotation = method.getAnnotation(DebugTool.class);
            if (annotation != null) {
                method.setAccessible(true);
                ToolMethodInfo info = ToolMethodInfo.from(method, bean);
                tools.put(info.getName(), info);
                log.info("Registered debug tool: {} ({})",
                        info.getName(), info.getDescription());
            }
        }
    }

    /**
     * Get a registered tool by name.
     */
    public ToolMethodInfo getTool(String name) {
        return tools.get(name);
    }

    /**
     * Check if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * Get all tool definitions for sending to the LLM.
     */
    public List<ToolDefinition> getAllToolDefinitions() {
        return tools.values().stream()
                .map(ToolMethodInfo::getToolDefinition)
                .toList();
    }

    /**
     * Get all registered tool names.
     */
    public Set<String> getAllToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Number of registered tools.
     */
    public int size() {
        return tools.size();
    }

    /**
     * Alias for size() — number of registered tools.
     */
    public int toolCount() {
        return tools.size();
    }
}
