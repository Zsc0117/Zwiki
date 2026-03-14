package com.zwiki;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableWebMvc
@EnableFeignClients
@EnableDiscoveryClient
@EnableRedisHttpSession
@Slf4j
public class ZwikiRepositoryWikiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwikiRepositoryWikiApplication.class,args);
    }
}