package com.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableAspectJAutoProxy
@EnableFeignClients(basePackageClasses = com.demo.feign.FeignClientConfig.class)
public class OrderManagementApp {
    public static void main(String[] args) {
        SpringApplication.run(OrderManagementApp.class, args);
    }
}
