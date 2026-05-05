package com.loadtest.app1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestApp1Application {
    public static void main(String[] args) {
        SpringApplication.run(TestApp1Application.class, args);
    }
}
