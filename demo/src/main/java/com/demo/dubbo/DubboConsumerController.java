package com.demo.dubbo;

import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dubbo consumer — injects the service via @DubboReference.
 */
@RestController
public class DubboConsumerController {

    private static final Logger log = LoggerFactory.getLogger(DubboConsumerController.class);

    @DubboReference(version = "1.0.0", timeout = 5000, check = false)
    private GreetingService greetingService;

    @GetMapping("/dubbo/greet")
    public String greet(@RequestParam(defaultValue = "World") String name) {
        try {
            return greetingService.sayHello(name);
        } catch (Exception e) {
            log.warn("Dubbo call failed: {}", e.getMessage());
            return "Dubbo service call failed: " + e.getMessage();
        }
    }
}
