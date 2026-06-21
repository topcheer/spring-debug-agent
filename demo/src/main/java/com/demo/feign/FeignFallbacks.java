package com.demo.feign;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fallback classes for Feign clients.
 * Demonstrates graceful degradation when downstream services are down.
 */
public class FeignFallbacks {

    public static class InventoryClientFallback implements FeignClientConfig.InventoryClient {
        @Override
        public Map<String, Object> getStock(String sku) {
            return Map.of("sku", sku, "quantity", 0, "status", "UNAVAILABLE",
                    "reason", "inventory-service is down");
        }

        @Override
        public Map<String, Object> reserveStock(Map<String, Object> request) {
            return Map.of("status", "FALLBACK", "reason", "inventory-service is down");
        }

        @Override
        public List<Map<String, Object>> listInventory() {
            return Collections.emptyList();
        }
    }

    public static class PaymentClientFallback implements FeignClientConfig.PaymentClient {
        @Override
        public Map<String, Object> processPayment(Map<String, Object> paymentRequest) {
            return Map.of("status", "DECLINED", "reason", "payment-service is down",
                    "fallback", true);
        }

        @Override
        public Map<String, Object> getPaymentStatus(String transactionId) {
            return Map.of("transactionId", transactionId, "status", "UNKNOWN",
                    "reason", "payment-service is down");
        }

        @Override
        public Map<String, Object> refundPayment(String transactionId) {
            return Map.of("transactionId", transactionId, "status", "REFUND_PENDING",
                    "reason", "payment-service is down");
        }
    }
}
