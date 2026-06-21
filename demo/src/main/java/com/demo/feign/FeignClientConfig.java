package com.demo.feign;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Demo Feign clients for OpenFeignInspector.
 * Two clients: one with direct URL, one for load-balanced service discovery.
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
public class FeignClientConfig {

    @FeignClient(name = "inventory-service", url = "http://localhost:8081",
            fallback = FeignFallbacks.InventoryClientFallback.class)
    public interface InventoryClient {

        @GetMapping("/api/inventory/{sku}")
        Map<String, Object> getStock(@PathVariable("sku") String sku);

        @PostMapping("/api/inventory/reserve")
        Map<String, Object> reserveStock(@RequestBody Map<String, Object> request);

        @GetMapping("/api/inventory")
        List<Map<String, Object>> listInventory();
    }

    @FeignClient(name = "payment-service", url = "http://localhost:8082",
            fallback = FeignFallbacks.PaymentClientFallback.class)
    public interface PaymentClient {

        @PostMapping("/api/payments/process")
        Map<String, Object> processPayment(@RequestBody Map<String, Object> paymentRequest);

        @GetMapping("/api/payments/{transactionId}")
        Map<String, Object> getPaymentStatus(@PathVariable("transactionId") String transactionId);

        @PutMapping("/api/payments/{transactionId}/refund")
        Map<String, Object> refundPayment(@PathVariable("transactionId") String transactionId);
    }
}
