package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Cloud diagnostic tools.
 * Inspects service discovery, config server, and cloud circuit breakers.
 * Conditional on Spring Cloud being on classpath.
 */
public class CloudInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get service discovery info: registered services, instances, and health status. Works with Eureka, Consul, and Zookeeper. Useful for diagnosing service registration or discovery issues.")
    public Map<String, Object> getServiceDiscovery() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Eureka
        try {
            Class<?> eurekaClientClass = Class.forName("com.netflix.discovery.EurekaClient");
            String[] names = ctx.getBeanNamesForType(eurekaClientClass);
            if (names.length > 0) {
                Object client = ctx.getBean(names[0]);
                result.put("discoveryType", "Eureka");

                // Get application info
                try {
                    Method getApps = eurekaClientClass.getMethod("getApplications");
                    Object apps = getApps.invoke(client);
                    if (apps != null) {
                        Method getRegisteredApps = apps.getClass().getMethod("getRegisteredApplications");
                        List<?> registered = (List<?>) getRegisteredApps.invoke(apps);
                        List<Map<String, Object>> serviceList = new ArrayList<>();
                        for (Object app : registered) {
                            Map<String, Object> a = new LinkedHashMap<>();
                            a.put("name", ReflectionHelper.invokeMethod(app, "getName"));
                            Object instances = ReflectionHelper.invokeMethod(app, "getInstances");
                            if (instances instanceof List<?> list) {
                                List<Map<String, Object>> instList = new ArrayList<>();
                                for (Object inst : list) {
                                    Map<String, Object> i = new LinkedHashMap<>();
                                    i.put("id", ReflectionHelper.invokeMethod(inst, "getInstanceId"));
                                    i.put("host", ReflectionHelper.invokeMethod(inst, "getHostName"));
                                    i.put("port", ReflectionHelper.invokeMethod(inst, "getPort"));
                                    i.put("status", String.valueOf(ReflectionHelper.invokeMethod(inst, "getStatus")));
                                    instList.add(i);
                                }
                                a.put("instances", instList);
                            }
                            serviceList.add(a);
                        }
                        result.put("registeredServices", serviceList);
                        result.put("serviceCount", serviceList.size());
                    }

                    // Self registration status
                    Object instanceInfo = ReflectionHelper.invokeMethod(client, "getInstanceInfo");
                    if (instanceInfo != null) {
                        Map<String, Object> self = new LinkedHashMap<>();
                        self.put("appName", ReflectionHelper.invokeMethod(instanceInfo, "getAppName"));
                        self.put("instanceId", ReflectionHelper.invokeMethod(instanceInfo, "getInstanceId"));
                        self.put("status", String.valueOf(ReflectionHelper.invokeMethod(instanceInfo, "getStatus")));
                        self.put("port", ReflectionHelper.invokeMethod(instanceInfo, "getPort"));
                        result.put("selfRegistration", self);
                    }
                } catch (Exception ignored) {}
                return result;
            }
        } catch (ClassNotFoundException ignored) {}

        // Consul
        try {
            Class<?> consulClientClass = Class.forName("com.ecwid.consul.v1.ConsulClient");
            String[] names = ctx.getBeanNamesForType(consulClientClass);
            if (names.length > 0) {
                result.put("discoveryType", "Consul");
                result.put("note", "Consul client detected. Service discovery is configured.");
                return result;
            }
        } catch (ClassNotFoundException ignored) {}

        // Nacos
        try {
            Class<?> namingServiceClass = Class.forName("com.alibaba.nacos.api.naming.NamingService");
            String[] names = ctx.getBeanNamesForType(namingServiceClass);
            if (names.length > 0) {
                result.put("discoveryType", "Nacos");
                return result;
            }
        } catch (ClassNotFoundException ignored) {}

        result.put("note", "No service discovery client detected (Eureka/Consul/Nacos). " +
                "This application may be standalone or using a different discovery mechanism.");
        return result;
    }

    @DebugTool(description = "Get Spring Cloud Config server status: configuration sources, property refresh status, and environment info. Useful for debugging externalized configuration issues.")
    public Map<String, Object> getConfigServerStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Check for Config Server client
            try {
                Class<?> configClientClass = Class.forName(
                        "org.springframework.cloud.config.client.ConfigServicePropertySourceLocator");
                String[] names = ctx.getBeanNamesForType(configClientClass);
                if (names.length > 0) {
                    result.put("configServer", "enabled");
                    // Get config server URI
                    String uri = ctx.getEnvironment().getProperty("spring.cloud.config.uri");
                    result.put("configServerUri", uri != null ? uri : "default");
                    String name = ctx.getEnvironment().getProperty("spring.cloud.config.name");
                    result.put("applicationName", name != null ? name : ctx.getEnvironment()
                            .getProperty("spring.application.name"));
                    String profile = ctx.getEnvironment().getProperty("spring.cloud.config.profile");
                    result.put("profile", profile != null ? profile : "default");
                    String label = ctx.getEnvironment().getProperty("spring.cloud.config.label");
                    result.put("label", label != null ? label : "default");
                }
            } catch (ClassNotFoundException ignored) {}

            // Check for @RefreshScope beans
            try {
                Class<?> refreshScopeClass = Class.forName(
                        "org.springframework.cloud.context.scope.refresh.RefreshScope");
                String[] names = ctx.getBeanNamesForType(refreshScopeClass);
                if (names.length > 0) {
                    Object scope = ctx.getBean(names[0]);
                    result.put("refreshScope", "available");
                    Object names1 = ReflectionHelper.invokeMethod(scope, "getName");
                    result.put("refreshScopeName", names1);
                }
            } catch (ClassNotFoundException ignored) {}

            // Property sources from environment
            List<String> propertySources = new ArrayList<>();
            org.springframework.core.env.ConfigurableEnvironment env =
                    (org.springframework.core.env.ConfigurableEnvironment) ctx.getEnvironment();
            for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
                propertySources.add(ps.getName());
            }
            result.put("propertySources", propertySources);

            if (result.isEmpty()) {
                result.put("note", "Spring Cloud Config not detected.");
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get Spring Cloud circuit breaker info: instances, groups, and their status across all implementations (Resilience4j, Spring Cloud Circuit Breaker abstraction).")
    public List<Map<String, Object>> getCloudCircuitBreakers() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> cbFactoryClass = Class.forName(
                    "org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory");
            String[] names = ctx.getBeanNamesForType(cbFactoryClass);
            if (names.length > 0) {
                Object factory = ctx.getBean(names[0]);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("factoryClass", factory.getClass().getSimpleName());
                info.put("beanName", names[0]);

                // Get underlying implementation
                String impl = factory.getClass().getSimpleName();
                if (impl.contains("Resilience4J")) {
                    info.put("implementation", "Resilience4j");
                } else if (impl.contains("Hystrix")) {
                    info.put("implementation", "Hystrix");
                } else if (impl.contains("Sentinel")) {
                    info.put("implementation", "Sentinel");
                } else {
                    info.put("implementation", impl);
                }

                result.add(info);
            }
        } catch (ClassNotFoundException ignored) {}

        // Also check Resilience4j registry directly
        try {
            Class<?> registryClass = Class.forName("io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry");
            String[] registryNames = ctx.getBeanNamesForType(registryClass);
            if (registryNames.length > 0) {
                Object registry = ctx.getBean(registryNames[0]);
                Method getAll = registryClass.getMethod("getAllCircuitBreakers");
                Set<?> breakers = (Set<?>) getAll.invoke(registry);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("source", "Resilience4j Registry (Spring Cloud)");
                summary.put("instanceCount", breakers.size());

                List<String> cbNames = new ArrayList<>();
                for (Object cb : breakers) {
                    cbNames.add((String) ReflectionHelper.invokeMethod(cb, "getName"));
                }
                summary.put("instances", cbNames);
                result.add(summary);
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.add(Map.of("note", "No Spring Cloud circuit breaker detected. " +
                    "Add spring-cloud-starter-circuitbreaker-resilience4j to enable."));
        }

        return result;
    }
}
