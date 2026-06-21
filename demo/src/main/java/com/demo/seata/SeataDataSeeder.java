package com.demo.seata;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Creates Seata demo tables.
 */
@Component
public class SeataDataSeeder implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS seata_orders (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "customer_name VARCHAR(200), " +
                "product VARCHAR(200), " +
                "quantity INT, " +
                "status VARCHAR(50))");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS seata_inventory (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "product VARCHAR(200), " +
                "deducted_qty INT)");

        // Seata undo_log table
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS undo_log (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "branch_id BIGINT, " +
                "xid VARCHAR(100), " +
                "context VARCHAR(128), " +
                "rollback_info LONGBLOB, " +
                "log_status INT, " +
                "log_created VARCHAR(50), " +
                "log_modified VARCHAR(50))");
    }
}
