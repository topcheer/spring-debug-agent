package com.demo.service;

import com.demo.entity.Order;
import com.demo.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Background scheduled tasks for order management.
 *
 * Demonstrates @Scheduled tasks for the TaskInspector tool:
 * - Daily revenue report
 * - Stale order cleanup
 * - Order count metrics
 */
@Service
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    @Autowired
    private OrderRepository orderRepository;

    /**
     * Generate revenue report every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)  // 5 minutes
    public void generateRevenueReport() {
        try {
            List<Order> confirmedOrders = orderRepository.findByStatus("CONFIRMED");
            BigDecimal totalRevenue = confirmedOrders.stream()
                    .map(Order::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("Revenue report: {} confirmed orders, total revenue: {}",
                    confirmedOrders.size(), totalRevenue);
        } catch (Exception e) {
            log.error("Revenue report generation failed", e);
        }
    }

    /**
     * Check for stale orders every 2 minutes.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 10_000)  // 2 minutes, delay 10s
    public void checkStaleOrders() {
        try {
            List<Order> pending = orderRepository.findByStatus("PENDING");
            if (!pending.isEmpty()) {
                log.warn("Found {} stale pending orders", pending.size());
            }
        } catch (Exception e) {
            log.error("Stale order check failed", e);
        }
    }

    /**
     * Cron-based: every weekday at 9am (won't actually trigger in demo,
     * but shows up in TaskInspector listing).
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void dailySummaryReport() {
        log.info("Daily summary report triggered at {}", LocalDateTime.now());
    }
}
