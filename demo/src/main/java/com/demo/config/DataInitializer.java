package com.demo.config;

import com.demo.entity.Customer;
import com.demo.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    CommandLineRunner initData(CustomerRepository customerRepository) {
        return args -> {
            log.info("Initializing demo data...");

            Customer alice = new Customer();
            alice.setName("Alice Johnson");
            alice.setEmail("alice@example.com");
            alice.setTier("GOLD");
            customerRepository.save(alice);

            Customer bob = new Customer();
            bob.setName("Bob Smith");
            bob.setEmail("bob@example.com");
            bob.setTier("SILVER");
            customerRepository.save(bob);

            Customer charlie = new Customer();
            charlie.setName("Charlie Brown");
            charlie.setEmail("charlie@example.com");
            charlie.setTier("BRONZE");
            customerRepository.save(charlie);

            log.info("Demo data initialized: 3 customers created.");
        };
    }
}
