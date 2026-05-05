package com.loadtest.app2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestApp2Application {
    public static void main(String[] args) {
        SpringApplication.run(TestApp2Application.class, args);
    }
}
