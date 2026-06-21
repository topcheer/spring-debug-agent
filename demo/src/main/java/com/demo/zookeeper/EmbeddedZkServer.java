package com.demo.zookeeper;

import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.io.IOException;

/**
 * Starts an embedded ZooKeeper server for demo purposes.
 * This allows Dubbo to use ZK as its registry center without an external server.
 *
 * The TestingServer runs on port 2181 and is started before Dubbo initializes.
 */
@Configuration
public class EmbeddedZkServer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedZkServer.class);

    @Bean(destroyMethod = "close")
    public TestingServer testingServer() throws Exception {
        TestingServer server = new TestingServer(2181, true);
        log.info("Embedded ZooKeeper server started on port 2181");
        return server;
    }
}
