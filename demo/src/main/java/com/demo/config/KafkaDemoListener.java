package com.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Demo Kafka listener for MessagingInspector.
 */
@Component
public class KafkaDemoListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaDemoListener.class);

    @KafkaListener(topics = "demo-topic", groupId = "demo-group")
    public void handleDemoMessage(String message) {
        log.info("Received Kafka message: {}", message);
    }

    @KafkaListener(topics = "order-events", groupId = "demo-group")
    public void handleOrderEvent(String event) {
        log.info("Order event: {}", event);
    }
}
