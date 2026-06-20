package com.demo.config;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentMap;

/**
 * Embedded Hazelcast configuration for distributed cache demo.
 */
@Configuration
public class HazelcastConfig {

    @Bean
    public HazelcastInstance hazelcastInstance() {
        Config config = new Config();
        config.setClusterName("demo-cluster");
        config.getNetworkConfig().setPort(5701);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);

        return com.hazelcast.core.Hazelcast.newHazelcastInstance(config);
    }

    @Bean("orderCacheMap")
    public IMap<String, String> orderCacheMap(HazelcastInstance hazelcastInstance) {
        IMap<String, String> map = hazelcastInstance.getMap("order-cache");
        map.put("order-001", "Alice Johnson - $129.99");
        map.put("order-002", "Bob Smith - $45.50");
        map.put("order-003", "Charlie Brown - $299.00");
        return map;
    }
}
