package com.demo.sentinel;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sentinel demo: demonstrates @SentinelResource for flow control and circuit breaking.
 * Sentinel works standalone — no external server needed.
 */
@Service
public class SentinelDemoService {

    private static final Logger log = LoggerFactory.getLogger(SentinelDemoService.class);

    /**
     * Flow-controlled method — SentinelInspector can see this resource.
     */
    @SentinelResource(value = "queryOrder", blockHandler = "queryOrderBlocked")
    public String queryOrder(String orderId) {
        log.info("Sentinel: processing order query for {}", orderId);
        return "Order[" + orderId + "] = 2x USB-C Hub, total $79.98";
    }

    /**
     * Circuit breaker demo — if this method throws repeatedly, Sentinel trips the breaker.
     */
    @SentinelResource(value = "callPayment", fallback = "paymentFallback")
    public String callPayment(String orderId) {
        // Simulate a flaky payment service
        if (Math.random() < 0.3) {
            throw new RuntimeException("Payment service timeout");
        }
        return "Payment success for " + orderId;
    }

    /** Block handler for queryOrder */
    public String queryOrderBlocked(String orderId, BlockException ex) {
        log.warn("Sentinel blocked queryOrder for {}: {}", orderId, ex.getClass().getSimpleName());
        return "Request blocked by Sentinel: " + ex.getClass().getSimpleName();
    }

    /** Fallback for callPayment */
    public String paymentFallback(String orderId, Throwable t) {
        log.warn("Sentinel fallback for callPayment[{}]: {}", orderId, t.getMessage());
        return "Payment service unavailable, fallback activated";
    }
}
