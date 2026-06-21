package com.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Demo @ConfigurationProperties bean for EnvironmentInspector.
 * Binds to demo.order.* properties.
 */
@Configuration
@ConfigurationProperties(prefix = "demo.order")
public class OrderProperties {

    private int maxItemsPerOrder = 100;
    private double taxRate = 0.08;
    private String currency = "USD";
    private boolean enableDiscounts = true;
    private List<String> supportedRegions = List.of("US", "EU", "CN", "JP");
    private Shipping shipping = new Shipping();

    public static class Shipping {
        private String provider = "dhl";
        private int maxWeightKg = 30;
        private boolean insuranceEnabled = true;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getMaxWeightKg() { return maxWeightKg; }
        public void setMaxWeightKg(int maxWeightKg) { this.maxWeightKg = maxWeightKg; }
        public boolean isInsuranceEnabled() { return insuranceEnabled; }
        public void setInsuranceEnabled(boolean insuranceEnabled) { this.insuranceEnabled = insuranceEnabled; }
    }

    // Getters/Setters
    public int getMaxItemsPerOrder() { return maxItemsPerOrder; }
    public void setMaxItemsPerOrder(int maxItemsPerOrder) { this.maxItemsPerOrder = maxItemsPerOrder; }
    public double getTaxRate() { return taxRate; }
    public void setTaxRate(double taxRate) { this.taxRate = taxRate; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isEnableDiscounts() { return enableDiscounts; }
    public void setEnableDiscounts(boolean enableDiscounts) { this.enableDiscounts = enableDiscounts; }
    public List<String> getSupportedRegions() { return supportedRegions; }
    public void setSupportedRegions(List<String> supportedRegions) { this.supportedRegions = supportedRegions; }
    public Shipping getShipping() { return shipping; }
    public void setShipping(Shipping shipping) { this.shipping = shipping; }
}
