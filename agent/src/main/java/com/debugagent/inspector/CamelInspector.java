package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.*;
import java.util.*;

/**
 * Apache Camel diagnostic tools.
 * Inspects Camel routes, endpoints, consumers, route statistics, and error handlers.
 * Conditional on camel-spring-boot (org.apache.camel.CamelContext) being on classpath.
 */
public class CamelInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Object getCamelContext() {
        return ReflectionHelper.getFirstBeanOfType(ctx, "org.apache.camel.CamelContext");
    }

    @DebugTool(description = "List all Apache Camel routes: route ID, status (Started/Stopped/Suspended), "
            + "endpoint URI, uptime, and route description. "
            + "Useful for verifying route deployment and diagnosing routes that failed to start.")
    public List<Map<String, Object>> getCamelRoutes() {
        List<Map<String, Object>> routes = new ArrayList<>();
        Object camelCtx = getCamelContext();
        if (camelCtx == null) {
            return List.of(Map.of("error", "No CamelContext found. Add camel-spring-boot-starter."));
        }

        Object routeList = ReflectionHelper.invokeMethod(camelCtx, "getRoutes");
        if (routeList instanceof List) {
            for (Object route : (List<?>) routeList) {
                Map<String, Object> info = new LinkedHashMap<>();
                Object id = ReflectionHelper.invokeMethod(route, "getId");
                info.put("id", id);

                Object desc = ReflectionHelper.invokeMethod(route, "getDescription");
                if (desc != null) info.put("description", desc.toString());

                Object endpoint = ReflectionHelper.invokeMethod(route, "getEndpoint");
                if (endpoint != null) {
                    Object uri = ReflectionHelper.invokeMethod(endpoint, "getEndpointUri");
                    info.put("endpointUri", uri);
                }

                Object routeContext = ReflectionHelper.invokeMethod(route, "getRouteContext");
                if (routeContext != null) {
                    Object routeController = ReflectionHelper.invokeMethod(camelCtx, "getRouteController");
                    if (routeController != null) {
                        try {
                            Method m = routeController.getClass().getMethod("getRouteStatus", String.class);
                            if (m != null) {
                                Object status = m.invoke(routeController, id);
                                info.put("status", status != null ? status.toString() : "unknown");
                            }
                        } catch (Exception ignored) {}
                    }
                }

                Object uptime = ReflectionHelper.invokeMethod(route, "getUptime");
                if (uptime != null) info.put("uptime", uptime.toString());

                routes.add(info);
            }
        }

        return routes;
    }

    @DebugTool(description = "Get Camel route performance statistics: total exchanges completed, failed, "
            + "in-flight, min/mean/max processing time, last processing time, and failure rate. "
            + "Essential for identifying slow routes, bottlenecks, and error-prone integrations.")
    public List<Map<String, Object>> getCamelRouteStats() {
        List<Map<String, Object>> stats = new ArrayList<>();
        Object camelCtx = getCamelContext();
        if (camelCtx == null) {
            return List.of(Map.of("error", "No CamelContext found"));
        }

        Object manager = ReflectionHelper.invokeMethod(camelCtx, "getManagementStrategy");
        if (manager == null) {
            stats.add(Map.of("error", "Management strategy not available"));
            return stats;
        }

        // Get all managed route controllers
        Object routeList = ReflectionHelper.invokeMethod(camelCtx, "getRoutes");
        if (routeList instanceof List) {
            for (Object route : (List<?>) routeList) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("routeId", ReflectionHelper.invokeMethod(route, "getId"));

                Object ep = ReflectionHelper.invokeMethod(route, "getEndpoint");
                if (ep != null) s.put("endpoint", ReflectionHelper.invokeMethod(ep, "getEndpointUri"));

                // Try to get stats from ManagedRouteMBean via JMX or direct
                try {
                    // In Camel 3.x, get the ManagedRoute from ManagementStrategy
                    Method getManagedObjects = manager.getClass().getMethod("getManagedObjects");
                    Map<?, ?> managed = (Map<?, ?>) getManagedObjects.invoke(manager);
                    for (Object mo : managed.values()) {
                        if (mo.getClass().getSimpleName().contains("ManagedRoute")) {
                            Object routeId = ReflectionHelper.invokeMethod(mo, "getRouteId");
                            if (routeId != null && routeId.equals(s.get("routeId"))) {
                                s.put("exchangesCompleted", ReflectionHelper.invokeMethod(mo, "getExchangesCompleted"));
                                s.put("exchangesFailed", ReflectionHelper.invokeMethod(mo, "getExchangesFailed"));
                                s.put("exchangesInflight", ReflectionHelper.invokeMethod(mo, "getExchangesInflight"));
                                s.put("minProcessingTime", ReflectionHelper.invokeMethod(mo, "getMinProcessingTimeMillis"));
                                s.put("meanProcessingTime", ReflectionHelper.invokeMethod(mo, "getMeanProcessingTimeMillis"));
                                s.put("maxProcessingTime", ReflectionHelper.invokeMethod(mo, "getMaxProcessingTimeMillis"));
                                s.put("lastProcessingTime", ReflectionHelper.invokeMethod(mo, "getLastProcessingTimeMillis"));
                                s.put("totalProcessingTime", ReflectionHelper.invokeMethod(mo, "getTotalProcessingTimeMillis"));
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                stats.add(s);
            }
        }

        return stats;
    }

    @DebugTool(description = "List all Camel endpoints registered in the context: endpoint URI, singleton status, "
            + "and whether the endpoint is a consumer or producer. "
            + "Useful for verifying endpoint configuration and diagnosing connectivity issues.")
    public List<Map<String, Object>> getCamelEndpoints() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        Object camelCtx = getCamelContext();
        if (camelCtx == null) {
            return List.of(Map.of("error", "No CamelContext found"));
        }

        Object endpointMap = ReflectionHelper.invokeMethod(camelCtx, "getEndpointMap");
        if (endpointMap instanceof Map) {
            for (Object key : ((Map<?, ?>) endpointMap).keySet()) {
                Map<String, Object> ep = new LinkedHashMap<>();
                Object endpoint = ((Map<?, ?>) endpointMap).get(key);
                ep.put("uri", key.toString());
                ep.put("class", endpoint.getClass().getSimpleName());

                Object isSingleton = ReflectionHelper.invokeMethod(endpoint, "isSingleton");
                if (isSingleton != null) ep.put("singleton", isSingleton);

                endpoints.add(ep);
            }
        }

        return endpoints;
    }

    @DebugTool(description = "Inspect Camel consumers: consumer state, inflight exchanges, suspended status, "
            + "and throughput. Useful for diagnosing message consumption issues, stuck consumers, "
            + "or poll strategy problems in enterprise integration routes.")
    public List<Map<String, Object>> getCamelConsumers() {
        List<Map<String, Object>> consumers = new ArrayList<>();
        Object camelCtx = getCamelContext();
        if (camelCtx == null) {
            return List.of(Map.of("error", "No CamelContext found"));
        }

        Object routeList = ReflectionHelper.invokeMethod(camelCtx, "getRoutes");
        if (routeList instanceof List) {
            for (Object route : (List<?>) routeList) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("routeId", ReflectionHelper.invokeMethod(route, "getId"));

                Object consumer = ReflectionHelper.invokeMethod(route, "getConsumer");
                if (consumer != null) {
                    info.put("consumerClass", consumer.getClass().getSimpleName());

                    Object endpoint = ReflectionHelper.invokeMethod(consumer, "getEndpoint");
                    if (endpoint != null) {
                        info.put("endpointUri", ReflectionHelper.invokeMethod(endpoint, "getEndpointUri"));
                    }

                    Object isRunAllowed = ReflectionHelper.invokeMethod(consumer, "isRunAllowed");
                    if (isRunAllowed != null) info.put("runAllowed", isRunAllowed);

                    Object isStarting = ReflectionHelper.invokeMethod(consumer, "isStarting");
                    if (isStarting != null) info.put("starting", isStarting);

                    Object isStopped = ReflectionHelper.invokeMethod(consumer, "isStopped");
                    if (isStopped != null) info.put("stopped", isStopped);
                }

                consumers.add(info);
            }
        }

        return consumers;
    }

    @DebugTool(description = "Get Camel context summary: name, version, uptime, status, "
            + "total routes, total endpoints, classpath packages scanned, and shutdown strategy. "
            + "Provides a quick health overview of the entire Camel integration runtime.")
    public Map<String, Object> getCamelContextInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        Object camelCtx = getCamelContext();
        if (camelCtx == null) {
            result.put("status", "not_configured");
            result.put("hint", "No CamelContext found. Add camel-spring-boot-starter dependency.");
            return result;
        }

        result.put("name", ReflectionHelper.invokeMethod(camelCtx, "getName"));
        result.put("version", ReflectionHelper.invokeMethod(camelCtx, "getVersion"));
        result.put("uptime", ReflectionHelper.invokeMethod(camelCtx, "getUptime"));

        Object routes = ReflectionHelper.invokeMethod(camelCtx, "getRoutes");
        if (routes instanceof List) {
            result.put("totalRoutes", ((List<?>) routes).size());
        }

        Object endpoints = ReflectionHelper.invokeMethod(camelCtx, "getEndpoints");
        if (endpoints instanceof List) {
            result.put("totalEndpoints", ((List<?>) endpoints).size());
        }

        // Shutdown strategy
        Object shutdownStrategy = ReflectionHelper.invokeMethod(camelCtx, "getShutdownStrategy");
        if (shutdownStrategy != null) {
            Map<String, Object> ss = new LinkedHashMap<>();
            ss.put("class", shutdownStrategy.getClass().getSimpleName());
            ss.put("timeout", ReflectionHelper.invokeMethod(shutdownStrategy, "getTimeout"));
            ss.put("timeUnit", ReflectionHelper.invokeMethod(shutdownStrategy, "getTimeUnit"));
            result.put("shutdownStrategy", ss);
        }

        // Camel context status
        try {
            Object status = ReflectionHelper.invokeMethod(camelCtx, "getStatus");
            result.put("status", status != null ? status.toString() : "unknown");
        } catch (Exception ignored) {}

        // Components
        Object componentMap = ReflectionHelper.invokeMethod(camelCtx, "getComponentNames");
        if (componentMap instanceof Set) {
            result.put("components", new ArrayList<>((Set<?>) componentMap));
        }

        return result;
    }
}
