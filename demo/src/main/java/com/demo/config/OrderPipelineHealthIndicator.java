package com.demo.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Configuration;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom health indicator for the demo order processing pipeline.
 * Demonstrates HealthInspector's get_health_component_detail tool.
 */
@Configuration
public class OrderPipelineHealthIndicator implements HealthIndicator {

    private final AtomicLong processedOrders = new AtomicLong(0);
    private final AtomicLong failedOrders = new AtomicLong(0);

    @Override
    public Health health() {
        long processed = processedOrders.get();
        long failed = failedOrders.get();
        double failureRate = processed > 0 ? (double) failed / processed : 0.0;

        Health.Builder builder = failureRate > 0.1 ? Health.down() : Health.up();
        return builder
                .withDetail("processedOrders", processed)
                .withDetail("failedOrders", failed)
                .withDetail("failureRate", String.format("%.2f%%", failureRate * 100))
                .withDetail("pipeline", "order-processing")
                .withDetail("lastCheck", java.time.Instant.now().toString())
                .build();
    }

    public void recordSuccess() {
        processedOrders.incrementAndGet();
    }

    public void recordFailure() {
        failedOrders.incrementAndGet();
        processedOrders.incrementAndGet();
    }
}
