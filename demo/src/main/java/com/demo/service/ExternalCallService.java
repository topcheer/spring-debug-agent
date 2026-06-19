package com.demo.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo service exercising Resilience4j, Redis, and Kafka.
 * Provides endpoints for testing ResilienceInspector and RedisInspector.
 */
@Service
public class ExternalCallService {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallService.class);

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AtomicInteger callCount = new AtomicInteger(0);

    public ExternalCallService(StringRedisTemplate redisTemplate,
                               KafkaTemplate<String, String> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @CircuitBreaker(name = "orderService", fallbackMethod = "fallbackCall")
    public String callExternalService() {
        int count = callCount.incrementAndGet();
        if (count % 3 == 0) {
            throw new RuntimeException("Simulated failure on call #" + count);
        }
        return "Success on call #" + count;
    }

    private String fallbackCall(Exception e) {
        return "Fallback: " + e.getMessage();
    }

    @Retry(name = "orderRetry")
    public String callWithRetry() {
        int count = callCount.incrementAndGet();
        if (count % 4 != 0) {
            throw new RuntimeException("Retry needed on call #" + count);
        }
        return "Success after retries on call #" + count;
    }

    @RateLimiter(name = "orderRateLimit")
    public String rateLimitedCall() {
        return "Rate-limited call at " + java.time.Instant.now();
    }

    // Redis operations
    public void cacheOrder(Long orderId, String orderJson) {
        redisTemplate.opsForValue().set("order:" + orderId, orderJson, Duration.ofMinutes(10));
    }

    public String getCachedOrder(Long orderId) {
        return redisTemplate.opsForValue().get("order:" + orderId);
    }

    public void publishEvent(String topic, String event) {
        kafkaTemplate.send(topic, event);
    }
}
