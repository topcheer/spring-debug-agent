package com.demo.dubbo;

import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * Dubbo service implementation — visible to DubboInspector.
 * Registry is N/A so it won't try to connect to an external registry.
 */
@DubboService(version = "1.0.0", timeout = 3000)
public class GreetingServiceImpl implements GreetingService {

    private static final Logger log = LoggerFactory.getLogger(GreetingServiceImpl.class);

    @Override
    public String sayHello(String name) {
        log.info("Dubbo: greeting {}", name);
        return "Hello, " + name + "! This is a Dubbo service response.";
    }

    @Override
    public String getTime() {
        return "Server time: " + LocalDateTime.now();
    }
}
