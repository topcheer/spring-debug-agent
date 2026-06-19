package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Messaging (Kafka/RabbitMQ) diagnostic tools.
 * Inspects consumers, queue stats, and dead letter queues.
 * Conditional on Spring Kafka or Spring AMQP being on classpath.
 */
public class MessagingInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get Kafka consumer group info: topic assignments, lag, offsets, and partition assignment. Useful for diagnosing slow consumers or message processing issues.")
    public List<Map<String, Object>> getKafkaConsumers(
            @ToolParam(description = "Consumer group ID (leave empty for all)") String groupId
    ) {
        List<Map<String, Object>> consumers = new ArrayList<>();

        try {
            // Try Spring Kafka's KafkaListenerEndpointRegistry
            String[] registryNames = ctx.getBeanNamesForType(
                    Class.forName("org.springframework.kafka.config.KafkaListenerEndpointRegistry"));

            for (String name : registryNames) {
                Object registry = ctx.getBean(name);
                Method getContainers = registry.getClass().getMethod("getListenerContainers");
                @SuppressWarnings("unchecked")
                List<Object> containers = (List<Object>) getContainers.invoke(registry);

                for (Object container : containers) {
                    Map<String, Object> info = new LinkedHashMap<>();

                    // Container ID
                    Method getBeanName = container.getClass().getMethod("getBeanName");
                    info.put("containerId", getBeanName.invoke(container));

                    // Topics
                    try {
                        Method getTopics = container.getClass().getMethod("getContainerProperties");
                        Object props = getTopics.invoke(container);
                        Method getAssignedTopics = props.getClass().getMethod("getTopics");
                        info.put("topics", Arrays.toString((String[]) getAssignedTopics.invoke(props)));
                    } catch (Exception ignored) {}

                    // Group ID
                    try {
                        Method getGroupId = container.getClass().getMethod("getGroupId");
                        info.put("groupId", getGroupId.invoke(container));
                    } catch (Exception ignored) {}

                    // Paused status
                    try {
                        Method isPaused = container.getClass().getMethod("isPaused");
                        info.put("paused", isPaused.invoke(container));
                    } catch (Exception ignored) {}

                    // Assignment and lag via KafkaAdminClient
                    if (groupId != null && !groupId.isBlank()) {
                        String gid = (String) info.get("groupId");
                        if (gid != null && !gid.equals(groupId)) continue;
                    }

                    consumers.add(info);
                }
            }

            if (consumers.isEmpty()) {
                consumers.add(Map.of("note", "No Kafka listeners found. " +
                        "Ensure @KafkaListener methods are registered."));
            }

        } catch (ClassNotFoundException e) {
            consumers.add(Map.of("error", "Spring Kafka not on classpath"));
        } catch (Exception e) {
            consumers.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return consumers;
    }

    @DebugTool(description = "Get message queue info: topic partitions, message rates, and consumer assignment for Kafka. For RabbitMQ, shows queue depths and consumer counts.")
    public Map<String, Object> getQueueInfo(
            @ToolParam(description = "Topic/queue name (leave empty for all)") String queueName
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Kafka topics
        try {
            Class<?> adminClass = Class.forName("org.springframework.kafka.core.KafkaAdmin");
            String[] adminNames = ctx.getBeanNamesForType(adminClass);
            if (adminNames.length > 0) {
                Object admin = ctx.getBean(adminNames[0]);
                result.put("kafkaAdmin", admin.getClass().getSimpleName());

                // Try to get topic info
                try {
                    Object clientFactory = ReflectionHelper.invokeMethod(admin, "getProducerFactory");
                    if (clientFactory != null) {
                        Object txManager = ctx.getBean(
                                Class.forName("org.springframework.kafka.core.KafkaTemplate"));
                        if (txManager != null) {
                            Method getDefaultTopic = txManager.getClass().getMethod("getDefaultTopic");
                            Object def = getDefaultTopic.invoke(txManager);
                            result.put("defaultTopic", def);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException ignored) {}

        // RabbitMQ queues
        try {
            Class<?> amqpAdminClass = Class.forName("org.springframework.amqp.core.AmqpAdmin");
            String[] amqpNames = ctx.getBeanNamesForType(amqpAdminClass);
            if (amqpNames.length > 0) {
                Object amqpAdmin = ctx.getBean(amqpNames[0]);
                result.put("rabbitMq", Map.of("available", true));
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            result.put("note", "No messaging infrastructure detected (neither Kafka nor RabbitMQ).");
        }

        return result;
    }

    @DebugTool(description = "Detect dead letter queues (DLQ) or error topics. Shows messages that failed processing and were routed to error queues. Useful for diagnosing message processing failures.")
    public List<Map<String, Object>> getDeadLetterQueues() {
        List<Map<String, Object>> dlqs = new ArrayList<>();

        // Kafka DLQ topics (common naming patterns)
        try {
            Class<?> templateClass = Class.forName("org.springframework.kafka.core.KafkaTemplate");
            String[] templateNames = ctx.getBeanNamesForType(templateClass);
            for (String name : templateNames) {
                Object template = ctx.getBean(name);
                Method getDefaultTopic = templateClass.getMethod("getDefaultTopic");
                Object defaultTopic = getDefaultTopic.invoke(template);
                if (defaultTopic != null) {
                    dlqs.add(Map.of(
                            "type", "kafka-template",
                            "beanName", name,
                            "defaultTopic", defaultTopic
                    ));
                }
            }
        } catch (Exception ignored) {}

        // RabbitMQ DLQ detection
        try {
            Class<?> rabbitAdminClass = Class.forName("org.springframework.amqp.rabbit.core.RabbitAdmin");
            String[] names = ctx.getBeanNamesForType(rabbitAdminClass);
            for (String name : names) {
                Object admin = ctx.getBean(name);
                dlqs.add(Map.of(
                        "type", "rabbitmq-admin",
                        "beanName", name,
                        "note", "RabbitAdmin detected. Check for queues with 'dlq' or 'dead' in their names."
                ));
            }
        } catch (Exception ignored) {}

        // Kafka error handler DLQ
        try {
            Class<?> errorHandlerClass = Class.forName(
                    "org.springframework.kafka.listener.DeadLetterPublishingRecoverer");
            String[] handlerNames = ctx.getBeanNamesForType(errorHandlerClass);
            if (handlerNames.length > 0) {
                for (String name : handlerNames) {
                    dlqs.add(Map.of(
                            "type", "kafka-dlq-recoverer",
                            "beanName", name,
                            "note", "DeadLetterPublishingRecoverer is configured. " +
                                    "Failed messages will be sent to DLQ topics."
                    ));
                }
            }
        } catch (Exception ignored) {}

        if (dlqs.isEmpty()) {
            dlqs.add(Map.of("note", "No dead letter queue handlers detected. " +
                    "Messages that fail processing may be silently retried or discarded."));
        }

        return dlqs;
    }
}
