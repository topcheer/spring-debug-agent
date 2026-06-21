package com.demo.nacos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Nacos demo: a @RefreshScope bean whose properties can be refreshed
 * from Nacos config server. Visible to NacosInspector.
 */
@Component
@RefreshScope
public class NacosDynamicConfig {

    private static final Logger log = LoggerFactory.getLogger(NacosDynamicConfig.class);

    /**
     * These properties would come from Nacos config server's
     * 'order-management-demo.yml' dataId when the server is running.
     */
    @org.springframework.beans.factory.annotation.Value("${dynamic.feature.flag.enabled:false}")
    private boolean featureFlagEnabled;

    @org.springframework.beans.factory.annotation.Value("${dynamic.max.connections:100}")
    private int maxConnections;

    public boolean isFeatureFlagEnabled() {
        return featureFlagEnabled;
    }

    public int getMaxConnections() {
        return maxConnections;
    }
}
