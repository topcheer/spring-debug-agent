package com.demo.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC channel configuration.
 * Creates a ManagedChannel bean pointing to a sample gRPC server address.
 */
@Configuration
public class GrpcConfig {

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel grpcManagedChannel() {
        return ManagedChannelBuilder
                .forAddress("localhost", 9090)
                .usePlaintext()
                .build();
    }
}
