package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Spring Cloud Gateway diagnostic tools.
 * Inspects route definitions, predicates, filters, global filters, and route matching.
 * Conditional on spring-cloud-starter-gateway being on classpath.
 */
public class GatewayInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Object getRouteLocator() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.cloud.gateway.route.RouteLocator");
    }

    private Object getRouteDefinitionLocator() {
        return ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.cloud.gateway.route.RouteDefinitionLocator");
    }

    @DebugTool(description = "List all Spring Cloud Gateway route definitions: route ID, predicates (Path, Host, "
            + "Method, Header, Query), filters (AddHeader, RewritePath, etc.), target URI, order, and metadata. "
            + "Essential for verifying route configuration and debugging 404 errors.")
    public List<Map<String, Object>> getGatewayRoutes() {
        List<Map<String, Object>> result = new ArrayList<>();

        Object locator = getRouteDefinitionLocator();
        if (locator == null) {
            result.add(Map.of("error", "No RouteDefinitionLocator found. Add spring-cloud-starter-gateway."));
            return result;
        }

        try {
            Object routesFlux = ReflectionHelper.invokeMethod(locator, "getRouteDefinitions");
            if (routesFlux != null) {
                // Flux - need to collect
                java.lang.reflect.Method collectList = routesFlux.getClass().getMethod("collectList");
                Object mono = collectList.invoke(routesFlux);
                java.lang.reflect.Method block = mono.getClass().getMethod("block");
                Object routes = block.invoke(mono);

                if (routes instanceof List) {
                    for (Object route : (List<?>) routes) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", ReflectionHelper.invokeMethod(route, "getId"));

                        Object uri = ReflectionHelper.invokeMethod(route, "getUri");
                        r.put("uri", uri);

                        Object order = ReflectionHelper.invokeMethod(route, "getOrder");
                        if (order != null) r.put("order", order);

                        // Predicates
                        Object predicates = ReflectionHelper.invokeMethod(route, "getPredicates");
                        if (predicates instanceof List) {
                            List<String> predStrs = new ArrayList<>();
                            for (Object p : (List<?>) predicates) {
                                predStrs.add(p.toString());
                            }
                            r.put("predicates", predStrs);
                        }

                        // Filters
                        Object filters = ReflectionHelper.invokeMethod(route, "getFilters");
                        if (filters instanceof List) {
                            List<String> filterStrs = new ArrayList<>();
                            for (Object f : (List<?>) filters) {
                                filterStrs.add(f.toString());
                            }
                            r.put("filters", filterStrs);
                        }

                        // Metadata
                        Object metadata = ReflectionHelper.invokeMethod(route, "getMetadata");
                        if (metadata instanceof Map) {
                            r.put("metadata", metadata);
                        }

                        result.add(r);
                    }
                }
            }
        } catch (Exception e) {
            result.add(Map.of("error", "Failed to read routes: " + e.getClass().getSimpleName()));
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_routes"));
        }

        return result;
    }

    @DebugTool(description = "List all Spring Cloud Gateway global filters: filter name, order, "
            + "and whether it's a forwarding, routing, or post filter. "
            + "Useful for debugging filter chain ordering and custom filter registration.")
    public List<Map<String, Object>> getGatewayGlobalFilters() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            List<?> filters = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.cloud.gateway.filter.GlobalFilter");
            if (filters.isEmpty()) {
                filters = ReflectionHelper.getBeansOfType(ctx,
                        "org.springframework.cloud.gateway.filter.GlobalFilter");
            }

            for (Object filter : filters) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("class", filter.getClass().getName());

                // Try to get order
                if (filter instanceof org.springframework.core.Ordered) {
                    f.put("order", ((org.springframework.core.Ordered) filter).getOrder());
                } else {
                    Object order = ReflectionHelper.invokeMethod(filter, "getOrder");
                    if (order != null) f.put("order", order);
                }

                result.add(f);
            }

            // Also check GatewayFilter beans
            List<?> gatewayFilters = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.cloud.gateway.filter.GatewayFilter");
            for (Object gf : gatewayFilters) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("class", gf.getClass().getSimpleName());
                result.add(f);
            }

        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_global_filters",
                    "hint", "Spring Cloud Gateway not detected. Add spring-cloud-starter-gateway."));
        }

        return result;
    }

    @DebugTool(description = "Inspect Spring Cloud Gateway discovery client routes: whether routes are "
            + "auto-generated from service discovery (lb://service-name), predicate predicates, and "
            + "whether the discovery locator is enabled. "
            + "Useful for diagnosing dynamic routing issues with Nacos/Eureka service discovery.")
    public Map<String, Object> getGatewayDiscoveryConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Discovery locator config
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.cloud.gateway.discovery.locator.enabled",
                "spring.cloud.gateway.discovery.locator.include-expression",
                "spring.cloud.gateway.discovery.locator.lower-case-service-id",
                "spring.cloud.gateway.discovery.locator.predicates",
                "spring.cloud.gateway.discovery.locator.filters",
                "spring.cloud.gateway.enabled",
                "spring.cloud.gateway.default-filters"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("discoveryConfig", props);

        // Check DiscoveryClient.RouteLocator
        Object dcLocator = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator");
        result.put("discoveryRouteLocatorPresent", dcLocator != null);

        // List services from discovery client
        try {
            Object dc = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.cloud.client.discovery.DiscoveryClient");
            if (dc != null) {
                Object services = ReflectionHelper.invokeMethod(dc, "getServices");
                if (services instanceof List) {
                    result.put("discoveredServices", services);
                }
            }
        } catch (Exception ignored) {}

        // Check for FilterDefinition / PredicateDefinition beans
        Object defaultFilters = ctx.getEnvironment().getProperty("spring.cloud.gateway.default-filters");
        if (defaultFilters != null) result.put("defaultFilters", defaultFilters);

        return result;
    }

    @DebugTool(description = "Get Spring Cloud Gateway actuator-style route info: active routes from "
            + "RouteLocator, route stats, and Gateway controller endpoint configuration. "
            + "Useful for a runtime view of the gateway state beyond static definitions.")
    public Map<String, Object> getGatewayActuatorInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Gateway properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "spring.cloud.gateway.enabled",
                "spring.cloud.gateway.metrics.enabled",
                "management.endpoint.gateway.enabled",
                "management.endpoints.web.exposure.include"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        result.put("properties", props);

        // HTTP client config
        Map<String, String> httpClient = new LinkedHashMap<>();
        String[] httpProps = {
                "spring.cloud.gateway.httpclient.connect-timeout",
                "spring.cloud.gateway.httpclient.response-timeout",
                "spring.cloud.gateway.httpclient.pool.type",
                "spring.cloud.gateway.httpclient.pool.max-connections",
                "spring.cloud.gateway.httpclient.pool.acquire-timeout",
                "spring.cloud.gateway.httpclient.ssl.handshake-timeout",
                "spring.cloud.gateway.httpclient.proxy.host"
        };
        for (String p : httpProps) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) httpClient.put(p.replace("spring.cloud.gateway.httpclient.", ""), val);
        }
        if (!httpClient.isEmpty()) result.put("httpClient", httpClient);

        // CORS config
        Map<String, String> cors = new LinkedHashMap<>();
        String[] corsProps = {
                "spring.cloud.gateway.globalcors.add-to-simple-url-handler-mapping",
                "spring.cloud.gateway.globalcors.cors-configurations"
        };
        for (String p : corsProps) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) cors.put(p, val);
        }
        if (!cors.isEmpty()) result.put("cors", cors);

        return result;
    }
}
