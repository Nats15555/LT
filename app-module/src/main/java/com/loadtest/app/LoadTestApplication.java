package com.loadtest.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LoadTestApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(LoadTestApplication.class, args);
    }
}
