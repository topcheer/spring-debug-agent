package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Alibaba Nacos diagnostic tools.
 * Inspects Nacos service discovery, configuration management, namespaces, and health.
 * Conditional on spring-cloud-starter-alibaba-nacos-discovery or nacos-client being on classpath.
 */
public class NacosInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all Nacos registered services and their instances: service name, "
            + "instance IP:port, healthy status, weight, cluster name, and metadata. "
            + "Useful for diagnosing service registration failures or instance not discovered.")
    public List<Map<String, Object>> getNacosServices() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object namingService = getNamingService();
        if (namingService == null) {
            result.add(Map.of("error", "No Nacos NamingService found. Add spring-cloud-starter-alibaba-nacos-discovery."));
            return result;
        }

        try {
            // Get all services from naming service
            Object listView = ReflectionHelper.invokeMethod(namingService, "getServicesOfServer",
                    1, Integer.MAX_VALUE);
            if (listView != null) {
                Object data = ReflectionHelper.invokeMethod(listView, "getData");
                if (data instanceof List) {
                    for (Object serviceName : (List<?>) data) {
                        Map<String, Object> svc = new LinkedHashMap<>();
                        svc.put("name", serviceName.toString());

                        // Get instances for this service
                        try {
                            Object instances = ReflectionHelper.invokeMethod(namingService,
                                    "getAllInstances", serviceName.toString());
                            if (instances instanceof List) {
                                List<Map<String, Object>> instanceList = new ArrayList<>();
                                for (Object inst : (List<?>) instances) {
                                    Map<String, Object> i = new LinkedHashMap<>();
                                    i.put("ip", ReflectionHelper.invokeMethod(inst, "getIp"));
                                    i.put("port", ReflectionHelper.invokeMethod(inst, "getPort"));
                                    i.put("healthy", ReflectionHelper.invokeMethod(inst, "isHealthy"));
                                    i.put("weight", ReflectionHelper.invokeMethod(inst, "getWeight"));
                                    Object cluster = ReflectionHelper.invokeMethod(inst, "getClusterName");
                                    if (cluster != null) i.put("cluster", cluster);
                                    Object metadata = ReflectionHelper.invokeMethod(inst, "getMetadata");
                                    if (metadata instanceof Map) i.put("metadata", metadata);
                                    instanceList.add(i);
                                }
                                svc.put("instances", instanceList);
                            }
                        } catch (Exception ignored) {}

                        result.add(svc);
                    }
                }
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_services",
                    "hint", "No registered services found or Nacos server not connected."));
        }

        return result;
    }

    @DebugTool(description = "Get Nacos configuration by dataId, group, and namespace. "
            + "Returns the raw configuration content, type (yaml/properties/json), "
            + "and last modified time. Useful for verifying config content and "
            + "diagnosing configuration not taking effect.")
    public Map<String, Object> getNacosConfig(
            @ToolParam(description = "Configuration data ID (e.g., 'application.yml')") String dataId,
            @ToolParam(description = "Configuration group (default: 'DEFAULT_GROUP')") String group
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        Object configService = getConfigService();
        if (configService == null) {
            result.put("error", "No Nacos ConfigService found. Add spring-cloud-starter-alibaba-nacos-config.");
            return result;
        }

        String grp = (group != null && !group.isBlank()) ? group : "DEFAULT_GROUP";

        try {
            Object config = ReflectionHelper.invokeMethod(configService, "getConfig", dataId, grp, 5000L);
            if (config != null) {
                result.put("dataId", dataId);
                result.put("group", grp);
                result.put("content", config.toString());
            } else {
                result.put("status", "not_found");
                result.put("dataId", dataId);
                result.put("group", grp);
            }
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "List all Nacos namespaces: namespace ID, name, and config count. "
            + "The default namespace has empty ID. Useful for verifying multi-tenant "
            + "configuration isolation and namespace routing.")
    public List<Map<String, Object>> getNacosNamespaces() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object configService = getConfigService();
        if (configService == null) {
            result.add(Map.of("error", "No Nacos ConfigService found"));
            return result;
        }

        // Default namespace
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("namespace", "public");
        def.put("id", "");
        def.put("isDefault", true);
        result.add(def);

        // Try to get custom namespaces
        try {
            // Get server status and config count
            Object serverStatus = ReflectionHelper.invokeMethod(configService, "getServerStatus");
            Map<String, Object> status = new LinkedHashMap<>();
            if (serverStatus != null) {
                status.put("serverStatus", serverStatus.toString());
            }

            // Check Spring properties for namespace
            String namespace = ctx.getEnvironment().getProperty("spring.cloud.nacos.config.namespace");
            if (namespace != null) {
                Map<String, Object> ns = new LinkedHashMap<>();
                ns.put("namespace", namespace);
                ns.put("id", namespace);
                ns.put("configuredInSpring", true);
                result.add(ns);
            }
        } catch (Exception ignored) {}

        return result;
    }

    @DebugTool(description = "Check Nacos server connectivity and client health: server address, "
            + "client status (UP/DOWN), connection info, and whether the client is "
            + "successfully registered. Essential for diagnosing Nacos connection failures.")
    public Map<String, Object> getNacosHealth() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Naming service status
        Object namingService = getNamingService();
        if (namingService != null) {
            try {
                Object serverStatus = ReflectionHelper.invokeMethod(namingService, "getServerStatus");
                result.put("namingServerStatus", serverStatus != null ? serverStatus.toString() : "unknown");
            } catch (Exception ignored) {}
        } else {
            result.put("namingService", "not_configured");
        }

        // Config service status
        Object configService = getConfigService();
        if (configService != null) {
            try {
                Object serverStatus = ReflectionHelper.invokeMethod(configService, "getServerStatus");
                result.put("configServerStatus", serverStatus != null ? serverStatus.toString() : "unknown");
            } catch (Exception ignored) {}
        } else {
            result.put("configService", "not_configured");
        }

        // Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.cloud.nacos.discovery.server-addr",
                "spring.cloud.nacos.discovery.namespace",
                "spring.cloud.nacos.discovery.group",
                "spring.cloud.nacos.discovery.cluster-name",
                "spring.cloud.nacos.config.server-addr",
                "spring.cloud.nacos.config.namespace",
                "spring.cloud.nacos.config.group",
                "spring.cloud.nacos.config.file-extension",
                "spring.application.name"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        // Application name (used for registration)
        String appName = ctx.getEnvironment().getProperty("spring.application.name");
        if (appName != null) result.put("registeredAs", appName);

        return result;
    }

    @DebugTool(description = "Inspect Nacos configuration change listeners: which dataIds are being watched, "
            + "listener class names, and the configuration properties bound to Nacos config. "
            + "Useful for debugging why a configuration change didn't take effect.")
    public List<Map<String, Object>> getNacosConfigListeners() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object configService = getConfigService();
        if (configService == null) {
            result.add(Map.of("error", "No Nacos ConfigService found"));
            return result;
        }

        // Try to access listeners via reflection
        try {
            Object agent = ReflectionHelper.getFieldValue(configService, "agent");
            if (agent != null) {
                Object worker = ReflectionHelper.getFieldValue(agent, "clientWorker");
                if (worker != null) {
                    Object cacheMap = ReflectionHelper.getFieldValue(worker, "cacheMap");
                    if (cacheMap instanceof Map) {
                        for (Object key : ((Map<?, ?>) cacheMap).keySet()) {
                            Map<String, Object> listener = new LinkedHashMap<>();
                            listener.put("cacheKey", key.toString());

                            Object cache = ((Map<?, ?>) cacheMap).get(key);
                            if (cache != null) {
                                Object dataId = ReflectionHelper.invokeMethod(cache, "getDataId");
                                if (dataId != null) listener.put("dataId", dataId);
                                Object group = ReflectionHelper.invokeMethod(cache, "getGroup");
                                if (group != null) listener.put("group", group);
                                Object md5 = ReflectionHelper.invokeMethod(cache, "getMd5");
                                if (md5 != null) listener.put("md5", md5);
                                Object content = ReflectionHelper.invokeMethod(cache, "getContent");
                                if (content != null) {
                                    String c = content.toString();
                                    listener.put("contentLength", c.length());
                                    listener.put("contentPreview", c.length() > 200
                                            ? c.substring(0, 200) + "..." : c);
                                }
                            }
                            result.add(listener);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Also check @NacosConfigListener beans
        try {
            var beans = ctx.getBeansWithAnnotation(
                    (Class<? extends java.lang.annotation.Annotation>)
                            (Class<?>) Class.forName("com.alibaba.nacos.api.config.annotation.NacosConfigListener"));
            if (!beans.isEmpty()) {
                for (var e : beans.entrySet()) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("beanName", e.getKey());
                    l.put("class", e.getValue().getClass().getName());
                    result.add(l);
                }
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_listeners",
                    "hint", "No Nacos config listeners found."));
        }

        return result;
    }

    private Object getNamingService() {
        Object ns = ReflectionHelper.getFirstBeanOfType(ctx,
                "com.alibaba.nacos.api.naming.NamingService");
        if (ns == null) {
            ns = ReflectionHelper.getFirstBeanOfType(ctx,
                    "com.alibaba.cloud.nacos.NacosNamingService");
        }
        return ns;
    }

    private Object getConfigService() {
        Object cs = ReflectionHelper.getFirstBeanOfType(ctx,
                "com.alibaba.nacos.api.config.ConfigService");
        return cs;
    }
}
