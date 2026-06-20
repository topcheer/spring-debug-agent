package com.demo.es;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchDataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchDataSeeder.class);
    private final ProductRepository repo;

    public ElasticsearchDataSeeder(ProductRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        try {
            if (repo.count() == 0) {
                repo.save(new ProductDocument("p1", "Laptop Pro", 1299.99, "electronics"));
                repo.save(new ProductDocument("p2", "Wireless Mouse", 29.99, "electronics"));
                repo.save(new ProductDocument("p3", "Coffee Mug", 12.50, "kitchen"));
                repo.save(new ProductDocument("p4", "Mechanical Keyboard", 89.00, "electronics"));
                log.info("Seeded 4 product documents into Elasticsearch");
            }
        } catch (Exception e) {
            log.warn("Elasticsearch seed skipped: {}", e.getMessage());
        }
    }
}
