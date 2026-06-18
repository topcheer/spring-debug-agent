package com.demo.controller;

import com.demo.entity.Order;
import com.demo.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    @Autowired
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        try {
            log.info("Creating order for customer {}", request.getCustomerId());

            List<OrderService.OrderItemRequest> items = request.getItems().stream()
                    .map(i -> new OrderService.OrderItemRequest(
                            i.getSku(), i.getProductName(), i.getQuantity()))
                    .toList();

            Order order = orderService.createOrder(request.getCustomerId(), items);
            return ResponseEntity.ok(Map.of(
                    "order", order,
                    "message", "Order created successfully"
            ));
        } catch (Exception e) {
            log.error("Error creating order", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOrder(@PathVariable Long id) {
        Order order = orderService.getOrder(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Order>> getOrdersByCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(orderService.getOrdersByCustomer(customerId));
    }

    @GetMapping("/expensive")
    public ResponseEntity<List<Order>> findExpensiveOrders(
            @RequestParam(defaultValue = "500") double minAmount) {
        return ResponseEntity.ok(orderService.findExpensiveOrders(BigDecimal.valueOf(minAmount)));
    }

    // --- DTO ---

    public static class CreateOrderRequest {
        private Long customerId;
        private List<ItemRequest> items;

        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public List<ItemRequest> getItems() { return items; }
        public void setItems(List<ItemRequest> items) { this.items = items; }
    }

    public static class ItemRequest {
        private String sku;
        private String productName;
        private int quantity;

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }
}
