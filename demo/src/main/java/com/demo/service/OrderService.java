package com.demo.service;

import com.demo.entity.Customer;
import com.demo.entity.Order;
import com.demo.entity.OrderItem;
import com.demo.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Core order processing service.
 *
 * Contains several interesting methods for the debug agent to inspect:
 * - createOrder: multi-step transaction with validation, pricing, credit check
 * - findSlowOrders: queries that can be slow
 * - processOrderAsync: async processing
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private Executor taskExecutor;

    /**
     * Create a new order. This is the main flow with multiple steps.
     *
     * Good for WatchPoint monitoring — set a watch point here to see
     * the arguments and return value on each call.
     */
    @Transactional
    public Order createOrder(Long customerId, List<OrderItemRequest> itemRequests) {
        log.info("Creating order for customer {} with {} items", customerId, itemRequests.size());

        // Step 1: Validate customer exists and has credit
        Customer customer = customerService.getCustomer(customerId);
        if (customer == null) {
            throw new IllegalArgumentException("Customer not found: " + customerId);
        }

        // Step 2: Build order items with pricing
        Order order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setCustomerId(customerId);

        for (OrderItemRequest req : itemRequests) {
            BigDecimal price = pricingService.getPrice(req.getSku());
            BigDecimal discountedPrice = pricingService.applyDiscount(price, customer.getTier());

            OrderItem item = new OrderItem();
            item.setProductName(req.getProductName());
            item.setSku(req.getSku());
            item.setQuantity(req.getQuantity());
            item.setUnitPrice(discountedPrice);

            order.addItem(item);
        }

        // Step 3: Check credit before proceeding
        boolean hasCredit = customerService.checkCredit(customerId, order.getTotalAmount());
        if (!hasCredit) {
            order.setStatus("REJECTED_INSUFFICIENT_CREDIT");
            return orderRepository.save(order);
        }

        // Step 4: Save order
        order.setStatus("CONFIRMED");
        Order saved = orderRepository.save(order);

        // Step 5: Deduct credit (async)
        CompletableFuture.runAsync(() -> {
            try {
                customerService.deductCredit(customerId, order.getTotalAmount());
            } catch (Exception e) {
                log.error("Failed to deduct credit for order {}", order.getOrderNumber(), e);
            }
        }, taskExecutor);

        // Step 6: Sometimes upgrade tier (can cause NPE — intentional bug)
        if (customer.getCreditBalance().compareTo(new BigDecimal("5000")) < 0) {
            try {
                customerService.upgradeTier(customerId);
            } catch (Exception e) {
                log.warn("Tier upgrade failed (known bug): {}", e.getMessage());
            }
        }

        log.info("Order {} created successfully. Total: {}", saved.getOrderNumber(), saved.getTotalAmount());
        return saved;
    }

    /**
     * Find expensive orders. Can be slow with large datasets.
     */
    public List<Order> findExpensiveOrders(BigDecimal minAmount) {
        return orderRepository.findExpensiveOrders(minAmount);
    }

    /**
     * Find all orders for a customer.
     */
    public List<Order> getOrdersByCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    /**
     * Get order by ID.
     */
    public Order getOrder(Long id) {
        return orderRepository.findById(id).orElse(null);
    }

    /**
     * Get all orders.
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    /**
     * Count orders by status.
     */
    public long countByStatus(String status) {
        return orderRepository.countByStatus(status);
    }

    /**
     * Internal state — inspectable via get_bean_field_value.
     */
    private int maxRetryAttempts = 3;
    private String processingMode = "SYNC";

    // --- DTO ---

    public static class OrderItemRequest {
        private String sku;
        private String productName;
        private int quantity;

        public OrderItemRequest() {}

        public OrderItemRequest(String sku, String productName, int quantity) {
            this.sku = sku;
            this.productName = productName;
            this.quantity = quantity;
        }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }
}
