package com.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachine;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * Order lifecycle state machine for StateMachineInspector demo.
 * States: PENDING -> CONFIRMED -> SHIPPED -> DELIVERED
 *          PENDING -> CANCELLED
 */
@Configuration
@EnableStateMachine
public class OrderStateMachineConfig
        extends StateMachineConfigurerAdapter<OrderStateMachineConfig.OrderState, OrderStateMachineConfig.OrderEvent> {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachineConfig.class);

    public enum OrderState {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }

    public enum OrderEvent {
        CONFIRM, SHIP, DELIVER, CANCEL
    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states)
            throws Exception {
        states
            .withStates()
            .initial(OrderState.PENDING)
            .states(EnumSet.allOf(OrderState.class))
            .end(OrderState.DELIVERED)
            .end(OrderState.CANCELLED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions)
            throws Exception {
        transitions
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.CONFIRMED).event(OrderEvent.CONFIRM)
                .and()
            .withExternal()
                .source(OrderState.PENDING).target(OrderState.CANCELLED).event(OrderEvent.CANCEL)
                .and()
            .withExternal()
                .source(OrderState.CONFIRMED).target(OrderState.SHIPPED).event(OrderEvent.SHIP)
                .and()
            .withExternal()
                .source(OrderState.SHIPPED).target(OrderState.DELIVERED).event(OrderEvent.DELIVER);
    }
}
