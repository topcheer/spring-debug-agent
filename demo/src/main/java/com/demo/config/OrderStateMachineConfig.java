package com.demo.config;

import org.springframework.context.annotation.Configuration;

/**
 * Orders state machine states and events for order lifecycle:
 * PENDING → CONFIRMED → SHIPPED → DELIVERED
 *   ↘ CANCELLED
 */
@Configuration
public class OrderStateMachineConfig {

    public enum OrderState {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }

    public enum OrderEvent {
        CONFIRM, SHIP, DELIVER, CANCEL
    }
}
