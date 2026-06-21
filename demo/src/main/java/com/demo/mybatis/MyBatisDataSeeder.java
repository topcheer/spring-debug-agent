package com.demo.mybatis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds MyBatis demo data: creates table and inserts sample products.
 */
@Component
public class MyBatisDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MyBatisDataSeeder.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductMapper productMapper;

    @Override
    public void run(String... args) {
        // Create table
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS mybatis_product (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(200), " +
                "category VARCHAR(100), " +
                "price DOUBLE, " +
                "description VARCHAR(500))");

        // Insert sample data
        if (productMapper.count() == 0) {
            insertProduct("Laptop Pro 16", "Electronics", 2499.00, "High-performance laptop");
            insertProduct("Wireless Mouse", "Electronics", 49.99, "Bluetooth mouse");
            insertProduct("Mechanical Keyboard", "Electronics", 129.00, "RGB keyboard");
            insertProduct("USB-C Hub", "Accessories", 39.99, "7-in-1 hub");
            insertProduct("Monitor Stand", "Accessories", 89.00, "Aluminum stand");
            log.info("MyBatis demo: inserted {} products", productMapper.count());
        }
    }

    private void insertProduct(String name, String category, double price, String desc) {
        Product p = new Product();
        p.setName(name);
        p.setCategory(category);
        p.setPrice(price);
        p.setDescription(desc);
        productMapper.insertProduct(p);
    }
}
