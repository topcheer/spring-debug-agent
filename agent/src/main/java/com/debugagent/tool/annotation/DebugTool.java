package com.debugagent.tool.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a diagnostic tool callable by the LLM.
 *
 * The method's return value will be serialized to JSON and sent back to the LLM.
 *
 * Example:
 *   @DebugTool(description = "Get JVM thread dump, including stack traces and thread states")
 *   public String getThreadDump(@ToolParam(description = "Include full stack traces") boolean includeStack) {
 *       ...
 *   }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DebugTool {
    /**
     * Human-readable description of what the tool does.
     * This is sent to the LLM so it knows when to call this tool.
     */
    String description();

    /**
     * Tool name. Defaults to the method name with underscores.
     */
    String name() default "";
}
