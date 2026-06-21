package com.demo.dubbo;

/**
 * Dubbo service interface.
 */
public interface GreetingService {
    String sayHello(String name);
    String getTime();
}
