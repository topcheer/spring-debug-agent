package com.demo.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign request interceptor config for OpenFeignInspector demo.
 * Adds correlation ID and auth headers to every Feign request.
 */
@Configuration
public class FeignInterceptorConfig {

    private static final Logger log = LoggerFactory.getLogger(FeignInterceptorConfig.class);

    @Bean
    public RequestInterceptor correlationIdInterceptor() {
        return (RequestTemplate template) -> {
            String correlationId = java.util.UUID.randomUUID().toString();
            template.header("X-Correlation-ID", correlationId);
            log.debug("Feign request to {} with correlation ID: {}",
                    template.url(), correlationId);
        };
    }

    @Bean
    public RequestInterceptor authHeaderInterceptor() {
        return (RequestTemplate template) -> {
            template.header("Authorization", "Bearer demo-token");
        };
    }
}
