package com.zwiki;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 *
 *
 * @author: CYM-pai
 * @date: 2026/01/29 17:35
 **/
@SpringBootApplication
@EnableAsync
@EnableFeignClients
@EnableDiscoveryClient
public class ZwikiReviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(com.zwiki.ZwikiReviewerApplication.class, args);
    }
} 
