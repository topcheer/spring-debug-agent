package com.demo.rocketmq;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RocketMQ demo: producer + consumer.
 * Template/listener beans are visible to RocketMqInspector even without a running NameServer.
 */
@RestController
public class RocketMqDemoController {

    private static final Logger log = LoggerFactory.getLogger(RocketMqDemoController.class);

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @GetMapping("/rocketmq/send")
    public String sendMessage(@RequestParam(defaultValue = "Hello RocketMQ") String message) {
        if (rocketMQTemplate == null) {
            return "RocketMQ not enabled (set rocketmq.enabled=true with a running NameServer)";
        }
        try {
            rocketMQTemplate.syncSend("demo-topic", MessageBuilder.withPayload(message).build());
            return "Message sent to demo-topic: " + message;
        } catch (Exception e) {
            log.warn("RocketMQ send failed: {}", e.getMessage());
            return "Send failed (NameServer not running): " + e.getMessage();
        }
    }

    /**
     * RocketMQ consumer — visible to RocketMqInspector.
     * Only activates when rocketmq.enabled=true (to avoid startup crash without a NameServer).
     */
    @org.springframework.stereotype.Component
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
    @RocketMQMessageListener(
            topic = "demo-topic",
            consumerGroup = "demo-consumer-group",
            consumeMode = ConsumeMode.CONCURRENTLY,
            messageModel = MessageModel.CLUSTERING
    )
    public static class DemoMessageListener implements RocketMQListener<String> {

        private static final Logger log = LoggerFactory.getLogger(DemoMessageListener.class);

        @Override
        public void onMessage(String message) {
            log.info("RocketMQ received: {}", message);
        }
    }
}
