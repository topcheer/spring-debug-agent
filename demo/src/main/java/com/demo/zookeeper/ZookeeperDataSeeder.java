package com.demo.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds ZNode data into the embedded ZK for ZookeeperInspector demo.
 * Creates nodes under the demo-app namespace.
 */
@Component
@Order(20)
public class ZookeeperDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperDataSeeder.class);

    @Autowired
    private CuratorFramework curatorFramework;

    @Override
    public void run(String... args) throws Exception {
        Thread.sleep(2000); // Wait for curator to connect

        try {
            // Create config nodes
            curatorFramework.create().creatingParentsIfNeeded()
                    .forPath("/config/app-name", "order-management-demo".getBytes());
            curatorFramework.create()
                    .forPath("/config/version", "0.8.1".getBytes());
            curatorFramework.create()
                    .forPath("/config/max-connections", "100".getBytes());

            // Create service registry nodes
            curatorFramework.create().creatingParentsIfNeeded()
                    .forPath("/services/greeting-service/provider-1",
                            "{\"host\":\"127.0.0.1\",\"port\":20880}".getBytes());

            // Create ephemeral lock node
            curatorFramework.create()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath("/locks/order-lock-001",
                            "locked-by-instance-1".getBytes());

            log.info("Zookeeper demo: seeded config, services, and lock nodes");
        } catch (Exception e) {
            log.warn("Zookeeper demo: seed failed (nodes may already exist): {}", e.getMessage());
        }
    }
}
