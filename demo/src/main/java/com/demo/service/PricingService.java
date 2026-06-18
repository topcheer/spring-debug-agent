package com.demo.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates an external pricing/inventory microservice call.
 *
 * In a real app this would be a Feign client or RestTemplate call to another service.
 * It deliberately introduces latency and has some pricing logic.
 */
@Service
public class PricingService {

    private final Map<String, BigDecimal> priceCatalog = new ConcurrentHashMap<>();

    public PricingService() {
        priceCatalog.put("LAPTOP-PRO-15", new BigDecimal("2499.00"));
        priceCatalog.put("MOUSE-WIRELESS", new BigDecimal("49.99"));
        priceCatalog.put("KEYBOARD-MECH", new BigDecimal("129.00"));
        priceCatalog.put("MONITOR-4K-27", new BigDecimal("599.00"));
        priceCatalog.put("USB-C-HUB", new BigDecimal("39.99"));
        priceCatalog.put("WEBCAM-HD", new BigDecimal("89.00"));
        priceCatalog.put("HEADSET-PRO", new BigDecimal("199.00"));
        priceCatalog.put("DOCK-STATION", new BigDecimal("349.00"));
    }

    /**
     * Look up price for a SKU. Simulates network latency.
     */
    public BigDecimal getPrice(String sku) {
        // Simulate network call latency
        simulateLatency(50 + (int)(Math.random() * 100));

        BigDecimal price = priceCatalog.get(sku);
        if (price == null) {
            throw new IllegalArgumentException("Unknown SKU: " + sku);
        }
        return price;
    }

    /**
     * Apply tier-based discount.
     */
    public BigDecimal applyDiscount(BigDecimal originalPrice, String customerTier) {
        BigDecimal discountRate = switch (customerTier) {
            case "GOLD" -> new BigDecimal("0.15");
            case "SILVER" -> new BigDecimal("0.10");
            default -> new BigDecimal("0.05");
        };
        BigDecimal discount = originalPrice.multiply(discountRate);
        return originalPrice.subtract(discount);
    }

    /**
     * Validate SKU exists.
     */
    public boolean isValid(String sku) {
        return priceCatalog.containsKey(sku);
    }

    private void simulateLatency(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
