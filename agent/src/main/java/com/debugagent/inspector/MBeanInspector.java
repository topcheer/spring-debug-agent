package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * JMX MBean browser.
 * Lists and inspects all registered MBeans in the platform MBean server.
 * This covers Tomcat, HikariCP, logging, JPA, JVM internals, and more.
 */
@Component
public class MBeanInspector {

    private final MBeanServer mbeanServer;

    public MBeanInspector() {
        this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
    }

    @DebugTool(description = "List all registered JMX MBeans grouped by domain. Returns ObjectName, description, and attribute count. Useful for discovering what components (Tomcat, HikariCP, Hibernate, etc.) are registered.")
    public Map<String, Object> listMBeans(
            @ToolParam(description = "Filter by domain name (e.g., 'com.zaxxer.hikari', 'org.apache.tomcat'). Leave empty for all.") String domainFilter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Set<String> domains = new TreeSet<>(Arrays.asList(mbeanServer.getDomains()));
            Map<String, List<Map<String, Object>>> domainMap = new TreeMap<>();

            for (String domain : domains) {
                if (domainFilter != null && !domainFilter.isBlank()
                        && !domain.toLowerCase().contains(domainFilter.toLowerCase())) {
                    continue;
                }

                Set<ObjectName> names = mbeanServer.queryNames(new ObjectName(domain + ":*"), null);
                List<Map<String, Object>> mbeans = new ArrayList<>();

                for (ObjectName name : names) {
                    Map<String, Object> mbeanInfo = new LinkedHashMap<>();
                    mbeanInfo.put("objectName", name.toString());
                    mbeanInfo.put("domain", name.getDomain());
                    mbeanInfo.put("type", name.getKeyProperty("type"));
                    try {
                        MBeanInfo info = mbeanServer.getMBeanInfo(name);
                        mbeanInfo.put("description", info.getDescription());
                        mbeanInfo.put("attributeCount", info.getAttributes().length);
                        mbeanInfo.put("operationCount", info.getOperations().length);
                    } catch (Exception e) {
                        mbeanInfo.put("error", e.getMessage());
                    }
                    mbeans.add(mbeanInfo);
                }

                if (!mbeans.isEmpty()) {
                    domainMap.put(domain, mbeans);
                }
            }

            result.put("domains", domainMap);
            result.put("totalDomains", domainMap.size());
            result.put("totalMBeans", domainMap.values().stream().mapToInt(List::size).sum());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Read all attributes of a specific JMX MBean. Use list_mbeans first to find the ObjectName. Returns attribute name, type, and current value.")
    public Map<String, Object> getMBeanAttributes(
            @ToolParam(description = "MBean ObjectName, e.g., 'com.zaxxer.hikari:type=Pool (HikariPool-1)'", required = true) String objectName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ObjectName name = new ObjectName(objectName);
            MBeanInfo info = mbeanServer.getMBeanInfo(name);
            List<Map<String, Object>> attrs = new ArrayList<>();

            for (MBeanAttributeInfo attrInfo : info.getAttributes()) {
                Map<String, Object> attr = new LinkedHashMap<>();
                attr.put("name", attrInfo.getName());
                attr.put("type", attrInfo.getType());
                attr.put("readable", attrInfo.isReadable());
                attr.put("writable", attrInfo.isWritable());
                attr.put("description", attrInfo.getDescription());

                if (attrInfo.isReadable()) {
                    try {
                        Object value = mbeanServer.getAttribute(name, attrInfo.getName());
                        attr.put("value", formatValue(value));
                    } catch (Exception e) {
                        attr.put("value", "<error: " + e.getClass().getSimpleName() + ">");
                    }
                }
                attrs.add(attr);
            }

            result.put("objectName", objectName);
            result.put("className", info.getClassName());
            result.put("description", info.getDescription());
            result.put("attributes", attrs);
        } catch (Exception e) {
            result.put("error", "Failed to read MBean: " + e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Read a single attribute value from a JMX MBean. Faster than get_mbean_attributes when you know exactly what you need.")
    public Map<String, Object> getMBeanAttribute(
            @ToolParam(description = "MBean ObjectName", required = true) String objectName,
            @ToolParam(description = "Attribute name to read", required = true) String attributeName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ObjectName name = new ObjectName(objectName);
            Object value = mbeanServer.getAttribute(name, attributeName);
            result.put("objectName", objectName);
            result.put("attribute", attributeName);
            result.put("value", formatValue(value));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Invoke an operation on a JMX MBean. Use list_mbeans to discover available operations. Parameters are passed as a JSON array of strings.")
    public Map<String, Object> invokeMBeanOperation(
            @ToolParam(description = "MBean ObjectName", required = true) String objectName,
            @ToolParam(description = "Operation name to invoke", required = true) String operationName,
            @ToolParam(description = "JSON array of string parameters, e.g. [\"arg1\",\"arg2\"]. Leave empty for no-arg operations.") String paramsJson
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ObjectName name = new ObjectName(objectName);
            MBeanInfo info = mbeanServer.getMBeanInfo(name);

            // Parse params
            String[] paramStrings = parseParams(paramsJson);
            Object[] params = new Object[paramStrings.length];
            String[] signature = new String[paramStrings.length];

            // Find matching operation
            MBeanOperationInfo matchedOp = null;
            for (MBeanOperationInfo op : info.getOperations()) {
                if (op.getName().equals(operationName) && op.getSignature().length == paramStrings.length) {
                    matchedOp = op;
                    for (int i = 0; i < op.getSignature().length; i++) {
                        signature[i] = op.getSignature()[i].getType();
                        params[i] = convertParam(paramStrings[i], signature[i]);
                    }
                    break;
                }
            }

            if (matchedOp == null) {
                result.put("error", "Operation '" + operationName + "' with " + paramStrings.length + " params not found");
                result.put("availableOperations", Arrays.stream(info.getOperations())
                        .map(op -> op.getName() + "(" + op.getSignature().length + " params)")
                        .toArray());
                return result;
            }

            Object ret = mbeanServer.invoke(name, operationName, params, signature);
            result.put("operation", operationName);
            result.put("returnType", matchedOp.getReturnType());
            result.put("returnValue", formatValue(ret));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Helpers ====================

    private String[] parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) return new String[0];
        try {
            // Simple JSON array parse without external dependencies
            String trimmed = paramsJson.trim();
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return new String[]{paramsJson};
            if (trimmed.equals("[]")) return new String[0];
            String inner = trimmed.substring(1, trimmed.length() - 1);
            // Split by comma, handle quoted strings
            List<String> parts = new ArrayList<>();
            boolean inQuote = false;
            StringBuilder sb = new StringBuilder();
            for (char c : inner.toCharArray()) {
                if (c == '"') { inQuote = !inQuote; continue; }
                if (c == ',' && !inQuote) {
                    parts.add(sb.toString().trim().replaceAll("^\"|\"$", ""));
                    sb = new StringBuilder();
                } else {
                    sb.append(c);
                }
            }
            if (sb.length() > 0) parts.add(sb.toString().trim().replaceAll("^\"|\"$", ""));
            return parts.toArray(new String[0]);
        } catch (Exception e) {
            return new String[]{paramsJson};
        }
    }

    private Object convertParam(String value, String type) {
        if (value == null) return null;
        try {
            return switch (type) {
                case "int", "java.lang.Integer" -> Integer.parseInt(value);
                case "long", "java.lang.Long" -> Long.parseLong(value);
                case "boolean", "java.lang.Boolean" -> Boolean.parseBoolean(value);
                case "double", "java.lang.Double" -> Double.parseDouble(value);
                case "float", "java.lang.Float" -> Float.parseFloat(value);
                default -> value;
            };
        } catch (Exception e) {
            return value;
        }
    }

    private Object formatValue(Object value) {
        if (value == null) return "null";
        if (value.getClass().isArray()) {
            try {
                int len = java.lang.reflect.Array.getLength(value);
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < Math.min(len, 100); i++) {
                    Object elem = java.lang.reflect.Array.get(value, i);
                    list.add(elem != null ? elem.toString() : "null");
                }
                if (len > 100) list.add("... " + (len - 100) + " more");
                return list;
            } catch (Exception e) {
                return value.toString();
            }
        }
        String s = value.toString();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
