package com.debugagent.tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.debugagent.llm.model.ToolDefinition;
import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;

/**
 * Metadata for a single tool method, including the reflective method handle
 * and the JSON Schema definition for the LLM.
 */
public class ToolMethodInfo {

    private final String name;
    private final String description;
    private final Method method;
    private final Object bean;
    private final ToolDefinition toolDefinition;

    public ToolMethodInfo(String name, String description, Method method, Object bean,
                          ToolDefinition toolDefinition) {
        this.name = name;
        this.description = description;
        this.method = method;
        this.bean = bean;
        this.toolDefinition = toolDefinition;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Method getMethod() { return method; }
    public Object getBean() { return bean; }
    public ToolDefinition getToolDefinition() { return toolDefinition; }

    /**
     * Build a ToolMethodInfo from an annotated method.
     */
    public static ToolMethodInfo from(Method method, Object bean) {
        DebugTool annotation = method.getAnnotation(DebugTool.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Method " + method + " is not annotated with @DebugTool");
        }

        String name = annotation.name().isEmpty()
                ? camelToSnake(method.getName())
                : annotation.name();
        String description = annotation.description();

        // Build JSON Schema for parameters
        Map<String, Object> schema = buildParameterSchema(method);

        ToolDefinition toolDef = new ToolDefinition(name, description, schema);
        return new ToolMethodInfo(name, description, method, bean, toolDef);
    }

    /**
     * Build a JSON Schema "parameters" object for the method's parameters.
     */
    private static Map<String, Object> buildParameterSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Parameter[] params = method.getParameters();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : params) {
            ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);
            String paramName = camelToSnake(param.getName());
            String paramDesc = paramAnnotation != null ? paramAnnotation.description() : "";
            boolean isRequired = paramAnnotation != null && paramAnnotation.required();

            Map<String, Object> propSchema = new LinkedHashMap<>();
            propSchema.put("type", jsonType(param.getType()));
            propSchema.put("description", paramDesc);
            if (param.getType() == boolean.class || param.getType() == Boolean.class) {
                propSchema.put("type", "boolean");
            }
            properties.put(paramName, propSchema);

            if (isRequired) {
                required.add(paramName);
            }
        }

        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static String jsonType(Class<?> javaType) {
        if (javaType == int.class || javaType == Integer.class
                || javaType == long.class || javaType == Long.class
                || javaType == short.class || javaType == Short.class) {
            return "integer";
        }
        if (javaType == float.class || javaType == Float.class
                || javaType == double.class || javaType == Double.class) {
            return "number";
        }
        if (javaType == boolean.class || javaType == Boolean.class) {
            return "boolean";
        }
        // Everything else (String, List, Map, etc.) → string
        return "string";
    }

    private static String camelToSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
