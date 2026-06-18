package com.demo.service;

import com.demo.entity.Customer;
import com.demo.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PricingService pricingService;

    /**
     * Check if customer has enough credit balance.
     */
    public boolean checkCredit(Long customerId, BigDecimal requiredAmount) {
        Optional<Customer> customer = customerRepository.findById(customerId);
        if (customer.isEmpty()) {
            throw new IllegalArgumentException("Customer not found: " + customerId);
        }
        Customer c = customer.get();
        log.debug("Checking credit for {}: has {}, needs {}", c.getName(), c.getCreditBalance(), requiredAmount);
        return c.getCreditBalance().compareTo(requiredAmount) >= 0;
    }

    /**
     * Deduct credit from customer after order.
     */
    @Transactional
    public void deductCredit(Long customerId, BigDecimal amount) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        if (customer.getCreditBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient credit for customer " + customer.getName());
        }

        BigDecimal newBalance = customer.getCreditBalance().subtract(amount);
        customer.setCreditBalance(newBalance);

        // Upgrade tier based on total deductions
        if (newBalance.compareTo(new BigDecimal("5000")) < 0) {
            customer.setTier("GOLD");
        } else if (newBalance.compareTo(new BigDecimal("8000")) < 0) {
            customer.setTier("SILVER");
        }

        customerRepository.save(customer);
        log.info("Deducted {} from customer {}. New balance: {}", amount, customer.getName(), newBalance);
    }

    /**
     * Upgrade customer tier — sometimes throws NPE (intentional bug for debugging).
     */
    public void upgradeTier(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        // BUG: Sometimes customer.getTags() is null and this throws NPE
        customer.getTags().add("VIP");

        if (ThreadLocalRandom.current().nextDouble() > 0.7) {
            customer.setTier("GOLD");
        }
        customerRepository.save(customer);
    }

    public Customer getCustomer(Long id) {
        return customerRepository.findById(id).orElse(null);
    }
}
