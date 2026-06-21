package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Cloud OpenFeign inspection tool.
 * Shows Feign client beans, their target URLs, configuration, interceptors, and fallbacks.
 * Conditional on spring-cloud-starter-openfeign being on classpath.
 */
public class OpenFeignInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all @FeignClient beans: name, URL, contextId, fallback class, "
            + "and target interface. Useful for understanding microservice client topology.")
    public List<Map<String, Object>> getFeignClients() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Class<?> feignClientClass = ReflectionHelper.resolveClass(
                    "org.springframework.cloud.openfeign.FeignClient", ctx);
            if (feignClientClass == null) {
                results.add(Map.of("info", "OpenFeign not on classpath. Add spring-cloud-starter-openfeign."));
                return results;
            }

            // Scan all beans for @FeignClient annotation on their interface
            for (String name : ctx.getBeanDefinitionNames()) {
                try {
                    Object bean = ctx.getBean(name);
                    if (bean == null) continue;

                    // Check interfaces for @FeignClient
                    for (Class<?> iface : bean.getClass().getInterfaces()) {
                        Object ann = iface.getAnnotation((Class<java.lang.annotation.Annotation>) feignClientClass);
                        if (ann != null) {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("beanName", name);
                            info.put("interface", iface.getName());
                            info.put("name", ReflectionHelper.invokeMethod(ann, "name"));
                            Object url = ReflectionHelper.invokeMethod(ann, "url");
                            if (url != null && !url.toString().isEmpty()) {
                                info.put("url", url);
                            }
                            Object contextId = ReflectionHelper.invokeMethod(ann, "contextId");
                            if (contextId != null && !contextId.toString().isEmpty()) {
                                info.put("contextId", contextId);
                            }
                            Object qualifier = ReflectionHelper.invokeMethod(ann, "qualifiers");
                            if (qualifier instanceof String[] qArr && qArr.length > 0) {
                                info.put("qualifiers", Arrays.asList(qArr));
                            }
                            Object fallback = ReflectionHelper.invokeMethod(ann, "fallback");
                            if (fallback != null && !fallback.equals(void.class)) {
                                info.put("fallback", ((Class<?>) fallback).getName());
                            }
                            Object fallbackFactory = ReflectionHelper.invokeMethod(ann, "fallbackFactory");
                            if (fallbackFactory != null && !fallbackFactory.equals(void.class)) {
                                info.put("fallbackFactory", ((Class<?>) fallbackFactory).getName());
                            }
                            info.put("className", bean.getClass().getName());

                            results.add(info);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No @FeignClient beans found"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get Feign clients: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "Get Feign client configuration: connect timeout, read timeout, logger level, "
            + "retryer, request options, and encoder/decoder settings.")
    public Map<String, Object> getFeignClientConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Read Feign properties from environment
            org.springframework.core.env.Environment env = ctx.getEnvironment();
            Map<String, Object> props = new LinkedHashMap<>();

            String[] feignProps = {
                    "spring.cloud.openfeign.enabled",
                    "spring.cloud.openfeign.circuitbreaker.enabled",
                    "spring.cloud.openfeign.client.config.default.connect-timeout",
                    "spring.cloud.openfeign.client.config.default.read-timeout",
                    "spring.cloud.openfeign.client.config.default.logger-level",
                    "spring.cloud.openfeign.client.config.default.request-interceptors",
                    "spring.cloud.openfeign.compression.request.enabled",
                    "spring.cloud.openfeign.compression.response.enabled",
                    "feign.client.config.default.connectTimeout",
                    "feign.client.config.default.readTimeout",
                    "feign.client.config.default.loggerLevel"
            };

            for (String prop : feignProps) {
                String val = env.getProperty(prop);
                if (val != null) {
                    props.put(prop, val);
                }
            }
            if (!props.isEmpty()) result.put("properties", props);

            // Try to find FeignClientFactoryBean or Feign.Builder beans
            List<Object> builders = ReflectionHelper.getBeansOfType(ctx,
                    "feign.Feign$Builder");
            if (!builders.isEmpty()) {
                List<Map<String, Object>> builderInfo = new ArrayList<>();
                for (Object builder : builders) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("class", builder.getClass().getName());
                    builderInfo.add(b);
                }
                result.put("builders", builderInfo);
            }

            // Check for CircuitBreaker integration
            Object cbEnabled = null;
            try {
                cbEnabled = env.getProperty("spring.cloud.openfeign.circuitbreaker.enabled", Boolean.class);
            } catch (Exception ignored) {}
            if (cbEnabled != null) {
                result.put("circuitBreakerEnabled", cbEnabled);
            }

        } catch (Exception e) {
            result.put("error", "Failed to get Feign config: " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "List all Feign request interceptors: class name, order, and whether they're "
            + "Spring Cloud LoadBalancer, OAuth2, or custom interceptors.")
    public List<Map<String, Object>> getFeignInterceptors() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            List<Object> interceptors = ReflectionHelper.getBeansOfType(ctx,
                    "feign.RequestInterceptor");
            if (interceptors.isEmpty()) {
                results.add(Map.of("info", "No Feign RequestInterceptor beans found"));
                return results;
            }

            for (Object interceptor : interceptors) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("className", interceptor.getClass().getName());
                info.put("simpleName", interceptor.getClass().getSimpleName());

                // Identify common interceptors
                String name = interceptor.getClass().getName();
                if (name.contains("OAuth2")) {
                    info.put("type", "OAuth2");
                } else if (name.contains("LoadBalancer") || name.contains("_lb")) {
                    info.put("type", "LoadBalancer");
                } else if (name.contains("SpringBoot")) {
                    info.put("type", "Spring Boot Default");
                } else {
                    info.put("type", "Custom");
                }

                // Try to get the interceptor's template modification behavior
                try {
                    Method applyMethod = interceptor.getClass().getMethod(
                            "apply", Class.forName("feign.RequestTemplate", false, ctx.getClassLoader()));
                    if (applyMethod != null) {
                        info.put("hasApplyMethod", true);
                    }
                } catch (Exception ignored) {}

                results.add(info);
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get Feign interceptors: " + e.getMessage()));
        }

        return results;
    }

    @DebugTool(description = "Get Feign target info for a specific client: resolved target URL, load-balanced vs direct, "
            + "HTTP method mappings from the interface, and path prefixes.")
    public Map<String, Object> getFeignTargetInfo(
            @ToolParam(description = "Feign client bean name or interface name") String clientName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Class<?> feignClientClass = ReflectionHelper.resolveClass(
                    "org.springframework.cloud.openfeign.FeignClient", ctx);
            if (feignClientClass == null) {
                result.put("info", "OpenFeign not on classpath");
                return result;
            }

            Object targetBean = null;
            Class<?> targetInterface = null;

            for (String name : ctx.getBeanDefinitionNames()) {
                try {
                    Object bean = ctx.getBean(name);
                    if (bean == null) continue;

                    for (Class<?> iface : bean.getClass().getInterfaces()) {
                        Object ann = iface.getAnnotation((Class<java.lang.annotation.Annotation>) feignClientClass);
                        if (ann != null && (name.toLowerCase().contains(clientName.toLowerCase())
                                || iface.getSimpleName().toLowerCase().contains(clientName.toLowerCase()))) {
                            targetBean = bean;
                            targetInterface = iface;
                            result.put("beanName", name);
                            result.put("interface", iface.getName());
                            result.put("feignClientName", ReflectionHelper.invokeMethod(ann, "name"));
                            result.put("url", ReflectionHelper.invokeMethod(ann, "url"));
                            result.put("isLoadBalanced", ReflectionHelper.invokeMethod(ann, "url").toString().isEmpty());
                            break;
                        }
                    }
                    if (targetInterface != null) break;
                } catch (Exception ignored) {}
            }

            if (targetInterface == null) {
                result.put("error", "Feign client not found: " + clientName);
                return result;
            }

            // Extract HTTP method mappings from interface
            List<Map<String, Object>> endpoints = new ArrayList<>();
            for (Method m : targetInterface.getDeclaredMethods()) {
                Map<String, Object> endpoint = new LinkedHashMap<>();
                endpoint.put("method", m.getName());
                endpoint.put("paramTypes", Arrays.stream(m.getParameterTypes())
                        .map(Class::getSimpleName).toArray());

                // Check for Spring MVC annotations on Feign interface
                for (java.lang.annotation.Annotation ann : m.getAnnotations()) {
                    String annName = ann.annotationType().getSimpleName();
                    if (Set.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
                            "PatchMapping", "RequestMapping").contains(annName)) {
                        endpoint.put("httpMethod", annName);
                        Object path = ReflectionHelper.invokeMethod(ann, "value");
                        if (path instanceof String[] pArr && pArr.length > 0) {
                            endpoint.put("path", pArr[0]);
                        }
                    }
                }

                if (endpoint.containsKey("httpMethod")) {
                    endpoints.add(endpoint);
                }
            }
            result.put("endpoints", endpoints);

        } catch (Exception e) {
            result.put("error", "Failed to get Feign target info: " + e.getMessage());
        }

        return result;
    }
}
