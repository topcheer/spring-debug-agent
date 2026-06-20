package com.demo.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Controller
public class OrderGraphQLController {
    private final Map<Long, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(3);

    public OrderGraphQLController() {
        orders.put(1L, Map.of("id", 1, "customer", "Alice", "amount", 99.50, "status", "DELIVERED"));
        orders.put(2L, Map.of("id", 2, "customer", "Bob", "amount", 150.00, "status", "PENDING"));
        orders.put(3L, Map.of("id", 3, "customer", "Charlie", "amount", 42.99, "status", "CANCELLED"));
    }

    @QueryMapping
    public Map<String, Object> orderById(@Argument Long id) {
        return orders.get(id);
    }

    @QueryMapping
    public List<Map<String, Object>> allOrders() {
        return new ArrayList<>(orders.values());
    }

    @MutationMapping
    public Map<String, Object> createOrder(@Argument String customer, @Argument Double amount) {
        long id = idGen.incrementAndGet();
        Map<String, Object> order = Map.of("id", id, "customer", customer, "amount", amount, "status", "PENDING");
        orders.put(id, order);
        return order;
    }
}
