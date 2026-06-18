package com.demo.repository;

import com.demo.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerId(Long customerId);

    List<Order> findByStatus(String status);

    /**
     * Deliberately slow query (no index on total_amount, uses function in WHERE).
     * Good for demonstrating slow query detection.
     */
    @Query("SELECT o FROM Order o WHERE o.totalAmount > :minAmount ORDER BY o.totalAmount DESC")
    List<Order> findExpensiveOrders(@Param("minAmount") BigDecimal minAmount);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
    long countByStatus(@Param("status") String status);
}
