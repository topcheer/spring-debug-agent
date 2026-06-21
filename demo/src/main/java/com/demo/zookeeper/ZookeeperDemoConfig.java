package com.demo.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Zookeeper / Curator demo configuration.
 * Creates a CuratorFramework bean visible to ZookeeperInspector.
 * Depends on EmbeddedZkServer's TestingServer so the ZK is running first.
 */
@Configuration
public class ZookeeperDemoConfig {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperDemoConfig.class);

    @Bean(initMethod = "start", destroyMethod = "close")
    public CuratorFramework curatorFramework(EmbeddedZkServer embeddedZkServer) {
        // TestingServer is already started via @Bean, ZK is ready
        CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString("localhost:2181")
                .sessionTimeoutMs(30000)
                .connectionTimeoutMs(10000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .namespace("demo-app")
                .build();
        log.info("Curator client created: connectString=localhost:2181, namespace=demo-app");
        return client;
    }
}
