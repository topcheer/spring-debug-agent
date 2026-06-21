package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Apache Dubbo diagnostic tools.
 * Inspects Dubbo service providers, consumers, registry centers, thread pools, and invocation stats.
 * Conditional on dubbo-spring-boot-starter (org.apache.dubbo.config.spring.ServiceBean) being on classpath.
 */
public class DubboInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all Dubbo service providers: interface name, version, group, "
            + "registry URLs, protocol, port, and activeness. "
            + "Useful for verifying service export and diagnosing registration failures.")
    public List<Map<String, Object>> getDubboServices() {
        List<Map<String, Object>> services = new ArrayList<>();

        List<?> serviceBeans = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.dubbo.config.spring.ServiceBean");
        if (serviceBeans.isEmpty()) {
            serviceBeans = ReflectionHelper.getBeansOfType(ctx,
                    "com.alibaba.dubbo.config.spring.ServiceBean");
        }

        for (Object sb : serviceBeans) {
            Map<String, Object> info = new LinkedHashMap<>();
            Object iface = ReflectionHelper.invokeMethod(sb, "getInterface");
            if (iface != null) info.put("interface", iface.toString());

            Object group = ReflectionHelper.invokeMethod(sb, "getGroup");
            if (group != null) info.put("group", group);

            Object version = ReflectionHelper.invokeMethod(sb, "getVersion");
            if (version != null) info.put("version", version);

            Object protocol = ReflectionHelper.invokeMethod(sb, "getProtocol");
            if (protocol != null) {
                Object protocolName = ReflectionHelper.invokeMethod(protocol, "getName");
                Object protocolPort = ReflectionHelper.invokeMethod(protocol, "getPort");
                info.put("protocol", protocolName != null ? protocolName : protocol.toString());
                if (protocolPort != null) info.put("port", protocolPort);
            }

            Object registry = ReflectionHelper.invokeMethod(sb, "getRegistries");
            if (registry instanceof List) {
                List<String> regUrls = new ArrayList<>();
                for (Object r : (List<?>) registry) {
                    regUrls.add(r.toString());
                }
                info.put("registries", regUrls);
            }

            Object exported = ReflectionHelper.invokeMethod(sb, "isExported");
            if (exported != null) info.put("exported", exported);

            Object ref = ReflectionHelper.invokeMethod(sb, "getRef");
            if (ref != null) info.put("implClass", ref.getClass().getSimpleName());

            Object timeout = ReflectionHelper.invokeMethod(sb, "getTimeout");
            if (timeout != null) info.put("timeout", timeout);

            Object retries = ReflectionHelper.invokeMethod(sb, "getRetries");
            if (retries != null) info.put("retries", retries);

            services.add(info);
        }

        if (services.isEmpty()) {
            services.add(Map.of("status", "no_providers",
                    "hint", "No Dubbo @Service beans found. Add dubbo-spring-boot-starter."));
        }

        return services;
    }

    @DebugTool(description = "List all Dubbo service consumers (references): interface name, group, version, "
            + "timeout, retries, load balance strategy, check status, and URL. "
            + "Useful for diagnosing consumer injection failures or timeout configuration issues.")
    public List<Map<String, Object>> getDubboReferences() {
        List<Map<String, Object>> refs = new ArrayList<>();

        List<?> refBeans = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.dubbo.config.spring.ReferenceBean");
        if (refBeans.isEmpty()) {
            refBeans = ReflectionHelper.getBeansOfType(ctx,
                    "com.alibaba.dubbo.config.spring.ReferenceBean");
        }

        for (Object rb : refBeans) {
            Map<String, Object> info = new LinkedHashMap<>();
            Object iface = ReflectionHelper.invokeMethod(rb, "getInterface");
            if (iface != null) info.put("interface", iface.toString());

            Object group = ReflectionHelper.invokeMethod(rb, "getGroup");
            if (group != null) info.put("group", group);

            Object version = ReflectionHelper.invokeMethod(rb, "getVersion");
            if (version != null) info.put("version", version);

            Object timeout = ReflectionHelper.invokeMethod(rb, "getTimeout");
            if (timeout != null) info.put("timeout", timeout);

            Object retries = ReflectionHelper.invokeMethod(rb, "getRetries");
            if (retries != null) info.put("retries", retries);

            Object loadBalance = ReflectionHelper.invokeMethod(rb, "getLoadbalance");
            if (loadBalance != null) info.put("loadBalance", loadBalance);

            Object check = ReflectionHelper.invokeMethod(rb, "isCheck");
            if (check != null) info.put("check", check);

            Object url = ReflectionHelper.invokeMethod(rb, "getUrl");
            if (url != null) info.put("url", url.toString());

            Object cluster = ReflectionHelper.invokeMethod(rb, "getCluster");
            if (cluster != null) info.put("cluster", cluster);

            Object actives = ReflectionHelper.invokeMethod(rb, "getActives");
            if (actives != null) info.put("actives", actives);

            refs.add(info);
        }

        if (refs.isEmpty()) {
            refs.add(Map.of("status", "no_consumers",
                    "hint", "No Dubbo @Reference beans found."));
        }

        return refs;
    }

    @DebugTool(description = "Inspect Dubbo application configuration: application name, registry addresses, "
            + "protocol (dubbo/triple/rest), QoS port, serialization type, and metadata center. "
            + "Provides overall Dubbo runtime configuration overview.")
    public Map<String, Object> getDubboApplicationConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object appConfig = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.dubbo.config.ApplicationConfig");
        if (appConfig == null) {
            result.put("status", "not_configured");
            return result;
        }

        result.put("name", ReflectionHelper.invokeMethod(appConfig, "getName"));
        result.put("version", ReflectionHelper.invokeMethod(appConfig, "getVersion"));
        result.put("organization", ReflectionHelper.invokeMethod(appConfig, "getOrganization"));
        result.put("architecture", ReflectionHelper.invokeMethod(appConfig, "getArchitecture"));
        result.put("environment", ReflectionHelper.invokeMethod(appConfig, "getEnvironment"));

        Object qosPort = ReflectionHelper.invokeMethod(appConfig, "getQosPort");
        if (qosPort != null) result.put("qosPort", qosPort);

        Object qosEnable = ReflectionHelper.invokeMethod(appConfig, "getQosEnable");
        if (qosEnable != null) result.put("qosEnable", qosEnable);

        Object serializer = ReflectionHelper.invokeMethod(appConfig, "getSerializer");
        if (serializer != null) result.put("serializer", serializer);

        // Registry
        Object registries = ReflectionHelper.invokeMethod(appConfig, "getRegistries");
        if (registries instanceof List) {
            List<String> regUrls = new ArrayList<>();
            for (Object r : (List<?>) registries) {
                regUrls.add(r.toString());
            }
            result.put("registries", regUrls);
        }

        // Protocol
        Object protocols = ReflectionHelper.invokeMethod(appConfig, "getProtocols");
        if (protocols == null) {
            Object protocolConfig = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.apache.dubbo.config.ProtocolConfig");
            if (protocolConfig != null) {
                Map<String, Object> proto = new LinkedHashMap<>();
                proto.put("name", ReflectionHelper.invokeMethod(protocolConfig, "getName"));
                proto.put("port", ReflectionHelper.invokeMethod(protocolConfig, "getPort"));
                proto.put("threads", ReflectionHelper.invokeMethod(protocolConfig, "getThreads"));
                proto.put("serialization", ReflectionHelper.invokeMethod(protocolConfig, "getSerialization"));
                result.put("protocol", proto);
            }
        }

        // Spring properties
        String[] propNames = {
                "dubbo.application.name", "dubbo.registry.address", "dubbo.protocol.name",
                "dubbo.protocol.port", "dubbo.protocol.threads", "dubbo.provider.timeout",
                "dubbo.consumer.timeout", "dubbo.consumer.loadbalance"
        };
        Map<String, String> props = new LinkedHashMap<>();
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p, val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        return result;
    }

    @DebugTool(description = "Inspect Dubbo thread pool configuration: core size, max size, queue capacity, "
            + "keepalive time, active threads, and queue size. "
            + "Useful for diagnosing 'Thread pool is EXHAUSTED' errors in high-throughput Dubbo services.")
    public Map<String, Object> getDubboThreadPool() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object protocolConfig = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.dubbo.config.ProtocolConfig");
        if (protocolConfig == null) {
            result.put("status", "not_configured");
            return result;
        }

        Object threads = ReflectionHelper.invokeMethod(protocolConfig, "getThreads");
        if (threads != null) result.put("maxThreads", threads);

        Object coreThreads = ReflectionHelper.invokeMethod(protocolConfig, "getCorethreads");
        if (coreThreads != null) result.put("coreThreads", coreThreads);

        Object ioThreads = ReflectionHelper.invokeMethod(protocolConfig, "getIothreads");
        if (ioThreads != null) result.put("ioThreads", ioThreads);

        Object queues = ReflectionHelper.invokeMethod(protocolConfig, "getQueues");
        if (queues != null) result.put("queueCapacity", queues);

        Object threadPool = ReflectionHelper.invokeMethod(protocolConfig, "getThreadpool");
        if (threadPool != null) result.put("threadPoolType", threadPool);

        Object keepalive = ReflectionHelper.invokeMethod(protocolConfig, "getKeepalive");
        if (keepalive != null) result.put("keepAlive", keepalive);

        // Try to get actual thread pool stats from Dubbo ProtocolServer
        try {
            Object server = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.apache.dubbo.remoting.transport.AbstractServer");
            if (server != null) {
                Object executor = ReflectionHelper.invokeMethod(server, "getExecutor");
                if (executor != null) {
                    result.put("executorClass", executor.getClass().getSimpleName());
                    Object activeCount = ReflectionHelper.invokeMethod(executor, "getActiveCount");
                    if (activeCount != null) result.put("activeThreads", activeCount);
                    Object completedTaskCount = ReflectionHelper.invokeMethod(executor, "getCompletedTaskCount");
                    if (completedTaskCount != null) result.put("completedTasks", completedTaskCount);
                    Object taskCount = ReflectionHelper.invokeMethod(executor, "getTaskCount");
                    if (taskCount != null) result.put("totalTasks", taskCount);
                    Object largestPoolSize = ReflectionHelper.invokeMethod(executor, "getLargestPoolSize");
                    if (largestPoolSize != null) result.put("peakPoolSize", largestPoolSize);
                }
            }
        } catch (Exception ignored) {}

        return result;
    }

    @DebugTool(description = "Check Dubbo registry connection health and registered service URLs. "
            + "Lists all registry URLs, subscription status, and whether the application is "
            + "registered with the registry center (Nacos, Zookeeper, etc.). "
            + "Useful for diagnosing 'No provider available' or service discovery failures.")
    public List<Map<String, Object>> getDubboRegistryStatus() {
        List<Map<String, Object>> result = new ArrayList<>();

        List<?> registryConfigs = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.dubbo.config.RegistryConfig");
        if (registryConfigs.isEmpty()) {
            result.add(Map.of("status", "no_registry",
                    "hint", "No RegistryConfig found. Set dubbo.registry.address property."));
            return result;
        }

        for (Object rc : registryConfigs) {
            Map<String, Object> info = new LinkedHashMap<>();
            Object address = ReflectionHelper.invokeMethod(rc, "getAddress");
            info.put("address", address);

            Object protocol = ReflectionHelper.invokeMethod(rc, "getProtocol");
            if (protocol != null) info.put("protocol", protocol);

            Object username = ReflectionHelper.invokeMethod(rc, "getUsername");
            if (username != null) info.put("username", username);

            Object register = ReflectionHelper.invokeMethod(rc, "isRegister");
            if (register != null) info.put("registerEnabled", register);

            Object subscribe = ReflectionHelper.invokeMethod(rc, "isSubscribe");
            if (subscribe != null) info.put("subscribeEnabled", subscribe);

            Object check = ReflectionHelper.invokeMethod(rc, "isCheck");
            if (check != null) info.put("check", check);

            Object timeout = ReflectionHelper.invokeMethod(rc, "getTimeout");
            if (timeout != null) info.put("timeout", timeout);

            result.add(info);
        }

        return result;
    }
}
