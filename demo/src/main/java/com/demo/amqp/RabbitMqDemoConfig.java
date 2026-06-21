package com.demo.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ demo: declares queues, exchanges, bindings, and a listener.
 * Works even without a running broker — the inspector reads bean definitions.
 */
@Configuration
public class RabbitMqDemoConfig {

    public static final String EXCHANGE = "demo.exchange";
    public static final String QUEUE_ORDERS = "demo.queue.orders";
    public static final String QUEUE_NOTIFICATIONS = "demo.queue.notifications";
    public static final String ROUTING_KEY = "demo.routing";

    @Bean
    public TopicExchange demoExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue ordersQueue() {
        return QueueBuilder.durable(QUEUE_ORDERS).build();
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATIONS).build();
    }

    @Bean
    public Binding ordersBinding(Queue ordersQueue, TopicExchange demoExchange) {
        return BindingBuilder.bind(ordersQueue).to(demoExchange).with("demo.orders.#");
    }

    @Bean
    public Binding notificationsBinding(Queue notificationsQueue, TopicExchange demoExchange) {
        return BindingBuilder.bind(notificationsQueue).to(demoExchange).with("demo.notifications.#");
    }

    /**
     * Listener for orders queue — will be visible in AmqpInspector.getAmqpConsumers().
     */
    @org.springframework.stereotype.Component
    public static class OrderMessageListener {

        private static final Logger log = LoggerFactory.getLogger(OrderMessageListener.class);

        @RabbitListener(queues = QUEUE_ORDERS)
        public void handleOrder(String message) {
            log.info("RabbitMQ received order: {}", message);
        }
    }
}
