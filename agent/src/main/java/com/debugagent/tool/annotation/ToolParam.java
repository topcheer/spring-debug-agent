package com.debugagent.tool.annotation;

import java.lang.annotation.*;

/**
 * Describes a parameter of a {@link DebugTool} method.
 * Generates the JSON Schema "properties" entry for the LLM.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {
    /**
     * Description of what this parameter does.
     */
    String description() default "";

    /**
     * Whether this parameter is required.
     */
    boolean required() default false;
}
