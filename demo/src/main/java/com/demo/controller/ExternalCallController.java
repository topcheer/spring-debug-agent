package com.demo.controller;

import com.demo.service.ExternalCallService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Demo controller exposing Resilience4j, Redis, and Kafka endpoints
 * for testing the corresponding inspectors.
 */
@RestController
@RequestMapping("/api")
public class ExternalCallController {

    private final ExternalCallService externalCallService;

    public ExternalCallController(ExternalCallService externalCallService) {
        this.externalCallService = externalCallService;
    }

    @GetMapping("/external/circuit-breaker")
    public Map<String, Object> testCircuitBreaker() {
        try {
            String result = externalCallService.callExternalService();
            return Map.of("status", "ok", "result", result);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @GetMapping("/external/retry")
    public Map<String, Object> testRetry() {
        try {
            String result = externalCallService.callWithRetry();
            return Map.of("status", "ok", "result", result);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @GetMapping("/external/rate-limit")
    public Map<String, Object> testRateLimit() {
        try {
            String result = externalCallService.rateLimitedCall();
            return Map.of("status", "ok", "result", result);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @PostMapping("/redis/cache/{key}")
    public Map<String, Object> setRedis(@PathVariable String key, @RequestBody String value) {
        externalCallService.cacheOrder(Long.parseLong(key), value);
        return Map.of("status", "cached", "key", "order:" + key);
    }

    @GetMapping("/redis/cache/{key}")
    public Map<String, Object> getRedis(@PathVariable String key) {
        String value = externalCallService.getCachedOrder(Long.parseLong(key));
        return Map.of("key", "order:" + key, "value", value != null ? value : "null");
    }

    @PostMapping("/kafka/publish")
    public Map<String, Object> publishKafka(@RequestParam String topic,
                                            @RequestBody String message) {
        externalCallService.publishEvent(topic, message);
        return Map.of("status", "published", "topic", topic);
    }
}
