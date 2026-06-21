package com.demo.camel;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Camel routes for CamelInspector demo.
 * Routes are in-memory (timer/mock) — no external systems needed.
 */
@Component
public class DemoCamelRoutes extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(DemoCamelRoutes.class);

    @Override
    public void configure() throws Exception {

        // Route 1: Timer -> Log (generates a message every 10 seconds)
        from("timer://demoTimer?period=10000")
                .routeId("timer-to-log")
                .setBody(constant("Hello from Camel!"))
                .log("Camel route [timer-to-log] fired: ${body}")
                .to("mock://timerOutput");

        // Route 2: Direct endpoint for order processing simulation
        from("direct://processOrder")
                .routeId("order-processing")
                .log("Processing order: ${body}")
                .setHeader("processedAt", simple("${date:now:yyyy-MM-dd HH:mm:ss}"))
                .transform().simple("Order[${body}] processed at ${header.processedAt}")
                .to("mock://orderOutput");

        // Route 3: Content-based routing
        from("direct://routeByType")
                .routeId("content-router")
                .choice()
                    .when(xpath("/order/type = 'URGENT'"))
                        .log("URGENT order received")
                        .to("mock://urgentOrders")
                    .when(xpath("/order/type = 'NORMAL'"))
                        .log("NORMAL order received")
                        .to("mock://normalOrders")
                    .otherwise()
                        .log("Unknown order type")
                        .to("mock://unknownOrders")
                .end();

        log.info("Camel demo routes configured: timer-to-log, order-processing, content-router");
    }
}
