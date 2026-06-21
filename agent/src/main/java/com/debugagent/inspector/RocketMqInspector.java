package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * RocketMQ diagnostic tools.
 * Inspects RocketMQ producer, consumer, topic, and message trace information.
 * Conditional on rocketmq-spring-boot-starter being on classpath.
 */
public class RocketMqInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Inspect RocketMQ producer configuration: producer group, name server address, "
            + "send message timeout, retry times, compress level, max message size, and message type. "
            + "Useful for diagnosing message send failures or timeout configuration issues.")
    public Map<String, Object> getRocketMqProducerInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Try RocketMQTemplate
        Object template = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.rocketmq.spring.core.RocketMQTemplate");
        if (template == null) {
            result.put("status", "not_configured");
            result.put("hint", "No RocketMQTemplate found. Add rocketmq-spring-boot-starter.");
            return result;
        }

        result.put("templateClass", template.getClass().getSimpleName());

        Object producer = ReflectionHelper.invokeMethod(template, "getProducer");
        if (producer != null) {
            Object producerGroup = ReflectionHelper.invokeMethod(producer, "getProducerGroup");
            if (producerGroup != null) result.put("producerGroup", producerGroup);

            Object namesrvAddr = ReflectionHelper.invokeMethod(producer, "getNamesrvAddr");
            if (namesrvAddr != null) result.put("nameServer", namesrvAddr);

            Object sendMsgTimeout = ReflectionHelper.invokeMethod(producer, "getSendMsgTimeout");
            if (sendMsgTimeout != null) result.put("sendTimeoutMs", sendMsgTimeout);

            Object retryTimes = ReflectionHelper.invokeMethod(producer, "getRetryTimesWhenSendFailed");
            if (retryTimes != null) result.put("retryTimesWhenSendFailed", retryTimes);

            Object retryTimesAsync = ReflectionHelper.invokeMethod(producer, "getRetryTimesWhenSendAsyncFailed");
            if (retryTimesAsync != null) result.put("retryTimesWhenAsyncFailed", retryTimesAsync);

            Object maxMessageSize = ReflectionHelper.invokeMethod(producer, "getMaxMessageSize");
            if (maxMessageSize != null) result.put("maxMessageSize", maxMessageSize);

            Object compressLevel = ReflectionHelper.invokeMethod(producer, "getCompressMsgBodyOverHowmuch");
            if (compressLevel != null) result.put("compressThreshold", compressLevel);

            // Instance info
            Object instanceName = ReflectionHelper.invokeMethod(producer, "getInstanceName");
            if (instanceName != null) result.put("instanceName", instanceName);

            Object clientIP = ReflectionHelper.invokeMethod(producer, "getClientIP");
            if (clientIP != null) result.put("clientIP", clientIP);
        }

        // Spring properties
        Map<String, String> props = new LinkedHashMap<>();
        String[] propNames = {
                "rocketmq.name-server", "rocketmq.producer.group",
                "rocketmq.producer.send-message-timeout", "rocketmq.producer.retry-times-when-send-failed",
                "rocketmq.producer.max-message-size", "rocketmq.producer.compress-message-body-threshold"
        };
        for (String p : propNames) {
            String val = ctx.getEnvironment().getProperty(p);
            if (val != null) props.put(p.replace("rocketmq.", ""), val);
        }
        if (!props.isEmpty()) result.put("springProperties", props);

        return result;
    }

    @DebugTool(description = "Inspect RocketMQ consumer configuration: consumer group, name server, "
            + "consume mode (CONCURRENTLY/ORDERLY), message model (CLUSTERING/BROADCASTING), "
            + "consume thread pool size, consume timeout, and subscription table. "
            + "Useful for diagnosing consumer not receiving messages or consumption lag.")
    public List<Map<String, Object>> getRocketMqConsumers() {
        List<Map<String, Object>> result = new ArrayList<>();

        // Try to find RocketMQPushConsumer beans or @RocketMQMessageListener annotated containers
        List<?> listeners = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer");
        if (listeners.isEmpty()) {
            // Try inner class
            listeners = ReflectionHelper.getBeansOfType(ctx,
                    "org.apache.rocketmq.spring.core.RocketMQListener");
        }

        if (listeners.isEmpty()) {
            result.add(Map.of("status", "no_consumers",
                    "hint", "No RocketMQ consumers found. Use @RocketMQMessageListener to declare consumers."));
            return result;
        }

        for (Object container : listeners) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("class", container.getClass().getSimpleName());

            Object consumerGroup = ReflectionHelper.invokeMethod(container, "getConsumerGroup");
            if (consumerGroup == null) consumerGroup = ReflectionHelper.getFieldValue(container, "consumerGroup");
            if (consumerGroup != null) info.put("consumerGroup", consumerGroup);

            Object nameServer = ReflectionHelper.invokeMethod(container, "getNameServer");
            if (nameServer == null) nameServer = ReflectionHelper.getFieldValue(container, "nameServer");
            if (nameServer != null) info.put("nameServer", nameServer);

            Object topic = ReflectionHelper.invokeMethod(container, "getTopic");
            if (topic == null) topic = ReflectionHelper.getFieldValue(container, "topic");
            if (topic != null) info.put("topic", topic);

            Object consumeMode = ReflectionHelper.invokeMethod(container, "getConsumeMode");
            if (consumeMode == null) consumeMode = ReflectionHelper.getFieldValue(container, "consumeMode");
            if (consumeMode != null) info.put("consumeMode", consumeMode.toString());

            Object messageModel = ReflectionHelper.invokeMethod(container, "getMessageModel");
            if (messageModel == null) messageModel = ReflectionHelper.getFieldValue(container, "messageModel");
            if (messageModel != null) info.put("messageModel", messageModel.toString());

            Object consumeThreadMax = ReflectionHelper.invokeMethod(container, "getConsumeThreadMax");
            if (consumeThreadMax == null) consumeThreadMax = ReflectionHelper.getFieldValue(container, "consumeThreadMax");
            if (consumeThreadMax != null) info.put("consumeThreadMax", consumeThreadMax);

            Object consumeTimeout = ReflectionHelper.invokeMethod(container, "getConsumeTimeout");
            if (consumeTimeout != null) info.put("consumeTimeout", consumeTimeout);

            Object isRunning = ReflectionHelper.invokeMethod(container, "isRunning");
            if (isRunning != null) info.put("running", isRunning);

            result.add(info);
        }

        return result;
    }

    @DebugTool(description = "Inspect RocketMQ consumer subscription details: topic, sub-expression (tag filter), "
            + "and subscription class. Useful for verifying tag-based filtering or "
            + "diagnosing messages being filtered incorrectly.")
    public Map<String, Object> getRocketMqSubscriptions() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<?> containers = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer");

        if (containers.isEmpty()) {
            result.put("status", "no_subscriptions");
            return result;
        }

        Map<String, String> subTable = new LinkedHashMap<>();
        for (Object container : containers) {
            Object topic = ReflectionHelper.getFieldValue(container, "topic");
            Object selectorExpression = ReflectionHelper.getFieldValue(container, "selectorExpression");

            String topicStr = topic != null ? topic.toString() : "unknown";
            String tagStr = selectorExpression != null ? selectorExpression.toString() : "*";
            subTable.put(topicStr, tagStr);
        }
        result.put("subscriptions", subTable);

        return result;
    }

    @DebugTool(description = "Check RocketMQ name server connectivity and broker info. "
            + "Reports whether the name server is reachable, and if brokers are discovered. "
            + "Useful for diagnosing producer/consumer startup failures due to connectivity issues.")
    public Map<String, Object> getRocketMqServerInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        Object template = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.rocketmq.spring.core.RocketMQTemplate");
        if (template == null) {
            result.put("status", "not_configured");
            return result;
        }

        Object producer = ReflectionHelper.invokeMethod(template, "getProducer");
        if (producer != null) {
            Object nameServer = ReflectionHelper.invokeMethod(producer, "getNamesrvAddr");
            result.put("nameServer", nameServer);

            Object instanceName = ReflectionHelper.invokeMethod(producer, "getInstanceName");
            result.put("instanceName", instanceName);

            // Try to get MQClientInstance for more details
            Object mqClientFactory = ReflectionHelper.invokeMethod(producer, "getMQClientFactory");
            if (mqClientFactory != null) {
                result.put("clientFactoryClass", mqClientFactory.getClass().getSimpleName());

                // Try to get broker info
                try {
                    Object topicRouteData = ReflectionHelper.invokeMethod(mqClientFactory, "getMQClientAPIImpl");
                    if (topicRouteData != null) {
                        result.put("clientApiClass", topicRouteData.getClass().getSimpleName());
                    }
                } catch (Exception ignored) {}

                // Client ID
                Object clientId = ReflectionHelper.invokeMethod(mqClientFactory, "getClientId");
                if (clientId != null) result.put("clientId", clientId);
            }
        }

        // Spring config
        String nameServer = ctx.getEnvironment().getProperty("rocketmq.name-server");
        if (nameServer != null) result.put("configuredNameServer", nameServer);

        Object producerGroup = ctx.getEnvironment().getProperty("rocketmq.producer.group");
        if (producerGroup != null) result.put("configuredProducerGroup", producerGroup);

        return result;
    }

    @DebugTool(description = "Get RocketMQ transaction listener / checker configuration: "
            + "transaction producer group, transaction listener implementation, and check thread pool. "
            + "Useful for diagnosing transactional message issues in RocketMQ applications.")
    public Map<String, Object> getRocketMqTransactionInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        // TransactionMQProducer
        Object template = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.apache.rocketmq.spring.core.RocketMQTemplate");
        if (template == null) {
            result.put("status", "not_configured");
            return result;
        }

        Object producer = ReflectionHelper.invokeMethod(template, "getProducer");
        if (producer != null) {
            result.put("producerClass", producer.getClass().getSimpleName());
            result.put("isTransactionProducer",
                    producer.getClass().getSimpleName().contains("TransactionMQProducer"));

            Object checkThreadPool = ReflectionHelper.invokeMethod(producer, "getCheckRequestHoldMax");
            if (checkThreadPool != null) result.put("checkRequestHoldMax", checkThreadPool);
        }

        // TransactionListener
        List<?> listeners = ReflectionHelper.getBeansOfType(ctx,
                "org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener");
        if (!listeners.isEmpty()) {
            List<String> listenerClasses = new ArrayList<>();
            for (Object l : listeners) {
                listenerClasses.add(l.getClass().getSimpleName());
            }
            result.put("transactionListeners", listenerClasses);
        }

        // @RocketMQTransactionListener beans
        try {
            var beans = ctx.getBeansWithAnnotation(
                    (Class<? extends java.lang.annotation.Annotation>)
                            (Class<?>) Class.forName("org.apache.rocketmq.spring.annotation.RocketMQTransactionListener"));
            if (!beans.isEmpty()) {
                List<String> txListeners = new ArrayList<>();
                for (var entry : beans.entrySet()) {
                    txListeners.add(entry.getKey() + ": " + entry.getValue().getClass().getSimpleName());
                }
                result.put("rocketMqTransactionListeners", txListeners);
            }
        } catch (Exception ignored) {}

        return result;
    }
}
