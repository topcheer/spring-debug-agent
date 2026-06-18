package com.debugagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Executes a tool by name, converting JSON arguments to Java method parameters
 * and serializing the result back to a JSON string for the LLM.
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Execute a tool call.
     *
     * @param toolName   the tool name (snake_case)
     * @param arguments  JSON string of arguments from the LLM
     * @return JSON string result to feed back to the LLM
     */
    public String execute(String toolName, String arguments) {
        ToolMethodInfo toolInfo = registry.getTool(toolName);
        if (toolInfo == null) {
            return errorJson("Unknown tool: " + toolName);
        }

        try {
            Method method = toolInfo.getMethod();
            Parameter[] params = method.getParameters();
            Object[] args = new Object[params.length];

            // Parse arguments JSON
            JsonNode argsNode = (arguments == null || arguments.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(arguments);

            for (int i = 0; i < params.length; i++) {
                Parameter param = params[i];
                String paramName = camelToSnake(param.getName());
                JsonNode valueNode = argsNode.get(paramName);

                if (valueNode != null && !valueNode.isNull()) {
                    args[i] = convertValue(valueNode, param.getType());
                } else {
                    args[i] = getDefaultValue(param.getType());
                }
            }

            log.info("Executing tool: {} with args: {}", toolName, arguments);
            Object result = method.invoke(toolInfo.getBean(), args);

            String jsonResult = objectMapper.writeValueAsString(result);
            // Truncate very long results to avoid blowing up the context
            if (jsonResult.length() > 8000) {
                jsonResult = jsonResult.substring(0, 8000) + "\n... (truncated, total "
                        + jsonResult.length() + " chars)";
            }
            log.info("Tool {} returned {} chars", toolName, jsonResult.length());
            return jsonResult;

        } catch (Exception e) {
            log.error("Error executing tool: " + toolName, e);
            return errorJson("Error executing tool " + toolName + ": " + e.getMessage());
        }
    }

    private Object convertValue(JsonNode node, Class<?> targetType) {
        if (targetType == String.class) {
            return node.isValueNode() ? node.asText() : node.toString();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return node.asInt();
        }
        if (targetType == long.class || targetType == Long.class) {
            return node.asLong();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return node.asBoolean();
        }
        if (targetType == double.class || targetType == Double.class) {
            return node.asDouble();
        }
        if (targetType == float.class || targetType == Float.class) {
            return (float) node.asDouble();
        }
        // Complex types → try Jackson conversion
        try {
            return objectMapper.treeToValue(node, targetType);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        return null;
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "\\\"") + "\"}";
    }

    private static String camelToSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
