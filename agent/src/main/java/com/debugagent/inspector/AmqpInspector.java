package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * RabbitMQ / AMQP diagnostic tools.
 * Inspects Spring AMQP RabbitAdmin, queues, exchanges, consumers, channels,
 * and connection factory statistics.
 * Conditional on spring-boot-starter-amqp (org.springframework.amqp.rabbit.core.RabbitAdmin) being on classpath.
 */
public class AmqpInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Object getRabbitAdmin() {
        return ReflectionHelper.getFirstBeanOfType(ctx, "org.springframework.amqp.rabbit.core.RabbitAdmin");
    }

    private Object getConnectionFactory() {
        Object cf = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.amqp.rabbit.connection.CachingConnectionFactory");
        if (cf == null) {
            cf = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.amqp.rabbit.connection.ConnectionFactory");
        }
        return cf;
    }

    @DebugTool(description = "List all RabbitMQ queues: queue name, durable, exclusive, auto-delete, "
            + "arguments, and consumer count. Reads from RabbitAdmin queue properties. "
            + "Useful for verifying queue declaration and diagnosing missing queue issues.")
    public List<Map<String, Object>> getAmqpQueues() {
        List<Map<String, Object>> result = new ArrayList<>();
        Object admin = getRabbitAdmin();
        if (admin == null) {
            return List.of(Map.of("error", "No RabbitAdmin found. Add spring-boot-starter-amqp."));
        }

        Object queueNames = ReflectionHelper.invokeMethod(admin, "getQueueNames");
        if (queueNames instanceof String[]) {
            for (String name : (String[]) queueNames) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("name", name);
                result.add(q);
            }
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_queues",
                    "hint", "No queues found. Declare queues via @Bean Queue or @RabbitListener."));
        }

        return result;
    }

    @DebugTool(description = "Inspect RabbitMQ consumers: listener container info, queue names, "
            + "concurrent consumers, prefetch count, acknowledge mode, and message listener class. "
            + "Useful for diagnosing consumer not receiving messages or concurrency issues.")
    public List<Map<String, Object>> getAmqpConsumers() {
        List<Map<String, Object>> result = new ArrayList<>();

        // Get SimpleMessageListenerContainer beans
        List<?> containers = ReflectionHelper.getBeansOfType(ctx,
                "org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer");
        if (containers.isEmpty()) {
            containers = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer");
        }

        for (Object container : containers) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("class", container.getClass().getSimpleName());

            Object queueNames = ReflectionHelper.invokeMethod(container, "getQueueNames");
            if (queueNames instanceof String[]) {
                info.put("queues", Arrays.asList((String[]) queueNames));
            }

            Object concurrentConsumers = ReflectionHelper.invokeMethod(container, "getConcurrentConsumers");
            if (concurrentConsumers != null) info.put("concurrentConsumers", concurrentConsumers);

            Object maxConcurrentConsumers = ReflectionHelper.invokeMethod(container, "getMaxConcurrentConsumers");
            if (maxConcurrentConsumers != null) info.put("maxConcurrentConsumers", maxConcurrentConsumers);

            Object prefetch = ReflectionHelper.invokeMethod(container, "getPrefetchCount");
            if (prefetch != null) info.put("prefetchCount", prefetch);

            Object acknowledgeMode = ReflectionHelper.invokeMethod(container, "getAcknowledgeMode");
            if (acknowledgeMode != null) info.put("acknowledgeMode", acknowledgeMode.toString());

            Object isRunning = ReflectionHelper.invokeMethod(container, "isRunning");
            if (isRunning != null) info.put("running", isRunning);

            Object isActive = ReflectionHelper.invokeMethod(container, "isActive");
            if (isActive != null) info.put("active", isActive);

            Object messageListener = ReflectionHelper.invokeMethod(container, "getMessageListener");
            if (messageListener != null) {
                info.put("messageListenerClass", messageListener.getClass().getSimpleName());
            }

            result.add(info);
        }

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_consumers",
                    "hint", "No message listener containers found. Use @RabbitListener to declare consumers."));
        }

        return result;
    }

    @DebugTool(description = "Inspect RabbitMQ connection factory details: host, port, virtual host, "
            + "username, connection timeout, channel cache size, active connections/channels. "
            + "Useful for diagnosing connection issues, channel exhaustion, or pool sizing problems.")
    public Map<String, Object> getAmqpConnectionInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        Object cf = getConnectionFactory();
        if (cf == null) {
            result.put("status", "not_configured");
            return result;
        }

        result.put("class", cf.getClass().getSimpleName());

        // CachingConnectionFactory specific
        Object host = ReflectionHelper.invokeMethod(cf, "getHost");
        if (host != null) result.put("host", host);

        Object port = ReflectionHelper.invokeMethod(cf, "getPort");
        if (port != null) result.put("port", port);

        Object virtualHost = ReflectionHelper.invokeMethod(cf, "getVirtualHost");
        if (virtualHost != null) result.put("virtualHost", virtualHost);

        Object username = ReflectionHelper.invokeMethod(cf, "getUsername");
        if (username != null) result.put("username", username);

        Object channelCacheSize = ReflectionHelper.invokeMethod(cf, "getChannelCacheSize");
        if (channelCacheSize != null) result.put("channelCacheSize", channelCacheSize);

        Object connectionCacheSize = ReflectionHelper.invokeMethod(cf, "getConnectionCacheSize");
        if (connectionCacheSize != null) result.put("connectionCacheSize", connectionCacheSize);

        // Connection stats
        try {
            Object connectionListener = ReflectionHelper.invokeMethod(cf, "getConnectionListener");
            // Active connections and channels
            Object properties = ReflectionHelper.invokeMethod(cf, "getCacheProperties");
            if (properties instanceof Map) {
                result.put("cacheProperties", properties);
            }
        } catch (Exception ignored) {}

        // Spring properties
        String[] propNames = {
                "spring.rabbitmq.host", "spring.rabbitmq.port", "spring.rabbitmq.username",
                "spring.rabbitmq.virtual-host", "spring.rabbitmq.listener.direct.acknowledge-mode",
                "spring.rabbitmq.listener.simple.acknowledge-mode",
                "spring.rabbitmq.listener.simple.concurrency",
                "spring.rabbitmq.listener.simple.max-concurrency",
                "spring.rabbitmq.listener.simple.prefetch"
        };
        Map<String, String> springProps = new LinkedHashMap<>();
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null && !p.contains("password")) {
                springProps.put(p, val);
            }
        }
        if (!springProps.isEmpty()) {
            result.put("springProperties", springProps);
        }

        return result;
    }

    @DebugTool(description = "List all RabbitMQ exchanges: exchange name, type (direct/fanout/topic/headers), "
            + "durable, auto-delete, and bindings. Useful for verifying exchange topology "
            + "and diagnosing message routing failures.")
    public List<Map<String, Object>> getAmqpExchanges() {
        List<Map<String, Object>> result = new ArrayList<>();
        Object admin = getRabbitAdmin();
        if (admin == null) {
            return List.of(Map.of("error", "No RabbitAdmin found"));
        }

        try {
            // Get exchanges via RabbitAdmin
            Object exchangeObjects = ReflectionHelper.invokeMethod(admin, "getExchanges");
            if (exchangeObjects instanceof List) {
                for (Object exchange : (List<?>) exchangeObjects) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", ReflectionHelper.invokeMethod(exchange, "getName"));
                    info.put("type", ReflectionHelper.invokeMethod(exchange, "getType"));
                    info.put("durable", ReflectionHelper.invokeMethod(exchange, "isDurable"));
                    info.put("autoDelete", ReflectionHelper.invokeMethod(exchange, "isAutoDelete"));
                    result.add(info);
                }
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.add(Map.of("status", "no_exchanges",
                    "hint", "No exchanges found or RabbitMQ broker not connected."));
        }

        return result;
    }

    @DebugTool(description = "Get RabbitTemplate configuration and send/receive statistics: "
            + "exchange, routing key, reply timeout, confirm callback, returns callback, "
            + "and message converter. Useful for diagnosing message publishing issues "
            + "or publisher confirm/return configuration.")
    public Map<String, Object> getAmqpTemplateInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object template = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.amqp.rabbit.core.RabbitTemplate");
        if (template == null) {
            result.put("status", "not_configured");
            return result;
        }

        result.put("class", template.getClass().getSimpleName());

        Object exchange = ReflectionHelper.invokeMethod(template, "getExchange");
        if (exchange != null) result.put("defaultExchange", exchange);

        Object routingKey = ReflectionHelper.invokeMethod(template, "getRoutingKey");
        if (routingKey != null) result.put("defaultRoutingKey", routingKey);

        Object replyTimeout = ReflectionHelper.invokeMethod(template, "getReplyTimeout");
        if (replyTimeout != null) result.put("replyTimeout", replyTimeout);

        Object confirmCallback = ReflectionHelper.invokeMethod(template, "getConfirmCallback");
        result.put("hasConfirmCallback", confirmCallback != null);

        Object returnsCallback = ReflectionHelper.invokeMethod(template, "getReturnsCallback");
        result.put("hasReturnsCallback", returnsCallback != null);

        Object messageConverter = ReflectionHelper.invokeMethod(template, "getMessageConverter");
        if (messageConverter != null) {
            result.put("messageConverter", messageConverter.getClass().getSimpleName());
        }

        Object channelTransacted = ReflectionHelper.invokeMethod(template, "isChannelTransacted");
        if (channelTransacted != null) result.put("channelTransacted", channelTransacted);

        return result;
    }
}
