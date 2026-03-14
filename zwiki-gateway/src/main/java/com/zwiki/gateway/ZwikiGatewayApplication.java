package com.zwiki.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Zwiki API网关启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ZwikiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwikiGatewayApplication.class, args);
    }
}
