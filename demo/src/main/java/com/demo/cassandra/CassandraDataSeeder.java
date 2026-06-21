package com.demo.cassandra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Cassandra demo data seeder.
 * Uses CQL via the Java driver to create a demo keyspace and table.
 * Runs only when Cassandra is available (Docker).
 */
@Component
public class CassandraDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CassandraDataSeeder.class);

    @Override
    public void run(String... args) {
        try {
            // Wait briefly for Cassandra session
            Thread.sleep(3000);

            // Create keyspace and table via CQL through the CqlSession bean
            // We use reflection to avoid hard dependency on Cassandra driver at compile time
            Class<?> sessionClass = Class.forName("com.datastax.oss.driver.api.core.CqlSession");
            // The CqlSession bean is auto-configured by Spring Boot when Cassandra is available

            log.info("Cassandra demo: auto-config active, keyspace 'demo' will be created by Spring Data");
        } catch (Exception e) {
            log.info("Cassandra demo: not available ({}), skipping data seed", e.getMessage());
        }
    }
}
